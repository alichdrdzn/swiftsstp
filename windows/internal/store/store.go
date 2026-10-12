// Package store persists profiles.json and settings.json (+ opens
// usage-stats.json) in the location from docs/PROFILE_SCHEMA.md:
// %APPDATA%\SwiftSSTP on Windows,
// $SWIFTSSTP_HOME or ~/.config/SwiftSSTP elsewhere (dev/test).
//
// Users upgrading from the pre-rename SSTPflash builds are migrated on
// first use: the legacy config directory (%APPDATA%\SSTPflash /
// ~/.config/SSTPflash) is renamed to the new SwiftSSTP path when the new
// path does not exist yet, and the legacy SSTPFLASH_HOME env var is still
// honored as a fallback when SWIFTSSTP_HOME is unset.
package store

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	"swiftsstp/usage"
	"swiftsstp/windows/internal/engine"
)

var mu sync.Mutex

// Legacy names from the pre-rename SSTPflash builds; kept only so
// existing installs migrate to the SwiftSSTP names.
const (
	legacyDirName = "SSTPflash"
	legacyEnvVar  = "SSTPFLASH_HOME"
)

// Dir returns (and creates) the config directory.
func Dir() (string, error) {
	var dir string
	if runtime.GOOS == "windows" {
		base := os.Getenv("APPDATA")
		if base == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return "", err
			}
			base = filepath.Join(home, "AppData", "Roaming")
		}
		dir = migrateLegacy(filepath.Join(base, legacyDirName), filepath.Join(base, "SwiftSSTP"))
	} else if h := os.Getenv("SWIFTSSTP_HOME"); h != "" {
		dir = h
	} else if h := os.Getenv(legacyEnvVar); h != "" {
		// Pre-rename env override: still honored verbatim.
		dir = h
	} else {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		dir = migrateLegacy(filepath.Join(home, ".config", legacyDirName), filepath.Join(home, ".config", "SwiftSSTP"))
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("config dir: %w", err)
	}
	return dir, nil
}

// migrateLegacy returns the SwiftSSTP config path, renaming the legacy
// SSTPflash directory there when the new path is absent and the old one
// exists. Rename is best effort: if it fails (e.g. a file is locked), the
// legacy directory is returned so existing data keeps working.
func migrateLegacy(legacyDir, newDir string) string {
	if _, err := os.Stat(newDir); err == nil {
		return newDir // fresh install or already migrated
	}
	if _, err := os.Stat(legacyDir); err != nil {
		return newDir // nothing to migrate
	}
	if err := os.Rename(legacyDir, newDir); err != nil {
		return legacyDir
	}
	return newDir
}

func profilesPath() (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "profiles.json"), nil
}

// List loads all profiles (missing file = empty list).
func List() ([]engine.Profile, error) {
	mu.Lock()
	defer mu.Unlock()
	path, err := profilesPath()
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var f engine.ProfilesFile
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, fmt.Errorf("profiles.json: %w", err)
	}
	return f.Profiles, nil
}

// movedSettingsKeys are the app-wide fields that now live in
// settings.json (docs/PROFILE_SCHEMA.md); writers must not put them back
// into profiles.json.
var movedSettingsKeys = []string{
	"mtu", "ipv4_enabled", "ipv6_enabled", "dns_override",
	"auto_reconnect", "proxy", "tunnels",
}

// Save writes the whole profile list atomically, without the moved
// app-wide keys (see marshalProfiles).
func Save(profiles []engine.Profile) error {
	mu.Lock()
	defer mu.Unlock()
	path, err := profilesPath()
	if err != nil {
		return err
	}
	f := engine.ProfilesFile{Version: 1, Profiles: profiles}
	if f.Profiles == nil {
		f.Profiles = []engine.Profile{}
	}
	b, err := marshalProfiles(&f)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// marshalProfiles renders profiles.json with the moved settings keys
// stripped from every entry, so the file converges on the documented
// shape even when an older profiles.json is read and re-saved. This is
// only about what gets written: readers already treat those leftovers as
// stale (List decodes them into the struct fields, which connect always
// overwrites from settings.json before dialing).
func marshalProfiles(f *engine.ProfilesFile) ([]byte, error) {
	raw, err := json.Marshal(f)
	if err != nil {
		return nil, err
	}
	// Round-trip through a generic document so each profile entry is a
	// map we can prune. UseNumber keeps integers exact on the way out.
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var doc map[string]any
	if err := dec.Decode(&doc); err != nil {
		return nil, err
	}
	if list, ok := doc["profiles"].([]any); ok {
		for _, entry := range list {
			m, ok := entry.(map[string]any)
			if !ok {
				continue
			}
			for _, k := range movedSettingsKeys {
				delete(m, k)
			}
		}
	}
	return json.MarshalIndent(&doc, "", "  ")
}

// Upsert inserts or replaces a profile by ID (assigning an ID if empty).
func Upsert(p engine.Profile) (engine.Profile, error) {
	if p.ID == "" {
		p.ID = NewID()
	}
	profiles, err := List()
	if err != nil {
		return p, err
	}
	replaced := false
	for i := range profiles {
		if profiles[i].ID == p.ID {
			profiles[i] = p
			replaced = true
		}
	}
	if !replaced {
		profiles = append(profiles, p)
	}
	return p, Save(profiles)
}

// Delete removes a profile by ID.
func Delete(id string) error {
	profiles, err := List()
	if err != nil {
		return err
	}
	out := profiles[:0]
	for _, p := range profiles {
		if p.ID != id {
			out = append(out, p)
		}
	}
	return Save(out)
}

// OpenUsage opens the lifetime usage store (swiftsstp/usage) next to
// profiles.json: usage-stats.json in the config directory.
func OpenUsage() (*usage.Store, error) {
	dir, err := Dir()
	if err != nil {
		return nil, err
	}
	return usage.Open(filepath.Join(dir, "usage-stats.json"))
}

// NewID returns the schema's "random-hex-16" identifier.
func NewID() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%016x", os.Getpid())
	}
	return hex.EncodeToString(b[:])
}
