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
		// An existing file can still predate the three local-proxy keys:
		// settings.json shipped in an earlier release without them, and
		// only a missing-file migration would ever fold them in. Left
		// unfixed, the first connect after the upgrade would write the
		// built-in defaults over the ports and the system proxy toggle
		// the user had configured, which is a silent loss of settings.
		s, changed, err := mergeLocalProxyKnobs(b)
		if err != nil {
			return engine.Settings{}, err
		}
		if changed {
			if err := SaveSettings(s); err != nil {
				return s, fmt.Errorf("settings.json: migrate: %w", err)
			}
		}
		return s, nil
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
// The three local-proxy knobs count as moved fields as well; they just
// live one level deeper, under each profile's extras.
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
	// The three local-proxy knobs (socks_port, http_port, system_proxy)
	// used to live in each profile's extras map, so they are not top
	// level keys: look inside extras and take each one only when the key
	// is actually there, flagging a value that differs from the default
	// exactly like the fields above. SettingsFrom already resolved the
	// accessors against the defaults, so a profiles.json that carries
	// none of them simply seeds the defaults.
	if ex, ok := rawExtras(entry); ok {
		if _, ok := ex["socks_port"]; ok {
			differs = differs || c.SocksPort != d.SocksPort
			s.SocksPort = c.SocksPort
		}
		if _, ok := ex["http_port"]; ok {
			differs = differs || c.HTTPPort != d.HTTPPort
			s.HTTPPort = c.HTTPPort
		}
		if _, ok := ex["system_proxy"]; ok {
			differs = differs || c.SystemProxy != d.SystemProxy
			s.SystemProxy = c.SystemProxy
		}
	}
	return s, differs
}

// rawExtras decodes a profile entry's "extras" object into a generic map
// so the migration can see which keys the legacy file actually carries.
// A missing, null or non-object extras decodes as ok == false and the
// caller keeps the defaults.
func rawExtras(entry map[string]json.RawMessage) (map[string]json.RawMessage, bool) {
	raw, ok := entry["extras"]
	if !ok {
		return nil, false
	}
	var ex map[string]json.RawMessage
	if err := json.Unmarshal(raw, &ex); err != nil || ex == nil {
		return nil, false
	}
	return ex, true
}

// localProxyKeys are the three knobs that moved from each profile's extras
// into settings.json after that file had already shipped, so a settings.json
// written by the earlier release carries none of them.
var localProxyKeys = []string{"socks_port", "http_port", "system_proxy"}

// mergeLocalProxyKnobs parses a settings.json that already exists and folds
// in whichever of the three local-proxy keys it is missing, taking each
// value from the first profile (file order) whose extras carry it. Keys the
// file does carry are left exactly as the user configured them, so this pass
// is a one-way completion and never a re-seed. It reports whether anything
// changed so the caller can persist the result once.
func mergeLocalProxyKnobs(b []byte) (engine.Settings, bool, error) {
	var f settingsFile
	if err := json.Unmarshal(b, &f); err != nil {
		return engine.Settings{}, false, fmt.Errorf("settings.json: %w", err)
	}
	// Key presence has to be read from the raw object: the embedded
	// engine.Settings decodes an absent socks_port and an explicit 0 into
	// the same zero value, and only the raw form tells them apart.
	var keys map[string]json.RawMessage
	if err := json.Unmarshal(b, &keys); err != nil {
		return engine.Settings{}, false, fmt.Errorf("settings.json: %w", err)
	}
	missing := false
	for _, k := range localProxyKeys {
		if _, ok := keys[k]; !ok {
			missing = true
		}
	}
	if !missing {
		return f.Settings, false, nil
	}
	seed, err := seedExtrasFromProfiles()
	if err != nil {
		return engine.Settings{}, false, err
	}
	s := f.Settings
	if _, ok := keys["socks_port"]; !ok {
		s.SocksPort = seed.SocksPort
	}
	if _, ok := keys["http_port"]; !ok {
		s.HTTPPort = seed.HTTPPort
	}
	if _, ok := keys["system_proxy"]; !ok {
		s.SystemProxy = seed.SystemProxy
	}
	return s, true, nil
}

// seedExtrasFromProfiles scans profiles.json the way seedFromProfiles does
// and returns the values of the first profile (file order) that still
// carries any of the three local-proxy knobs in extras, keeping the built-in
// default for the keys that entry does not carry. Save prunes these keys out
// of profiles.json, so a converged file simply yields the defaults, which is
// the documented behaviour for installs that never had the legacy extras.
func seedExtrasFromProfiles() (engine.Settings, error) {
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
		ex, ok := rawExtras(entry)
		if !ok {
			continue
		}
		s := engine.DefaultSettings()
		carried := false
		if raw, ok := ex["socks_port"]; ok {
			var v int
			if err := json.Unmarshal(raw, &v); err == nil {
				s.SocksPort = v
				carried = true
			}
		}
		if raw, ok := ex["http_port"]; ok {
			var v int
			if err := json.Unmarshal(raw, &v); err == nil {
				s.HTTPPort = v
				carried = true
			}
		}
		if raw, ok := ex["system_proxy"]; ok {
			var v bool
			if err := json.Unmarshal(raw, &v); err == nil {
				s.SystemProxy = v
				carried = true
			}
		}
		if carried {
			return s, nil
		}
	}
	return engine.DefaultSettings(), nil
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
