package engine

import (
	"fmt"

	"swiftsstp/multi"
)

// Settings holds the app-wide tunnel options shared by every profile
// (docs/PROFILE_SCHEMA.md settings.json). These fields used to live on
// each profile; they are now one set of values for the whole app and a
// connect applies them on top of the selected profile before dialing.
// The JSON keys are exactly the schema's moved fields and nothing else:
// version lives in the store's file wrapper and app_rules is Android-only.
type Settings struct {
	MTU           int           `json:"mtu"`
	IPv4Enabled   bool          `json:"ipv4_enabled"`
	IPv6Enabled   bool          `json:"ipv6_enabled"`
	DNSOverride   []string      `json:"dns_override"`
	AutoReconnect AutoReconnect `json:"auto_reconnect"`
	Proxy         Proxy         `json:"proxy"`   // upstream proxy, "" type = direct
	Tunnels       int           `json:"tunnels"` // parallel SSTP sessions, 1..8
	// socks_port / http_port are the Windows local-proxy endpoints: the
	// SOCKS5 and HTTP inbounds the app starts on 127.0.0.1 in proxy
	// mode. system_proxy points the Windows system proxy at them while
	// such a session is up. These three are Windows-only knobs; Android
	// does not write them and tolerates their absence.
	SocksPort   int  `json:"socks_port"`
	HTTPPort    int  `json:"http_port"`
	SystemProxy bool `json:"system_proxy"`
}

// DefaultSettings returns the schema defaults. NewProfile seeds the
// same numbers for the fields it still carries; the local-proxy knobs
// were extras-only before this move, so only this function defaults
// them.
func DefaultSettings() Settings {
	return Settings{
		MTU:           0,
		IPv4Enabled:   true,
		IPv6Enabled:   false,
		DNSOverride:   []string{},
		AutoReconnect: AutoReconnect{Enabled: true, InitialBackoffMS: 1000, MaxBackoffMS: 30000},
		Proxy:         Proxy{},
		Tunnels:       1,
		SocksPort:     10808,
		HTTPPort:      10809,
		SystemProxy:   false,
	}
}

// SettingsFrom extracts the moved app-wide fields out of a profile (the
// inverse of Apply). It exists so an old profiles.json that still carries
// the values per profile can seed settings.json on first load. The three
// local-proxy knobs are read through the profile accessors, which already
// resolve "absent" extras to the built-in defaults; deciding whether the
// legacy file actually carried each key is the store migration's job.
func SettingsFrom(p Profile) Settings {
	return Settings{
		MTU:           p.MTU,
		IPv4Enabled:   p.IPv4Enabled,
		IPv6Enabled:   p.IPv6Enabled,
		DNSOverride:   p.DNSOverride,
		AutoReconnect: p.AutoReconnect,
		Proxy:         p.Proxy,
		Tunnels:       p.Tunnels,
		SocksPort:     p.SOCKSPort(),
		HTTPPort:      p.HTTPPort(),
		SystemProxy:   p.SystemProxy(),
	}
}

// Validate checks the app-wide fields. The rules moved here from
// Profile.Validate when the fields left the profile editor: they are
// validated by settings_save, not by profile save.
func (s Settings) Validate() error {
	if !s.IPv4Enabled && !s.IPv6Enabled {
		return fmt.Errorf("settings: at least one IP family must be enabled")
	}
	// Upstream proxy (optional; absent/empty type = direct).
	if s.Proxy.Configured() {
		switch s.Proxy.Type {
		case "http", "socks5":
		default:
			return fmt.Errorf("settings: unknown proxy type %q (want http or socks5)", s.Proxy.Type)
		}
		if s.Proxy.Host == "" {
			return fmt.Errorf("settings: proxy host is required when a proxy type is set")
		}
		if s.Proxy.Port < 1 || s.Proxy.Port > 65535 {
			return fmt.Errorf("settings: invalid proxy port")
		}
	}
	// Tunnels: 0 means "field absent" in a file written before the
	// setting existed, which TunnelCount treats as the default of 1.
	if s.Tunnels != 0 && (s.Tunnels < 1 || s.Tunnels > multi.MaxSessions) {
		return fmt.Errorf("settings: tunnels must be 1..%d", multi.MaxSessions)
	}
	// Local proxy ports: 0 means "field absent" in an old settings.json
	// and is resolved to the default at Apply time, so only explicitly
	// written ports are range checked.
	if s.SocksPort != 0 && (s.SocksPort < 1 || s.SocksPort > 65535) {
		return fmt.Errorf("settings: invalid socks port")
	}
	if s.HTTPPort != 0 && (s.HTTPPort < 1 || s.HTTPPort > 65535) {
		return fmt.Errorf("settings: invalid http port")
	}
	return nil
}

// Apply copies the app-wide settings onto a profile so the engine and
// manager code keep reading a single struct. Profile-level fields (host,
// credentials, certificate handling) are left untouched.
func (s Settings) Apply(p *Profile) {
	p.MTU = s.MTU
	p.IPv4Enabled = s.IPv4Enabled
	p.IPv6Enabled = s.IPv6Enabled
	p.DNSOverride = s.DNSOverride
	p.AutoReconnect = s.AutoReconnect
	p.Proxy = s.Proxy
	p.Tunnels = s.Tunnels
	// The three local-proxy knobs land in the profile's EXTRAS rather
	// than struct fields because that is where the manager reads them
	// (SOCKSPort / HTTPPort / SystemProxy consult extras), and
	// profiles.json is never rewritten after a connect: extras is the
	// one per-profile slot those accessors will find at dial time. The
	// values are resolved here, so a zero from an old settings.json
	// that predates the key becomes the default before it reaches the
	// data plane.
	p.SetExtraInt("socks_port", orDefault(s.SocksPort, 10808))
	p.SetExtraInt("http_port", orDefault(s.HTTPPort, 10809))
	p.SetExtraBool("system_proxy", s.SystemProxy)
}

// orDefault resolves a possibly absent (zero) port to its default.
func orDefault(v, def int) int {
	if v == 0 {
		return def
	}
	return v
}
