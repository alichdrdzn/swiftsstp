// Package update implements the stable in-app update channel for the
// Windows shell: fetch the release manifest (docs/UPDATE_SCHEMA.md),
// compare it against the running build, download the asset, verify its
// SHA-256 and replace the running exe.
//
// Everything here is advisory and failure-tolerant: a check that cannot
// reach GitHub returns an error the UI swallows, and nothing is ever
// replaced before the new image has been verified.
package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"swiftsstp/windows/internal/store"
)

// manifestURL is the stable-channel manifest behind GitHub's
// releases/latest redirect: no API call, no token, and the ETag lets
// clients revalidate cheaply (see docs/UPDATE_SCHEMA.md).
const manifestURL = "https://github.com/alichdrdzn/swiftsstp/releases/latest/download/update-manifest.json"

const (
	manifestSchema = 1
	channelName    = "stable"
	platform       = "windows" // key of this shell's entry in assets
	stateFileName  = "update-state.json"

	// The check must never hold anything up: one short request, no retries.
	checkTimeout = 6 * time.Second
	// A full installer is tens of megabytes. Slow links get a generous but
	// finite ceiling so a stalled socket cannot hang the action forever.
	downloadTimeout = 15 * time.Minute
	// The manifest is a few hundred bytes; anything larger is not one.
	maxManifestSize = 256 << 10
)

// Asset is one platform's artifact in the manifest.
type Asset struct {
	Name   string `json:"name"`
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

// Manifest is the wire format documented in docs/UPDATE_SCHEMA.md (only
// the fields this shell reads; the rest is ignored by the decoder).
type Manifest struct {
	Schema     int              `json:"schema"`
	Channel    string           `json:"channel"`
	Version    string           `json:"version"`
	NotesURL   string           `json:"notes_url"`
	MinVersion string           `json:"min_version"`
	Assets     map[string]Asset `json:"assets"`
}

// Info is what the UI needs to offer (and install) the update.
type Info struct {
	Available bool   `json:"available"`
	Version   string `json:"version"`
	NotesURL  string `json:"notes_url"`
	Name      string `json:"name"`
	URL       string `json:"url"`
	SHA256    string `json:"sha256"`
	Size      int64  `json:"size"`
}

// stateFile persists the ETag — and the manifest that tag belongs to —
// in the config directory, so a 304 answer still yields a full offer.
type stateFile struct {
	ETag     string    `json:"etag"`
	Manifest *Manifest `json:"manifest,omitempty"`
}

// Progress is the pollable download state.
type Progress struct {
	Phase string `json:"phase"` // idle|downloading|verifying|done|error
	Done  int64  `json:"done"`
	Total int64  `json:"total"`
	Error string `json:"error,omitempty"`
}

type downloadState struct {
	Progress
	path string // verified installer awaiting Apply
}

var mu sync.Mutex

var dl downloadState

// Snapshot returns the current download progress for the UI.
func Snapshot() Progress {
	mu.Lock()
	defer mu.Unlock()
	return dl.Progress
}

// setProgress mutates the shared download state under the package lock.
func setProgress(f func(*downloadState)) {
	mu.Lock()
	defer mu.Unlock()
	f(&dl)
}

// Check fetches the manifest and compares it against the running version.
// A build that does not know its own version ("dev", anything built
// outside CI) never claims an update — per the client rules in
// docs/UPDATE_SCHEMA.md — and neither does an unparsable one.
func Check(current string) (Info, error) {
	if !knownVersion(current) {
		return Info{}, nil
	}
	m, err := fetchManifest()
	if err != nil {
		return Info{}, fmt.Errorf("update check: %w", err)
	}
	if m.Schema != manifestSchema {
		return Info{}, fmt.Errorf("update check: unsupported manifest schema %d", m.Schema)
	}
	if m.Channel != channelName {
		return Info{}, fmt.Errorf("update check: unexpected channel %q", m.Channel)
	}
	info := Info{Version: m.Version, NotesURL: m.NotesURL}
	asset, ok := m.Assets[platform]
	// A release without an asset for this platform is simply not an update
	// here — not an error.
	if !ok || asset.URL == "" || strings.TrimSpace(asset.SHA256) == "" {
		return info, nil
	}
	if !newer(m.Version, current) {
		return info, nil
	}
	info.Available = true
	info.Name = asset.Name
	info.URL = asset.URL
	info.SHA256 = strings.ToLower(strings.TrimSpace(asset.SHA256))
	info.Size = asset.Size
	return info, nil
}

// knownVersion reports whether the build knows its own version. CI stamps
// it with -ldflags "-X main.version=…"; anything else is a local build
// that must not be told to replace itself.
func knownVersion(v string) bool {
	if v == "" || v == "dev" {
		return false
	}
	_, _, ok := parseSemver(v)
	return ok
}

// parseSemver splits a dotted version into its numeric core and the
// prerelease part after '-'; build metadata after '+' is ignored.
func parseSemver(s string) (core []int, pre string, ok bool) {
	s = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(s), "v"))
	if i := strings.IndexByte(s, '+'); i >= 0 {
		s = s[:i]
	}
	if i := strings.IndexByte(s, '-'); i >= 0 {
		pre, s = s[i+1:], s[:i]
	}
	if s == "" {
		return nil, pre, false
	}
	parts := strings.Split(s, ".")
	core = make([]int, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return nil, pre, false
		}
		core = append(core, n)
	}
	return core, pre, true
}

