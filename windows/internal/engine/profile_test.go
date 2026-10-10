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

	// Out-of-range / nonsense values are rejected.
	q.Tunnels = 9
	if err := q.Validate(); err == nil {
		t.Fatal("tunnels=9 should be rejected")
	}
	q.Tunnels = 3
	q.Proxy.Type = "ftp"
	if err := q.Validate(); err == nil {
		t.Fatal("proxy type ftp should be rejected")
	}
	q.Proxy.Type = "http"
	q.Proxy.Host = ""
	if err := q.Validate(); err == nil {
		t.Fatal("proxy without host should be rejected")
	}
	q.Proxy.Host, q.Proxy.Port = "127.0.0.1", 0
	if err := q.Validate(); err == nil {
		t.Fatal("proxy without port should be rejected")
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
