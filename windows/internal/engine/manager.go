package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"sstpflash/tunnel"
	"sstpflash/usage"
	"sstpflash/windows/internal/dataplane"
)

// Status is the full connection snapshot pushed to the UI (as a Wails
// event) and returned by Status queries.
type Status struct {
	State            string   `json:"state"` // disconnected|connecting|reconnecting|connected|auth_error|error
	Mode             string   `json:"mode,omitempty"`
	ProfileID        string   `json:"profile_id,omitempty"`
	ProfileName      string   `json:"profile_name,omitempty"`
	LocalIP          string   `json:"local_ip,omitempty"`
	PeerIP           string   `json:"peer_ip,omitempty"`
	DNS              []string `json:"dns,omitempty"`
	MTU              int      `json:"mtu,omitempty"`
	CertFingerprint  string   `json:"cert_fingerprint,omitempty"`
	Up               uint64   `json:"up"`
	Down             uint64   `json:"down"`
	RateUp           uint64   `json:"rate_up"`
	RateDown         uint64   `json:"rate_down"`
	TotalUp          uint64   `json:"total_up"`
	TotalDown        uint64   `json:"total_down"`
	Sessions         int      `json:"sessions"`
	ReconnectAttempts int     `json:"reconnect_attempts"`
	LastError        string   `json:"last_error,omitempty"`
	Since            string   `json:"since,omitempty"` // RFC3339, when the tunnel came up
	SocksPort        int      `json:"socks_port,omitempty"`
	HTTPPort         int      `json:"http_port,omitempty"`
	SystemProxyOn    bool     `json:"system_proxy_on"`
}

// Options wires the manager to its data planes and to the host application.
type Options struct {
	// Proxy is the netstack SOCKS/HTTP data plane (proxy mode).
	Proxy dataplane.DataPlane
	// Tunnel is the whole-device data plane (Wintun); nil where the
	// platform does not provide one (e.g. the Linux proxy test build).
	Tunnel dataplane.DataPlane
	// Usage accumulates lifetime per-server totals (sstpflash/usage,
	// write-through to usage-stats.json).
	Usage *usage.Store
	// OnStatus is called (from manager goroutines) on every transition and
	// on each 1s stats tick.
	OnStatus func(Status)
	// Logf receives human-readable log lines for the UI log tail.
	Logf func(format string, args ...any)
	// SetSystemProxy enables/disables the OS proxy (proxy mode + profile
	// extras.system_proxy). server is a ProxyServer value for Windows.
	SetSystemProxy func(enabled bool, server string) error
}

// Manager owns one tunnel session at a time plus the reconnect loop.
type Manager struct {
	opts Options

	mu       sync.Mutex
	running  bool
	stopping bool
	stopCh   chan struct{}
	waitCh   chan struct{}
	profile  Profile
	mode     Mode
	status   Status
	sysProxy bool
}

// NewManager builds a manager; opts.Usage may be nil (stats are session-only).
func NewManager(opts Options) *Manager {
	if opts.Logf == nil {
		opts.Logf = func(string, ...any) {}
	}
	return &Manager{opts: opts, status: Status{State: "disconnected"}}
}

// Status returns a copy of the current snapshot.
func (m *Manager) Status() Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.status
}

// Running reports whether a connect loop is active (any state but
// disconnected/terminal errors are still "running" until Disconnect).
func (m *Manager) Running() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.running
}

// Connect validates the profile and starts the connect loop in the
// background. It returns immediately; watch Status for progress.
func (m *Manager) Connect(p Profile, mode Mode) error {
	if err := p.Validate(); err != nil {
		return err
	}
	if !ValidMode(mode) {
		return fmt.Errorf("unknown mode %q", mode)
	}
	tunnelCfg, err := p.tunnelConfig(m.opts.Logf)
	if err != nil {
		return err
	}

	m.mu.Lock()
	if m.running {
		m.mu.Unlock()
		return errors.New("engine: already connecting or connected")
	}
	if mode == ModeTunnel && m.opts.Tunnel == nil {
		m.mu.Unlock()
		return errors.New("engine: whole-device mode is not supported on this platform")
	}
	if mode == ModeProxy && m.opts.Proxy == nil {
		m.mu.Unlock()
		return errors.New("engine: proxy mode is not available")
	}
	m.running, m.stopping = true, false
	m.stopCh = make(chan struct{})
	m.waitCh = make(chan struct{})
	m.profile, m.mode = p, mode
	m.status = Status{
		State:       "connecting",
		Mode:        string(mode),
		ProfileID:   p.ID,
		ProfileName: p.Name,
		SocksPort:   p.SOCKSPort(),
		HTTPPort:    p.HTTPPort(),
	}
	m.mu.Unlock()
	m.emit()

	stopCh, waitCh := m.stopCh, m.waitCh
	go m.run(tunnelCfg, p, mode, stopCh, waitCh)
	return nil
}

