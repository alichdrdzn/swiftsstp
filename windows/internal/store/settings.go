package store

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"swiftsstp/windows/internal/engine"
)

// settingsFile is the on-disk settings.json: the schema's version marker
// plus the shared Settings payload. The embedded struct flattens, so the
// moved keys sit at the top level exactly as docs/PROFILE_SCHEMA.md
// shows them. app_rules is Android-only and is never written here; a
// foreign settings.json that carries it decodes into nothing.
type settingsFile struct {
	Version int `json:"version"`
	engine.Settings
}

func settingsPath() (string, error) {
	dir, err := Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "settings.json"), nil
}

// LoadSettings reads settings.json, which always lives next to
// profiles.json. A missing file is migrated per docs/PROFILE_SCHEMA.md:
// it is created from the first profile (file order) whose moved fields
// differ from the built-in defaults in at least one of them, so
// existing installs keep the values they had configured; when no profile
// differs the defaults are written. Either way the file exists
// afterwards.
func LoadSettings() (engine.Settings, error) {
	path, err := settingsPath()
	if err != nil {
		return engine.Settings{}, err
	}
	mu.Lock()
	b, err := os.ReadFile(path)
	mu.Unlock()
	if err == nil {
		var f settingsFile
		if err := json.Unmarshal(b, &f); err != nil {
			return engine.Settings{}, fmt.Errorf("settings.json: %w", err)
		}
		return f.Settings, nil
	}
	if !os.IsNotExist(err) {
		return engine.Settings{}, err
	}

	// Migration: seed from the first profile that differs from the
	// defaults (values are copied verbatim — a "tunnels": 0 decodes as
	// "absent", which Validate and TunnelCount treat as the default 1).
	s, err := seedFromProfiles()
	if err != nil {
		return engine.Settings{}, err
	}
	if err := SaveSettings(s); err != nil {
		return s, fmt.Errorf("settings.json: migrate: %w", err)
	}
	return s, nil
}

// seedFromProfiles reads profiles.json the way it sits on disk and
// returns the documented migration seed: the first profile (file order)
// that differs from the built-in defaults in at least one moved field,
// or the defaults when none does. Only keys the file actually carries
// are taken: a profiles.json that Save already converged on the
// documented shape carries none of them and must not seed zero values.
func seedFromProfiles() (engine.Settings, error) {
	path, err := profilesPath()
	if err != nil {
		return engine.Settings{}, err
	}
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return engine.DefaultSettings(), nil
		}
		return engine.Settings{}, err
	}
	var doc struct {
		Profiles []map[string]json.RawMessage `json:"profiles"`
	}
	if err := json.Unmarshal(b, &doc); err != nil {
		return engine.Settings{}, fmt.Errorf("profiles.json: %w", err)
	}
	for _, entry := range doc.Profiles {
		if s, differs := seedFromProfile(entry); differs {
			return s, nil
		}
	}
	return engine.DefaultSettings(), nil
}

// seedFromProfile takes the moved fields a single raw profile entry
// carries (absent keys keep the built-in default) and reports whether
// any of them differs from that default.
func seedFromProfile(entry map[string]json.RawMessage) (engine.Settings, bool) {
	raw, err := json.Marshal(entry)
	if err != nil {
		return engine.Settings{}, false
	}
	var p engine.Profile
	if err := json.Unmarshal(raw, &p); err != nil {
		return engine.Settings{}, false
	}
	d := engine.DefaultSettings()
	c := engine.SettingsFrom(p)
	s := d
	differs := false
	if _, ok := entry["mtu"]; ok {
		differs = differs || c.MTU != d.MTU
		s.MTU = c.MTU
	}
	if _, ok := entry["ipv4_enabled"]; ok {
		differs = differs || c.IPv4Enabled != d.IPv4Enabled
		s.IPv4Enabled = c.IPv4Enabled
	}
	if _, ok := entry["ipv6_enabled"]; ok {
		differs = differs || c.IPv6Enabled != d.IPv6Enabled
		s.IPv6Enabled = c.IPv6Enabled
	}
	if _, ok := entry["dns_override"]; ok {
		differs = differs || !sameStrings(c.DNSOverride, d.DNSOverride)
		s.DNSOverride = c.DNSOverride
	}
	if _, ok := entry["auto_reconnect"]; ok {
		differs = differs || c.AutoReconnect != d.AutoReconnect
		s.AutoReconnect = c.AutoReconnect
	}
	if _, ok := entry["proxy"]; ok {
		differs = differs || c.Proxy != d.Proxy
		s.Proxy = c.Proxy
	}
	if _, ok := entry["tunnels"]; ok {
		differs = differs || c.Tunnels != d.Tunnels
		s.Tunnels = c.Tunnels
	}
	return s, differs
}

// SaveSettings writes settings.json atomically (tmp file + rename, the
// same pattern as Save).
func SaveSettings(s engine.Settings) error {
	mu.Lock()
	defer mu.Unlock()
	path, err := settingsPath()
	if err != nil {
		return err
	}
	f := settingsFile{Version: 1, Settings: s}
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

// sameStrings reports whether two slices match, treating nil and empty
// as the same list.
func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
