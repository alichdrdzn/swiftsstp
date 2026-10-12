package engine

import (
	"fmt"

	"swiftsstp/multi"
)

// Settings holds the app-wide tunnel options shared by every profile
// (docs/PROFILE_SCHEMA.md settings.json). These fields used to live on
// each profile; they are now one set of values for the whole app and a
// connect applies them on top of the selected profile before dialing.
// The JSON keys are exactly the schema's moved fields and nothing else —
// version lives in the store's file wrapper and app_rules is Android-only.
type Settings struct {
	MTU           int           `json:"mtu"`
	IPv4Enabled   bool          `json:"ipv4_enabled"`
	IPv6Enabled   bool          `json:"ipv6_enabled"`
	DNSOverride   []string      `json:"dns_override"`
	AutoReconnect AutoReconnect `json:"auto_reconnect"`
	Proxy         Proxy         `json:"proxy"`   // upstream proxy, "" type = direct
	Tunnels       int           `json:"tunnels"` // parallel SSTP sessions, 1..8
}

// DefaultSettings returns the schema defaults (same numbers as
// NewProfile, which still seeds the legacy per-profile fields).
func DefaultSettings() Settings {
	return Settings{
		MTU:           0,
		IPv4Enabled:   true,
		IPv6Enabled:   false,
		DNSOverride:   []string{},
		AutoReconnect: AutoReconnect{Enabled: true, InitialBackoffMS: 1000, MaxBackoffMS: 30000},
		Proxy:         Proxy{},
		Tunnels:       1,
	}
}

// SettingsFrom extracts the moved app-wide fields out of a profile — the
// inverse of Apply. It exists so an old profiles.json that still carries
// the values per profile can seed settings.json on first load.
func SettingsFrom(p Profile) Settings {
	return Settings{
		MTU:           p.MTU,
		IPv4Enabled:   p.IPv4Enabled,
		IPv6Enabled:   p.IPv6Enabled,
		DNSOverride:   p.DNSOverride,
		AutoReconnect: p.AutoReconnect,
		Proxy:         p.Proxy,
		Tunnels:       p.Tunnels,
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
	return nil
}

// Apply copies the app-wide settings onto a profile so the engine and
// manager code keep reading a single struct. Profile-level fields (host,
// credentials, certificate handling, extras) are left untouched.
func (s Settings) Apply(p *Profile) {
	p.MTU = s.MTU
	p.IPv4Enabled = s.IPv4Enabled
	p.IPv6Enabled = s.IPv6Enabled
	p.DNSOverride = s.DNSOverride
	p.AutoReconnect = s.AutoReconnect
	p.Proxy = s.Proxy
	p.Tunnels = s.Tunnels
}
