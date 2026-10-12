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
		!s.AutoReconnect.Enabled || s.AutoReconnect.InitialBackoffMS != 1000 || s.Proxy.Configured() {
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
	if f.Version != 1 || !f.IPv4Enabled {
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
     "tunnels":3,"extras":{}},
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
		s.Proxy.Type != "socks5" || s.Proxy.Port != 1080 {
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
	if !s.IPv4Enabled || s.IPv6Enabled || s.Tunnels != 1 || !s.AutoReconnect.Enabled {
		t.Fatalf("converged profiles seeded %+v, want defaults", s)
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