// Disconnect stops the loop, closes the session and the data plane, and
// restores the OS proxy. It blocks until teardown completes.
func (m *Manager) Disconnect() {
	m.mu.Lock()
	if !m.running {
		m.mu.Unlock()
		return
	}
	if m.stopping { // a previous Disconnect is already tearing down
		waitCh := m.waitCh
		m.mu.Unlock()
		<-waitCh
		return
	}
	m.stopping = true
	stopCh, waitCh := m.stopCh, m.waitCh
	close(stopCh)
	m.mu.Unlock()
	<-waitCh
}

// Close tears everything down (app shutdown).
func (m *Manager) Close() {
	m.Disconnect()
}

// run is the single connect loop goroutine.
func (m *Manager) run(cfg tunnel.Config, p Profile, mode Mode, stopCh, waitCh chan struct{}) {
	defer close(waitCh)

	var (
		backoff = p.AutoReconnect.initial()
		maxBO   = p.AutoReconnect.max()
		attempt int
		first   = true
	)

	for {
		state := "reconnecting"
		if first {
			state = "connecting"
			first = false
		}
		m.update(func(s *Status) {
			s.State = state
			s.LastError = ""
			s.ReconnectAttempts = attempt
			s.RateUp, s.RateDown = 0, 0
		})
		m.opts.Logf("engine: dialing %s:%d (mode=%s attempt=%d)", p.Host, effectivePort(p.Port), mode, attempt)

		sess, res, err := m.dial(cfg, stopCh)
		if err != nil {
			if tunnel.IsAuthError(err) {
				m.opts.Logf("engine: authentication rejected: %v", err)
				m.finish("auth_error", err.Error(), stopCh)
				return
			}
			select {
			case <-stopCh:
				m.finish("disconnected", "", stopCh)
				return
			default:
			}
			m.opts.Logf("engine: dial failed: %v", err)
			m.update(func(s *Status) {
				s.State = "error"
				s.LastError = err.Error()
			})
			if !p.AutoReconnect.Enabled {
				m.finish("error", err.Error(), stopCh)
				return
			}
			attempt++
			if !m.sleepBackoff(backoff, stopCh) {
				m.finish("disconnected", "", stopCh)
				return
			}
			backoff = nextBackoff(backoff, maxBO)
			continue
		}

		// Tunnel is up.
		backoff = p.AutoReconnect.initial()
		attempt = 0
		dp := m.dataPlane(mode)
		dpCfg := buildDataPlaneConfig(p, res)
		if err := dp.Start(sess, res, dpCfg); err != nil {
			m.opts.Logf("engine: data plane start failed: %v", err)
			_ = sess.Close()
			m.update(func(s *Status) {
				s.State = "error"
				s.LastError = err.Error()
			})
			if !p.AutoReconnect.Enabled {
				m.finish("error", err.Error(), stopCh)
				return
			}
			attempt++
			if !m.sleepBackoff(backoff, stopCh) {
				m.finish("disconnected", "", stopCh)
				return
			}
			backoff = nextBackoff(backoff, maxBO)
			continue
		}

		now := time.Now()
		if m.opts.Usage != nil && p.ID != "" {
			if err := m.opts.Usage.AddSession(p.ID); err != nil {
				m.opts.Logf("engine: usage session: %v", err)
			}
		}
		m.maybeEnableSystemProxy(p, mode)
		m.update(func(s *Status) {
			s.State = "connected"
			s.LastError = ""
			s.ReconnectAttempts = 0
			s.LocalIP = res.LocalIP.String()
			s.PeerIP = res.PeerIP.String()
			s.DNS = ipStrings(res.DNS)
			s.MTU = res.MTU
			s.CertFingerprint = res.ServerCertSHA256
			s.Up, s.Down = 0, 0
			s.Since = now.UTC().Format(time.RFC3339)
		})
		m.opts.Logf("engine: up local=%s peer=%s dns=%v mtu=%d cert=%s",
			res.LocalIP, res.PeerIP, res.DNS, res.MTU, shortFingerprint(res.ServerCertSHA256))

		sessionErr := m.pumpStats(sess, p, mode, stopCh)

		// Session over: detach the data plane (usage already written
		// through by addUsage).
		dp.Stop()
		m.maybeDisableSystemProxy(p, mode)

		select {
		case <-stopCh:
			m.finish("disconnected", "", stopCh)
			return
		default:
		}
		if sessionErr != nil && tunnel.IsAuthError(sessionErr) {
			m.opts.Logf("engine: authentication rejected after connect: %v", sessionErr)
			m.finish("auth_error", sessionErr.Error(), stopCh)
			return
		}
		reason := "tunnel closed"
		if sessionErr != nil {
			reason = sessionErr.Error()
		}
		m.opts.Logf("engine: session ended: %s", reason)
		m.update(func(s *Status) {
			s.State = "error"
			s.LastError = reason
			s.RateUp, s.RateDown = 0, 0
		})
		if !p.AutoReconnect.Enabled {
			m.finish("error", reason, stopCh)
			return
		}
		attempt++
		m.update(func(s *Status) { s.ReconnectAttempts = attempt })
		if !m.sleepBackoff(backoff, stopCh) {
			m.finish("disconnected", "", stopCh)
			return
		}
		backoff = nextBackoff(backoff, maxBO)
	}
}