// newer reports whether a is a strictly newer release than b. Prerelease
// ordering is deliberately minimal — an exact release outranks the same
// core carrying a prerelease, otherwise the core decides. The stable
// channel only ever ships plain releases, so full semver precedence
// would be dead weight; and if either side is unparsable we never claim
// an update.
func newer(a, b string) bool {
	ac, ap, aok := parseSemver(a)
	bc, bp, bok := parseSemver(b)
	if !aok || !bok {
		return false
	}
	n := len(ac)
	if len(bc) > n {
		n = len(bc)
	}
	for i := 0; i < n; i++ {
		x, y := 0, 0
		if i < len(ac) {
			x = ac[i]
		}
		if i < len(bc) {
			y = bc[i]
		}
		if x != y {
			return x > y
		}
	}
	if ap == bp {
		return false
	}
	return ap == "" // a plain release is newer than its own prerelease
}

func fetchManifest() (*Manifest, error) {
	st := loadState()
	m, etag, notModified, err := doFetch(st)
	if err != nil {
		return nil, err
	}
	if notModified {
		if st != nil && st.Manifest != nil {
			return st.Manifest, nil
		}
		// 304 with no cached body to pair it with: re-ask unconditionally.
		req, err := http.NewRequest(http.MethodGet, manifestURL, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("User-Agent", "SwiftSSTP")
		m, etag, notModified, err = doFetchWith(req)
		if err != nil {
			return nil, err
		}
		if notModified {
			return nil, fmt.Errorf("304 Not Modified but no manifest is cached")
		}
	}
	saveState(stateFile{ETag: etag, Manifest: m})
	return m, nil
}

// doFetch issues the conditional request; doFetchWith does the actual
// HTTP work so the unconditional retry above can reuse it.
func doFetch(st *stateFile) (*Manifest, string, bool, error) {
	req, err := http.NewRequest(http.MethodGet, manifestURL, nil)
	if err != nil {
		return nil, "", false, err
	}
	req.Header.Set("User-Agent", "SwiftSSTP")
	if st != nil && st.ETag != "" {
		req.Header.Set("If-None-Match", st.ETag)
	}
	return doFetchWith(req)
}

func doFetchWith(req *http.Request) (*Manifest, string, bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), checkTimeout)
	defer cancel()
	resp, err := (&http.Client{}).Do(req.WithContext(ctx))
	if err != nil {
		return nil, "", false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		return nil, resp.Header.Get("Etag"), true, nil
	}
	if resp.StatusCode != http.StatusOK {
		return nil, "", false, fmt.Errorf("manifest returned %s", resp.Status)
	}
	var m Manifest
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxManifestSize)).Decode(&m); err != nil {
		return nil, "", false, fmt.Errorf("manifest: %w", err)
	}
	return &m, resp.Header.Get("Etag"), false, nil
}

func statePath() (string, error) {
	dir, err := store.Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, stateFileName), nil
}

func loadState() *stateFile {
	path, err := statePath()
	if err != nil {
		return nil
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var st stateFile
	if err := json.Unmarshal(b, &st); err != nil {
		return nil
	}
	return &st
}

// saveState is a cache write: a failure here only costs an ETag later.
func saveState(st stateFile) {
	path, err := statePath()
	if err != nil {
		return
	}
	b, err := json.Marshal(st)
	if err != nil {
		return
	}
	_ = os.WriteFile(path, b, 0o600)
}

// Download streams info's asset to a temp file and verifies its SHA-256
// against the manifest before returning the local path. A mismatch is a
// hard error and the file is deleted. Progress is published through
// Snapshot() while this call blocks, so the UI can show bytes/percent.
func Download(info Info) (string, error) {
	mu.Lock()
	if dl.Phase == "downloading" || dl.Phase == "verifying" {
		mu.Unlock()
		return "", fmt.Errorf("an update download is already in progress")
	}
	dl = downloadState{Progress: Progress{Phase: "downloading", Total: info.Size}}
	mu.Unlock()

	path, written, err := download(info)
	setProgress(func(d *downloadState) {
		if err != nil {
			*d = downloadState{Progress: Progress{Phase: "error", Error: err.Error()}}
			return
		}
		*d = downloadState{Progress: Progress{Phase: "done", Done: written, Total: written}, path: path}
	})
	if err != nil {
		return "", err
	}
	return path, nil
}

func download(info Info) (path string, written int64, err error) {
	if info.URL == "" || info.SHA256 == "" {
		return "", 0, fmt.Errorf("no update asset to download")
	}
	ctx, cancel := context.WithTimeout(context.Background(), downloadTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, info.URL, nil)
	if err != nil {
		return "", 0, err
	}
	req.Header.Set("User-Agent", "SwiftSSTP")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", 0, fmt.Errorf("download: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", 0, fmt.Errorf("download: %s", resp.Status)
	}
	total := info.Size
	if resp.ContentLength > 0 {
		total = resp.ContentLength
	}
	setProgress(func(d *downloadState) { d.Total = total })

	// Temp file: Apply copies it over the exe afterwards, so it only has
	// to survive until the user confirms.
	out, err := os.CreateTemp("", "swiftsstp-update-*.exe")
	if err != nil {
		return "", 0, err
	}
	tmp := out.Name()
	fail := func(e error) (string, int64, error) {
		out.Close()
		_ = os.Remove(tmp) // a partial or corrupt download is never left behind
		return "", 0, e
	}

	h := sha256.New()
	written, err = io.Copy(io.MultiWriter(out, h, &progressWriter{}), resp.Body)
	if err != nil {
		return fail(fmt.Errorf("download: %w", err))
	}
	if err := out.Close(); err != nil {
		return fail(fmt.Errorf("download: write: %w", err))
	}
	setProgress(func(d *downloadState) { d.Phase = "verifying" })
	if got := hex.EncodeToString(h.Sum(nil)); got != info.SHA256 {
		return fail(fmt.Errorf("checksum mismatch: got %s, want %s", got, info.SHA256))
	}
	if info.Size > 0 && written != info.Size {
		return fail(fmt.Errorf("size mismatch: got %d bytes, manifest says %d", written, info.Size))
	}
	return tmp, written, nil
}

