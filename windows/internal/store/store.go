// Package store persists profiles.json (+ opens usage-stats.json) in the
// location from docs/PROFILE_SCHEMA.md: %APPDATA%\SSTPflash on Windows,
// $SSTPFLASH_HOME or ~/.config/SSTPflash elsewhere (dev/test).
package store

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"

	"sstpflash/usage"
	"sstpflash/windows/internal/engine"
)

var mu sync.Mutex

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
		dir = filepath.Join(base, "SSTPflash")
	} else if h := os.Getenv("SSTPFLASH_HOME"); h != "" {
		dir = h
	} else {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		dir = filepath.Join(home, ".config", "SSTPflash")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", fmt.Errorf("config dir: %w", err)
	}
	return dir, nil
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

// Save writes the whole profile list atomically.
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
	b, err := json.MarshalIndent(&f, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
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

// OpenUsage opens the lifetime usage store (sstpflash/usage) next to
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