// dial performs one tunnel.Dial, cancellable by stopCh.
func (m *Manager) dial(cfg tunnel.Config, stopCh chan struct{}) (*tunnel.Session, tunnel.Result, error) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	defer close(done)
	go func() {
		select {
		case <-stopCh:
			cancel()
		case <-done:
		}
	}()
	return tunnel.Dial(ctx, cfg)
}

// pumpStats samples counters once per second until the session or the
// manager stops. Returns the session's close cause.
func (m *Manager) pumpStats(sess *tunnel.Session, p Profile, mode Mode, stopCh chan struct{}) error {
	sessDone := make(chan error, 1)
	go func() { sessDone <- sess.Wait() }()

	tick := time.NewTicker(time.Second)
	defer tick.Stop()

	var (
		lastUp, lastDown uint64
		pendingUp        uint64
		pendingDown      uint64
		ticks            int
	)
	for {
		select {
		case <-stopCh:
			_ = sess.Close()
			err := <-sessDone
			m.addUsage(p, pendingUp, pendingDown)
			return err
		case err := <-sessDone:
			m.addUsage(p, pendingUp, pendingDown)
			return err
		case <-tick.C:
			up, down := sess.Counters()
			du, dd := up-lastUp, down-lastDown
			lastUp, lastDown = up, down
			pendingUp += du
			pendingDown += dd
			ticks++
			if m.opts.Usage != nil && ticks%15 == 0 {
				m.addUsage(p, pendingUp, pendingDown)
				pendingUp, pendingDown = 0, 0
			}
			var totalUp, totalDown uint64
			var sessions int
			if m.opts.Usage != nil && p.ID != "" {
				snap := m.opts.Usage.Get(p.ID)
				totalUp, totalDown = snap.TotalUp, snap.TotalDown
				sessions = int(snap.Sessions)
			}
			m.update(func(s *Status) {
				s.Up, s.Down = up, down
				s.RateUp, s.RateDown = du, dd
				s.TotalUp = totalUp + pendingUp
				s.TotalDown = totalDown + pendingDown
				s.Sessions = sessions
			})
		}
	}
}

// addUsage writes accumulated byte deltas through to usage-stats.json.
func (m *Manager) addUsage(p Profile, up, down uint64) {
	if m.opts.Usage == nil || p.ID == "" || (up == 0 && down == 0) {
		return
	}
	if err := m.opts.Usage.AddBytes(p.ID, up, down); err != nil {
		m.opts.Logf("engine: usage bytes: %v", err)
	}
}

