package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"swiftsstp/windows/internal/engine"
)

func freshHome(t *testing.T) string {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("SWIFTSSTP_HOME", "")
	t.Setenv("SSTPFLASH_HOME", "")
	return home
}

func TestDirMigratesLegacy(t *testing.T) {
	home := freshHome(t)
	legacy := filepath.Join(home, ".config", "SSTPflash")
	if err := os.MkdirAll(legacy, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacy, "profiles.json"), []byte(`{"version":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(home, ".config", "SwiftSSTP")
	if dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	if _, err := os.Stat(filepath.Join(dir, "profiles.json")); err != nil {
		t.Fatalf("profiles.json not migrated: %v", err)
	}
	// Second call is stable (no re-migration).
	dir2, err := Dir()
	if err != nil || dir2 != want {
		t.Fatalf("second Dir() = %q, %v", dir2, err)
	}
}

func TestDirFreshInstall(t *testing.T) {
	home := freshHome(t)
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".config", "SwiftSSTP"); dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	if _, err := os.Stat(filepath.Join(home, ".config", "SSTPflash")); !os.IsNotExist(err) {
		t.Fatalf("legacy dir should not exist: %v", err)
	}
}

func TestDirKeepsNewWhenBothExist(t *testing.T) {
	home := freshHome(t)
	for _, d := range []string{"SSTPflash", "SwiftSSTP"} {
		p := filepath.Join(home, ".config", d)
		if err := os.MkdirAll(p, 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(p, "profiles.json"), []byte(d), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".config", "SwiftSSTP"); dir != want {
		t.Fatalf("Dir() = %q, want %q", dir, want)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "profiles.json"))
	if string(b) != "SwiftSSTP" {
		t.Fatalf("new dir contents clobbered: %q", b)
	}
}

func TestDirLegacyEnvFallback(t *testing.T) {
	home := freshHome(t)
	legacyHome := filepath.Join(home, "oldcfg")
	t.Setenv("SSTPFLASH_HOME", legacyHome)
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	if dir != legacyHome {
		t.Fatalf("Dir() = %q, want legacy env %q", dir, legacyHome)
	}
	// New env wins when both are set.
	newHome := filepath.Join(home, "newcfg")
	t.Setenv("SWIFTSSTP_HOME", newHome)
	dir, err = Dir()
	if err != nil {
		t.Fatal(err)
	}
	if dir != newHome {
		t.Fatalf("Dir() = %q, want %q", dir, newHome)
	}
}

// TestLoadSettingsMigratesFresh pins the first half of the documented
// migration: with nothing to learn from (no profiles.json) the built-in
// defaults are written to settings.json.
func TestLoadSettingsMigratesFresh(t *testing.T) {
	home := freshHome(t)
	s, err := LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if !s.IPv4Enabled || s.IPv6Enabled || s.Tunnels != 1 ||
		!s.AutoReconnect.Enabled || s.AutoReconnect.InitialBackoffMS != 1000 || s.Proxy.Configured() ||
		s.SocksPort != 10808 || s.HTTPPort != 10809 || s.SystemProxy {
		t.Fatalf("fresh settings = %+v, want defaults", s)
	}
	path := filepath.Join(home, ".config", "SwiftSSTP", "settings.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("settings.json not written on first load: %v", err)
	}
	var f settingsFile
	if err := json.Unmarshal(b, &f); err != nil {
		t.Fatalf("settings.json: %v", err)
	}
	if f.Version != 1 || !f.IPv4Enabled || f.SocksPort != 10808 || f.HTTPPort != 10809 {
		t.Fatalf("settings.json = %s", b)
	}
	// Stable on the second load (no re-migration).
	s2, err := LoadSettings()
	if err != nil || s2.Tunnels != s.Tunnels {
		t.Fatalf("second LoadSettings = %+v, %v", s2, err)
	}
}

// TestLoadSettingsMigratesFromProfiles pins the upgrade path: the first
// profile (file order) whose moved fields differ from the defaults seeds
// settings.json; profiles that match the defaults are skipped and later,
// different profiles are not consulted.
func TestLoadSettingsMigratesFromProfiles(t *testing.T) {
	home := freshHome(t)
	dir := filepath.Join(home, ".config", "SwiftSSTP")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "profiles.json")
	// A profiles.json the way an old install left it: every entry still
	// carries the moved keys. The first entry that differs from the
	// defaults seeds the file; the third one is never consulted.
	legacy := `{
  "version": 1,
  "profiles": [
    {"id":"1","name":"a","host":"a","port":443,"username":"u","password":"p","tls_mode":"verify",
     "mtu":0,"ipv4_enabled":true,"ipv6_enabled":false,"dns_override":[],
     "auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
     "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":1,"extras":{}},
    {"id":"2","name":"b","host":"b","port":443,"username":"u","password":"p","tls_mode":"insecure",
     "mtu":1300,"ipv4_enabled":true,"ipv6_enabled":true,"dns_override":["1.1.1.1"],
     "auto_reconnect":{"enabled":false,"initial_backoff_ms":2000,"max_backoff_ms":60000},
     "proxy":{"type":"socks5","host":"127.0.0.1","port":1080,"username":"","password":""},
     "tunnels":3,"extras":{"socks_port":20808,"http_port":20809,"system_proxy":true}},
    {"id":"3","name":"c","host":"c","port":443,"username":"u","password":"p","tls_mode":"verify",
     "mtu":1200,"ipv4_enabled":true,"ipv6_enabled":false,"dns_override":[],
     "auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
     "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":8,"extras":{}}
  ]}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}

	s, err := LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.MTU != 1300 || s.Tunnels != 3 || !s.IPv6Enabled || len(s.DNSOverride) != 1 ||
		s.AutoReconnect.Enabled || s.AutoReconnect.InitialBackoffMS != 2000 ||
		s.Proxy.Type != "socks5" || s.Proxy.Port != 1080 ||
		s.SocksPort != 20808 || s.HTTPPort != 20809 || !s.SystemProxy {
		t.Fatalf("migrated settings = %+v, want profile 2's values", s)
	}
	// The seeded file sticks: a later LoadSettings never re-migrates, even
	// once profiles.json is rewritten without the moved keys.
	converged := `{"version":1,"profiles":[{"id":"9","name":"z","host":"z","port":443,
  "username":"u","password":"p","tls_mode":"verify","extras":{}}]}`
	if err := os.WriteFile(path, []byte(converged), 0o600); err != nil {
		t.Fatal(err)
	}
	again, err := LoadSettings()
	if err != nil || again.MTU != 1300 || again.Tunnels != 3 {
		t.Fatalf("settings re-migrated: %+v, %v", again, err)
	}

	// A converged profiles.json with no settings.json yet has nothing to
	// seed from: the defaults must be written, never the zero values.
	if err := os.Remove(filepath.Join(dir, "settings.json")); err != nil {
		t.Fatal(err)
	}
	s, err = LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if !s.IPv4Enabled || s.IPv6Enabled || s.Tunnels != 1 || !s.AutoReconnect.Enabled ||
		s.SocksPort != 10808 || s.HTTPPort != 10809 || s.SystemProxy {
		t.Fatalf("converged profiles seeded %+v, want defaults", s)
	}
}

