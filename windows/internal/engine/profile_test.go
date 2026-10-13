package engine

import (
	"encoding/json"
	"strings"
	"testing"
)

// TestProfileProxyAndTunnels pins docs/PROFILE_SCHEMA.md's "proxy" and
// "tunnels" fields: legacy files without them keep working, new files round
// trip with the canonical JSON names, and the values reach tunnel.Config.
func TestProfileProxyAndTunnels(t *testing.T) {
	// A profile file written BEFORE the fields existed must still load and
	// behave as "direct, one tunnel".
	old := `{"id":"x","name":"n","host":"1.2.3.4","port":443,"username":"u","password":"p",` +
		`"tls_mode":"insecure","ipv4_enabled":true,"auto_reconnect":{"enabled":true}}`
	var p Profile
	if err := json.Unmarshal([]byte(old), &p); err != nil {
		t.Fatalf("unmarshal old: %v", err)
	}
	if p.TunnelCount() != 1 {
		t.Fatalf("legacy tunnels = %d, want 1", p.TunnelCount())
	}
	if p.Proxy.Configured() {
		t.Fatalf("legacy proxy = %+v, want unconfigured", p.Proxy)
	}
	if err := p.Validate(); err != nil {
		t.Fatalf("legacy validate: %v", err)
	}
	if _, err := p.tunnelConfig(func(string, ...any) {}); err != nil {
		t.Fatalf("legacy tunnelConfig: %v", err)
	}

	// Round trip with both new fields set.
	p.Proxy = Proxy{Type: "socks5", Host: "127.0.0.1", Port: 3128, Username: "proxyuser", Password: "proxypass"}
	p.Tunnels = 3
	b, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	var q Profile
	if err := json.Unmarshal(b, &q); err != nil {
		t.Fatal(err)
	}
	if q.Proxy.Type != "socks5" || q.Proxy.Host != "127.0.0.1" || q.Proxy.Port != 3128 || q.Tunnels != 3 {
		t.Fatalf("round trip: %+v", q)
	}
	for _, s := range []string{`"proxy"`, `"tunnels"`} {
		if !strings.Contains(string(b), s) {
			t.Fatalf("canonical name %s missing from %s", s, b)
		}
	}
	if err := q.Validate(); err != nil {
		t.Fatalf("validate: %v", err)
	}
	cfg, err := q.tunnelConfig(func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Proxy == nil || cfg.Proxy.Type != "socks5" || cfg.Proxy.Port != 3128 || cfg.Proxy.Username != "proxyuser" {
		t.Fatalf("cfg.Proxy = %+v", cfg.Proxy)
	}
	if cfg.Proxy.Password != "proxypass" {
		t.Fatalf("cfg.Proxy.Password = %q", cfg.Proxy.Password)
	}
	if q.TunnelCount() != 3 {
		t.Fatalf("TunnelCount = %d", q.TunnelCount())
	}

	// Direct profile must not set a proxy at all.
	d := NewProfile()
	d.Host, d.Username, d.Password = "h", "u", "p"
	if err := d.Validate(); err != nil {
		t.Fatalf("new profile validate: %v", err)
	}
	dcfg, err := d.tunnelConfig(func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	if dcfg.Proxy != nil {
		t.Fatalf("new profile got Proxy %+v, want nil", dcfg.Proxy)
	}

	// The app-wide checks moved to Settings: a profile carrying nonsense
	// in the moved fields still passes profile validation (connect
	// applies settings.json on top of it first), and the same nonsense
	// is rejected by the settings rules instead.
	q.Tunnels = 9
	if err := q.Validate(); err != nil {
		t.Fatalf("profile validation must not police moved fields: %v", err)
	}
	st := SettingsFrom(q)
	if err := st.Validate(); err == nil {
		t.Fatal("settings tunnels=9 should be rejected")
	}
	st.Tunnels = 3
	st.Proxy.Type = "ftp"
	if err := st.Validate(); err == nil {
		t.Fatal("settings proxy type ftp should be rejected")
	}
	st.Proxy.Type = "http"
	st.Proxy.Host = ""
	if err := st.Validate(); err == nil {
		t.Fatal("settings proxy without host should be rejected")
	}
	st.Proxy.Host, st.Proxy.Port = "127.0.0.1", 0
	if err := st.Validate(); err == nil {
		t.Fatal("settings proxy without port should be rejected")
	}
	st = SettingsFrom(q)
	st.IPv4Enabled, st.IPv6Enabled = false, false
	if err := st.Validate(); err == nil {
		t.Fatal("settings without an IP family should be rejected")
	}
	// Clamping for values that reach the dial path anyway.
	q.Proxy = Proxy{}
	q.Tunnels = 0
	if q.TunnelCount() != 1 {
		t.Fatalf("tunnels=0 -> %d", q.TunnelCount())
	}
	q.Tunnels = 99
	if q.TunnelCount() != 8 {
		t.Fatalf("tunnels=99 -> %d", q.TunnelCount())
	}
}

// TestSettingsApplySplit pins the split behind docs/PROFILE_SCHEMA.md:
// the app-wide values live in settings.json, and a connect overlays them
// on the chosen profile (Settings.Apply) before it is validated and
// dialled. SettingsFrom is the inverse the migration uses.
func TestSettingsApplySplit(t *testing.T) {
	p := NewProfile()
	p.Host, p.Username, p.Password = "h", "u", "p"
	p.TLSMode = "insecure"

	s := DefaultSettings()
	if err := s.Validate(); err != nil {
		t.Fatalf("default settings validate: %v", err)
	}
	s.Apply(&p)
	if p.TunnelCount() != 1 || p.Proxy.Configured() || !p.IPv4Enabled || p.IPv6Enabled {
		t.Fatalf("defaults applied: tunnels=%d proxy=%+v v4=%v v6=%v",
			p.TunnelCount(), p.Proxy, p.IPv4Enabled, p.IPv6Enabled)
	}
	// The local-proxy knobs reach the manager through extras, so the
	// defaults must land there in a shape the accessors read back.
	if p.SOCKSPort() != 10808 || p.HTTPPort() != 10809 || p.SystemProxy() {
		t.Fatalf("defaults applied to extras: %+v", p.Extras)
	}

	// A non-default set must reach the engine through the same path.
	s.MTU = 1350
	s.Tunnels = 4
	s.IPv6Enabled = true
	s.DNSOverride = []string{"1.1.1.1"}
	s.Proxy = Proxy{Type: "http", Host: "proxy.example.com", Port: 3128}
	s.SocksPort = 20808
	s.HTTPPort = 20809
	s.SystemProxy = true
	if err := s.Validate(); err != nil {
		t.Fatalf("settings validate: %v", err)
	}
	s.Apply(&p)
	if p.MTU != 1350 || p.TunnelCount() != 4 || !p.IPv6Enabled || len(p.DNSOverride) != 1 {
		t.Fatalf("applied: %+v", p)
	}
	if p.SOCKSPort() != 20808 || p.HTTPPort() != 20809 || !p.SystemProxy() {
		t.Fatalf("applied extras: %+v", p.Extras)
	}
	if err := p.Validate(); err != nil {
		t.Fatalf("profile validate after apply: %v", err)
	}
	cfg, err := p.tunnelConfig(func(string, ...any) {})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.MTU != 1350 || cfg.Proxy == nil || cfg.Proxy.Type != "http" || cfg.Proxy.Port != 3128 {
		t.Fatalf("cfg = %+v", cfg)
	}

	back := SettingsFrom(p)
	if back.MTU != s.MTU || back.Tunnels != s.Tunnels || back.Proxy != s.Proxy ||
		!back.IPv6Enabled || len(back.DNSOverride) != 1 ||
		back.SocksPort != s.SocksPort || back.HTTPPort != s.HTTPPort || back.SystemProxy != s.SystemProxy {
		t.Fatalf("SettingsFrom/Apply round trip: %+v vs %+v", back, s)
	}

	// Local proxy ports: 0 means "field absent in an old settings.json"
	// and must pass validation; an explicit out-of-range port must not.
	s.SocksPort, s.HTTPPort, s.SystemProxy = 0, 0, false
	if err := s.Validate(); err != nil {
		t.Fatalf("zero local proxy ports mean absent and must validate: %v", err)
	}
	// Applying zero-valued (absent) settings still resolves the ports to
	// the defaults, so the manager never sees 0 at dial time.
	s.Apply(&p)
	if p.SOCKSPort() != 10808 || p.HTTPPort() != 10809 || p.SystemProxy() {
		t.Fatalf("absent ports did not resolve to defaults: %+v", p.Extras)
	}
	s.SocksPort = 70000
	if err := s.Validate(); err == nil {
		t.Fatal("settings socks port 70000 should be rejected")
	}
	s.SocksPort, s.HTTPPort = 10808, -1
	if err := s.Validate(); err == nil {
		t.Fatal("settings http port -1 should be rejected")
	}
}