// progressWriter mirrors bytes into the shared state for Snapshot().
type progressWriter struct{ n int64 }

func (w *progressWriter) Write(p []byte) (int, error) {
	w.n += int64(len(p))
	n := w.n
	setProgress(func(d *downloadState) { d.Done = n })
	return len(p), nil
}

// ApplyResult describes a replacement that did NOT happen, so the UI can
// explain why and fall back to the release page. Applied=true means the
// new build is already running and the caller should exit.
type ApplyResult struct {
	Applied    bool   `json:"applied"`
	Unwritable bool   `json:"unwritable,omitempty"`
	Error      string `json:"error,omitempty"`
}

// Apply replaces the running exe with the verified download: the current
// image is renamed to <exe>.old (Windows allows renaming a running exe,
// just not deleting it), the new one is copied into its place, and the
// new process is started. Every failure path either rolls the rename
// back or reports without having touched anything — never a
// half-replaced install.
func Apply() (ApplyResult, error) {
	mu.Lock()
	src := dl.path
	mu.Unlock()
	if src == "" {
		return ApplyResult{}, fmt.Errorf("no verified update to install — download it first")
	}

	exe, err := os.Executable()
	if err != nil {
		return ApplyResult{}, fmt.Errorf("locate running executable: %w", err)
	}
	dir := filepath.Dir(exe)

	// Probe write access before touching anything: Program Files without
	// elevation would otherwise fail halfway through the swap.
	if !dirWritable(dir) {
		return ApplyResult{
			Unwritable: true,
			Error:      "This folder needs administrator rights — open the release page to update manually.",
		}, nil
	}

	old := exe + ".old"
	_ = os.Remove(old) // stale image from an earlier update; rename needs the target gone
	if err := os.Rename(exe, old); err != nil {
		return ApplyResult{}, fmt.Errorf("could not move %s aside: %w", filepath.Base(exe), err)
	}
	if err := copyFile(src, exe); err != nil {
		// Put the old image back so the install is never left without an exe.
		_ = os.Rename(old, exe)
		return ApplyResult{}, fmt.Errorf("could not write the new version: %w", err)
	}

	// Hand off to the new build with our working directory, then detach:
	// the caller (app.go) exits once this reply reaches the UI.
	cmd := exec.Command(exe)
	if wd, err := os.Getwd(); err == nil {
		cmd.Dir = wd
	}
	if err := cmd.Start(); err != nil {
		_ = os.Remove(exe)
		_ = os.Rename(old, exe) // spawn failed: roll back to the running image
		return ApplyResult{}, fmt.Errorf("could not start the new version: %w", err)
	}
	_ = cmd.Process.Release()

	mu.Lock()
	dl.path = "" // consumed
	mu.Unlock()
	return ApplyResult{Applied: true}, nil
}

// dirWritable probes a directory by creating and removing a scratch file.
func dirWritable(dir string) bool {
	f, err := os.CreateTemp(dir, ".swiftsstp-write-test-*")
	if err != nil {
		return false
	}
	name := f.Name()
	f.Close()
	return os.Remove(name) == nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// Cleanup deletes the previous image (<exe>.old) left behind by Apply.
// The old process is still winding down when the new one starts, so the
// file is briefly locked: retry in the background instead of delaying
// startup, and give up silently — worst case is one stale file.
func Cleanup() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	old := exe + ".old"
	go func() {
		for i := 0; i < 20; i++ {
			if err := os.Remove(old); err == nil || os.IsNotExist(err) {
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
	}()
}