// TestLoadSettingsMigratesLegacyProxyExtras pins the extras half of the
// migration: the three local-proxy knobs used to live in each profile's
// extras map, so they are read from there and only when the key is
// actually present. A profile whose only difference is legacy extras
// still seeds settings.json; one that carries none of them leaves the
// defaults untouched.
func TestLoadSettingsMigratesLegacyProxyExtras(t *testing.T) {
	home := freshHome(t)
	dir := filepath.Join(home, ".config", "SwiftSSTP")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "profiles.json")
	// Every top-level moved field matches the defaults: only the legacy
	// extras knobs differ, and they must still flag the profile and get
	// folded in.
	legacy := `{"version":1,"profiles":[
    {"id":"1","name":"a","host":"a","port":443,"username":"u","password":"p","tls_mode":"verify",
     "mtu":0,"ipv4_enabled":true,"ipv6_enabled":false,"dns_override":[],
     "auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
     "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":1,
     "extras":{"socks_port":20808,"http_port":20809,"system_proxy":true}}
  ]}`
	if err := os.WriteFile(path, []byte(legacy), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.SocksPort != 20808 || s.HTTPPort != 20809 || !s.SystemProxy {
		t.Fatalf("legacy extras not folded in: %+v", s)
	}
	if !s.IPv4Enabled || s.Tunnels != 1 || s.MTU != 0 || s.Proxy.Configured() {
		t.Fatalf("legacy extras seeding leaked into other fields: %+v", s)
	}

	// A profiles.json that carries none of the three keys seeds the
	// defaults, never zero values.
	if err := os.Remove(filepath.Join(dir, "settings.json")); err != nil {
		t.Fatal(err)
	}
	clean := `{"version":1,"profiles":[{"id":"2","name":"b","host":"b","port":443,
    "username":"u","password":"p","tls_mode":"verify","extras":{}}]}`
	if err := os.WriteFile(path, []byte(clean), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err = LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.SocksPort != 10808 || s.HTTPPort != 10809 || s.SystemProxy {
		t.Fatalf("extras-free profiles.json seeded %+v, want defaults", s)
	}
}

// TestLoadSettingsFoldsExtrasIntoExistingFile pins the second, narrower
// migration: settings.json existed before the three local-proxy keys did,
// so an install that already has one keeps waiting for them. Folding the
// values in on the next load is what stops the first connect after the
// upgrade from writing the built-in defaults over the ports and the system
// proxy toggle the user had configured.
func TestLoadSettingsFoldsExtrasIntoExistingFile(t *testing.T) {
	home := freshHome(t)
	dir := filepath.Join(home, ".config", "SwiftSSTP")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	// A profiles.json the way the old install left it: the knobs live in
	// each profile's extras.
	legacyProfiles := `{"version":1,"profiles":[
    {"id":"1","name":"a","host":"a","port":443,"username":"u","password":"p","tls_mode":"verify",
     "extras":{"socks_port":20808,"http_port":20809,"system_proxy":true}}
  ]}`
	if err := os.WriteFile(filepath.Join(dir, "profiles.json"), []byte(legacyProfiles), 0o600); err != nil {
		t.Fatal(err)
	}
	// A settings.json from the release that predated those keys: complete
	// for everything else, and carrying values of its own that the fold-in
	// must not disturb.
	oldSettings := `{"version":1,"mtu":1300,"ipv4_enabled":true,"ipv6_enabled":false,
  "dns_override":[],"auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
  "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":2}`
	path := filepath.Join(dir, "settings.json")
	if err := os.WriteFile(path, []byte(oldSettings), 0o600); err != nil {
		t.Fatal(err)
	}

	s, err := LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.SocksPort != 20808 || s.HTTPPort != 20809 || !s.SystemProxy {
		t.Fatalf("legacy extras not folded into the existing settings.json: %+v", s)
	}
	if s.MTU != 1300 || s.Tunnels != 2 || !s.IPv4Enabled {
		t.Fatalf("fold-in disturbed the rest of the file: %+v", s)
	}
	// The pass persists, so the keys are on disk and later loads read them
	// from the file instead of consulting profiles.json again.
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range localProxyKeys {
		if !strings.Contains(string(b), `"`+k+`"`) {
			t.Fatalf("settings.json missing %q after the fold-in: %s", k, b)
		}
	}
	again, err := LoadSettings()
	if err != nil || again.SocksPort != 20808 || again.MTU != 1300 {
		t.Fatalf("second LoadSettings = %+v, %v", again, err)
	}
}

// TestLoadSettingsKeepsExplicitKnobs pins the other half of the pass: a key
// the file already carries is never rewritten from profiles.json, even when
// the legacy extras hold different values, and a partially complete file
// only has its genuinely missing keys filled in.
func TestLoadSettingsKeepsExplicitKnobs(t *testing.T) {
	home := freshHome(t)
	dir := filepath.Join(home, ".config", "SwiftSSTP")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	legacyProfiles := `{"version":1,"profiles":[
    {"id":"1","name":"a","host":"a","port":443,"username":"u","password":"p","tls_mode":"verify",
     "extras":{"socks_port":20808,"http_port":20809,"system_proxy":true}}
  ]}`
	if err := os.WriteFile(filepath.Join(dir, "profiles.json"), []byte(legacyProfiles), 0o600); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, "settings.json")
	partial := `{"version":1,"mtu":0,"ipv4_enabled":true,"ipv6_enabled":false,"dns_override":[],
  "auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
  "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":1,"socks_port":30808}`
	if err := os.WriteFile(path, []byte(partial), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err := LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.SocksPort != 30808 {
		t.Fatalf("explicit socks_port was overwritten: %+v", s)
	}
	if s.HTTPPort != 20809 || !s.SystemProxy {
		t.Fatalf("missing keys were not filled in: %+v", s)
	}

	// A complete file is left alone entirely, whatever profiles.json says.
	complete := `{"version":1,"mtu":0,"ipv4_enabled":true,"ipv6_enabled":false,"dns_override":[],
  "auto_reconnect":{"enabled":true,"initial_backoff_ms":1000,"max_backoff_ms":30000},
  "proxy":{"type":"","host":"","port":0,"username":"","password":""},"tunnels":1,
  "socks_port":30808,"http_port":30809,"system_proxy":false}`
	if err := os.WriteFile(path, []byte(complete), 0o600); err != nil {
		t.Fatal(err)
	}
	s, err = LoadSettings()
	if err != nil {
		t.Fatal(err)
	}
	if s.SocksPort != 30808 || s.HTTPPort != 30809 || s.SystemProxy {
		t.Fatalf("complete settings.json was re-seeded from profiles.json: %+v", s)
	}
}

// TestSaveOmitsMovedSettingsKeys pins the write side of the split:
// profiles.json converges on the documented shape (no app-wide keys),
// while readers keep working on the pruned file.
func TestSaveOmitsMovedSettingsKeys(t *testing.T) {
	freshHome(t)
	p := engine.NewProfile()
	p.ID, p.Name, p.Host, p.Username, p.Password = "id1", "n", "1.2.3.4", "u", "p"
	p.MTU = 1300
	p.Tunnels = 3
	p.AutoReconnect = engine.AutoReconnect{Enabled: false}
	p.Proxy = engine.Proxy{Type: "http", Host: "proxy", Port: 3128}
	p.SetExtraInt("socks_port", 10808)
	if err := Save([]engine.Profile{p}); err != nil {
		t.Fatal(err)
	}
	dir, err := Dir()
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(filepath.Join(dir, "profiles.json"))
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range movedSettingsKeys {
		if strings.Contains(string(b), `"`+k+`"`) {
			t.Fatalf("profiles.json still carries the moved key %q: %s", k, b)
		}
	}
	for _, k := range []string{`"id"`, `"name"`, `"host"`, `"tls_mode"`, `"extras"`, `"version"`} {
		if !strings.Contains(string(b), k) {
			t.Fatalf("profiles.json lost %s: %s", k, b)
		}
	}
	// The pruned file still loads, with the moved fields at their zero
	// values — connect re-applies settings.json before dialling.
	list, err := List()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].Name != "n" || list[0].MTU != 0 || list[0].Tunnels != 0 {
		t.Fatalf("List after prune = %+v", list)
	}
	if list[0].SOCKSPort() != 10808 {
		t.Fatalf("extras lost: %+v", list[0].Extras)
	}
}
