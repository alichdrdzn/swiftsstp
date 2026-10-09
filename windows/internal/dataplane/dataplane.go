// Package dataplane defines the interface between the engine (which owns the
// tunnel session and reconnect policy) and the mode-specific packet sinks:
// a gVisor netstack SOCKS/HTTP proxy (proxy mode) or a Wintun adapter
// (whole-device tunnel mode). It is deliberately tiny and OS-neutral so both
// Windows-only and Linux-testable code can share it without import cycles.
package dataplane

import (
	"net"

	"sstpflash/tunnel"
)

// Listener defaults for proxy mode (v2rayN-style local inbounds).
const (
	DefaultSocksPort = 10808
	DefaultHTTPPort  = 10809

	// Profile extras keys (docs/PROFILE_SCHEMA.md allows app-specific
	// extras under "extras").
	ExtraSocksPort   = "socks_port"
	ExtraHTTPPort    = "http_port"
	ExtraSystemProxy = "system_proxy"
)

// Config is the resolved, per-session input a data plane needs: what to
// route toward and how to resolve names. The engine fills it from the
// profile plus the negotiated tunnel.Result.
type Config struct {
	// ServerHost is the SSTP server address; whole-device mode must add a
	// host route for it via the original default gateway BEFORE installing
	// default routes (otherwise the control connection black-holes itself).
	ServerHost string
	// MTU is the negotiated inner MTU.
	MTU int
	// DNS is the effective resolver list: profile DNS override if set,
	// otherwise the server-pushed resolvers.
	DNS []net.IP
	// IPv4/IPv6 are the profile's address-family switches.
	IPv4 bool
	IPv6 bool
	// SOCKSPort/HTTPPort are the proxy-mode inbound listeners (proxy mode
	// only; the data plane rebinds if they change between connects).
	SOCKSPort int
	HTTPPort  int
}

// DataPlane is one packet sink for a live tunnel session. Start is called
// after each successful (re)connect, Stop when that session ends, Close when
// the user disconnects for good. Implementations must tolerate
// Start-after-Close (the user can reconnect after disconnecting).
type DataPlane interface {
	Start(sess *tunnel.Session, res tunnel.Result, cfg Config) error
	Stop()
	Close() error
}