// finish performs terminal teardown for this connect loop.
func (m *Manager) finish(state, lastErr string, stopCh chan struct{}) {
	if dp := m.dataPlane(m.modeLocked()); dp != nil {
		_ = dp.Close()
	}
	m.disableSystemProxy()
	m.update(func(s *Status) {
		s.State = state
		s.LastError = lastErr
		s.RateUp, s.RateDown = 0, 0
		if state == "disconnected" {
			s.LocalIP, s.PeerIP, s.CertFingerprint, s.Since = "", "", "", ""
			s.ReconnectAttempts = 0
		}
	})
	m.mu.Lock()
	m.running, m.stopping = false, false
	m.mu.Unlock()
	m.opts.Logf("engine: stopped (%s)", state)
}

func (m *Manager) dataPlane(mode Mode) dataplane.DataPlane {
	if mode == ModeTunnel {
		return m.opts.Tunnel
	}
	return m.opts.Proxy
}

func (m *Manager) modeLocked() Mode {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.mode
}

// sleepBackoff waits for d; returns false if the manager was stopped.
func (m *Manager) sleepBackoff(d time.Duration, stopCh chan struct{}) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-stopCh:
		return false
	}
}

func nextBackoff(cur, max time.Duration) time.Duration {
	cur *= 2
	if cur > max {
		cur = max
	}
	return cur
}

func (m *Manager) maybeEnableSystemProxy(p Profile, mode Mode) {
	if mode != ModeProxy || !p.SystemProxy() || m.opts.SetSystemProxy == nil {
		return
	}
	server := fmt.Sprintf("http=127.0.0.1:%d;https=127.0.0.1:%d;socks=127.0.0.1:%d",
		p.HTTPPort(), p.HTTPPort(), p.SOCKSPort())
	if err := m.opts.SetSystemProxy(true, server); err != nil {
		m.opts.Logf("engine: enable system proxy: %v", err)
		return
	}
	m.mu.Lock()
	m.sysProxy = true
	m.mu.Unlock()
	m.update(func(s *Status) { s.SystemProxyOn = true })
	m.opts.Logf("engine: system proxy set to %s", server)
}

func (m *Manager) maybeDisableSystemProxy(p Profile, mode Mode) {
	if mode == ModeProxy && p.SystemProxy() {
		m.disableSystemProxy()
	}
}

func (m *Manager) disableSystemProxy() {
	m.mu.Lock()
	on := m.sysProxy
	m.sysProxy = false
	m.mu.Unlock()
	if !on || m.opts.SetSystemProxy == nil {
		return
	}
	if err := m.opts.SetSystemProxy(false, ""); err != nil {
		m.opts.Logf("engine: restore system proxy: %v", err)
		return
	}
	m.update(func(s *Status) { s.SystemProxyOn = false })
	m.opts.Logf("engine: system proxy restored")
}

// update mutates the status under the lock and emits the result.
func (m *Manager) update(fn func(*Status)) {
	m.mu.Lock()
	fn(&m.status)
	snap := m.status
	m.mu.Unlock()
	if m.opts.OnStatus != nil {
		m.opts.OnStatus(snap)
	}
}

func (m *Manager) emit() {
	if m.opts.OnStatus != nil {
		m.opts.OnStatus(m.Status())
	}
}

func buildDataPlaneConfig(p Profile, res tunnel.Result) dataplane.Config {
	dns := effectiveDNS(p, res)
	return dataplane.Config{
		ServerHost: p.Host,
		MTU:        res.MTU,
		DNS:        dns,
		IPv4:       p.IPv4Enabled,
		IPv6:       p.IPv6Enabled,
		SOCKSPort:  p.SOCKSPort(),
		HTTPPort:   p.HTTPPort(),
	}
}

// effectiveDNS prefers the profile override, else the server-pushed list.
func effectiveDNS(p Profile, res tunnel.Result) []net.IP {
	if len(p.DNSOverride) > 0 {
		var out []net.IP
		for _, s := range p.DNSOverride {
			if ip := net.ParseIP(s); ip != nil {
				out = append(out, ip)
			}
		}
		if len(out) > 0 {
			return out
		}
	}
	return res.DNS
}

func ipStrings(ips []net.IP) []string {
	out := make([]string, 0, len(ips))
	for _, ip := range ips {
		out = append(out, ip.String())
	}
	return out
}

func effectivePort(port int) int {
	if port == 0 {
		return 443
	}
	return port
}

func shortFingerprint(fp string) string {
	if len(fp) > 16 {
		return fp[:16]
	}
	return fp
}
