// Package engine owns the tunnel session lifecycle: Connect/Disconnect,
// auto-reconnect with exponential backoff, live stats, and routing packets
// to the mode-specific data plane (gVisor netstack proxy or Wintun). It is
// OS-neutral so the proxy path can be live-tested on Linux.
package engine

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"time"

	"sstpflash/tunnel"
)

// Mode selects the data plane.
type Mode string

const (
	ModeTunnel Mode = "tunnel" // whole-device (Wintun on Windows)
	ModeProxy  Mode = "proxy"  // local SOCKS5/HTTP inbounds (netstack)
)

// Valid reports whether the mode string is one we support.
func ValidMode(m Mode) bool { return m == ModeTunnel || m == ModeProxy }

// AutoReconnect mirrors docs/PROFILE_SCHEMA.md auto_reconnect.
type AutoReconnect struct {
	Enabled          bool `json:"enabled"`
	InitialBackoffMS int  `json:"initial_backoff_ms"`
	MaxBackoffMS     int  `json:"max_backoff_ms"`
}

func (a AutoReconnect) initial() time.Duration {
	if a.InitialBackoffMS <= 0 {
		return time.Second
	}
	return time.Duration(a.InitialBackoffMS) * time.Millisecond
}

func (a AutoReconnect) max() time.Duration {
	m := a.MaxBackoffMS
	if m <= 0 {
		m = 30000
	}
	if m < a.InitialBackoffMS {
		m = a.InitialBackoffMS
	}
	return time.Duration(m) * time.Millisecond
}

// Profile is one saved server profile (docs/PROFILE_SCHEMA.md v1 plus
// app-specific extras under Extras).
type Profile struct {
	ID           string         `json:"id"`
	Name         string         `json:"name"`
	Host         string         `json:"host"`
	Port         int            `json:"port"`
	SNI          string         `json:"sni"`
	Username     string         `json:"username"`
	Password     string         `json:"password"`
	TLSMode      string         `json:"tls_mode"` // verify | insecure | custom_ca
	CAPath       string         `json:"ca_path"`
	MTU          int            `json:"mtu"`
	IPv4Enabled  bool           `json:"ipv4_enabled"`
	IPv6Enabled  bool           `json:"ipv6_enabled"`
	DNSOverride  []string       `json:"dns_override"`
	AutoReconnect AutoReconnect `json:"auto_reconnect"`
	Extras       map[string]any `json:"extras"`
}

// ProfilesFile is the on-disk wrapper for profiles.json.
type ProfilesFile struct {
	Version  int       `json:"version"`
	Profiles []Profile `json:"profiles"`
}

// NewProfile returns a profile with schema defaults.
func NewProfile() Profile {
	return Profile{
		Port:         443,
		TLSMode:      "verify",
		MTU:          0,
		IPv4Enabled:  true,
		IPv6Enabled:  false,
		DNSOverride:  []string{},
		AutoReconnect: AutoReconnect{Enabled: true, InitialBackoffMS: 1000, MaxBackoffMS: 30000},
		Extras:       map[string]any{},
	}
}

// SOCKSPort is the profile's SOCKS5 inbound port (extras.socks_port).
func (p Profile) SOCKSPort() int { return p.extraInt("socks_port", 10808) }

// HTTPPort is the profile's HTTP inbound port (extras.http_port).
func (p Profile) HTTPPort() int { return p.extraInt("http_port", 10809) }

// SystemProxy reports whether the profile wants the OS proxy set while
// connected in proxy mode (extras.system_proxy).
func (p Profile) SystemProxy() bool { return p.extraBool("system_proxy") }

func (p Profile) extraInt(key string, def int) int {
	if p.Extras == nil {
		return def
	}
	switch v := p.Extras[key].(type) {
	case float64:
		if v > 0 {
			return int(v)
		}
	case string:
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			return n
		}
	case json.Number:
		if n, err := v.Int64(); err == nil && n > 0 {
			return int(n)
		}
	}
	return def
}

func (p Profile) extraBool(key string) bool {
	if p.Extras == nil {
		return false
	}
	switch v := p.Extras[key].(type) {
	case bool:
		return v
	case float64:
		return v != 0
	case string:
		b, _ := strconv.ParseBool(v)
		return b
	}
	return false
}

// SetExtraInt / SetExtraBool keep Extras well-formed for persistence.
func (p *Profile) SetExtraInt(key string, v int) {
	if p.Extras == nil {
		p.Extras = map[string]any{}
	}
	p.Extras[key] = v
}

func (p *Profile) SetExtraBool(key string, v bool) {
	if p.Extras == nil {
		p.Extras = map[string]any{}
	}
	p.Extras[key] = v
}

// Validate checks the fields the engine needs before dialing.
func (p Profile) Validate() error {
	if p.Host == "" {
		return fmt.Errorf("profile: host is required")
	}
	if p.Username == "" || p.Password == "" {
		return fmt.Errorf("profile: username and password are required")
	}
	if p.Port < 0 || p.Port > 65535 {
		return fmt.Errorf("profile: invalid port")
	}
	if !p.IPv4Enabled && !p.IPv6Enabled {
		return fmt.Errorf("profile: at least one IP family must be enabled")
	}
	switch p.TLSMode {
	case "", "verify", "insecure", "custom_ca":
	default:
		return fmt.Errorf("profile: unknown tls_mode %q", p.TLSMode)
	}
	if p.TLSMode == "custom_ca" {
		if p.CAPath == "" {
			return fmt.Errorf("profile: tls_mode custom_ca requires ca_path")
		}
		if _, err := os.ReadFile(p.CAPath); err != nil {
			return fmt.Errorf("profile: read ca file: %w", err)
		}
	}
	return nil
}

// tunnelConfig converts the profile to engine dial parameters.
func (p Profile) tunnelConfig(logf func(string, ...any)) (tunnel.Config, error) {
	cfg := tunnel.Config{
		Server:      p.Host,
		Port:        p.Port,
		SNI:         p.SNI,
		Username:    p.Username,
		Password:    p.Password,
		MTU:         p.MTU,
		DialTimeout: 30 * time.Second,
		Logger:      newSlogAdapter(logf),
	}
	switch p.TLSMode {
	case "", "verify":
		cfg.TLSMode = tunnel.TLSVerify
	case "insecure":
		cfg.TLSMode = tunnel.TLSInsecure
	case "custom_ca":
		pem, err := os.ReadFile(p.CAPath)
		if err != nil {
			return cfg, fmt.Errorf("read ca file: %w", err)
		}
		cfg.TLSMode = tunnel.TLSCustomCA
		cfg.CACerts = pem
	}
	return cfg, nil
}
