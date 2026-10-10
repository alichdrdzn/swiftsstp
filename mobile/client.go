// Package mobile is the gomobile binding surface for the Sstpflash SSTP
// engine. Everything exported here is a type gomobile can marshal across
// JNI: strings, ints, int64s, bools, byte slices, error. No net.IP, no
// slog.Logger, no time.Duration crosses the boundary — those live behind
// the wrapper and are flattened into strings and numbers on the way out.
//
// Typical Android use:
//
//	c := mobile.NewClient("vpn.example.com", 443, "", "user", "pass", 1, nil, 0, 30, "", "", 0, "", "")
//	c.SetProtector(protector)   // VpnService.protect(fd) bridge
//	if err := c.Connect(20000); err != nil { ... }
//	for { pkt := c.ReadIP(); if pkt == nil { break }; tunFd.write(pkt) }
package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	"sstpflash/tunnel"
)

// Protector is implemented by the Android side as VpnService.protect(fd):
// it pins a file descriptor to the physical network so the tunnel's own
// TCP socket never routes back into the TUN it is carrying.
type Protector interface {
	// Protect returns true when the fd was successfully excluded from
	// routing (VpnService.protect does this synchronously).
	Protect(fd int) bool
}

// TLS modes accepted by NewClient, matching tunnel.TLSMode.
const (
	TLSVerify    = 0 // chain + hostname verification (default)
	TLSInsecure  = 1 // accept any certificate
	TLSCustomCA  = 2 // verify against caPem only
	tlsModeLimit = 2
)

// Client owns at most one live tunnel session. It is safe for concurrent
// use: the VPN service bridges packets on two threads while the UI reads
// stats and the log tail on the main thread.
type Client struct {
	host    string
	port    int
	sni     string
	user    string
	pass    string
	tlsMode int
	caPem   []byte
	mtu     int
	kaSec   int
	// proxy is the upstream proxy for the OUTER transport (nil = direct);
	// built once in NewClient from the flattened proxy parameters.
	proxy *tunnel.ProxyConfig

	mu       sync.Mutex
	prot     Protector
	sess     *tunnel.Session
	result   tunnel.Result
	lastErr  error
	cancel   context.CancelFunc
	dialCtx  context.Context
	closed   bool // Close was called; the session handle is kept for StatsJSON
	ring     *logRing
	certSHA  string
	connectN int
}

// NewClient builds a client for one profile. port 0 means 443; mtu 0 means
// the engine default (1400); keepaliveSec 0 means the engine default (30s)
// and a negative value disables LCP echo entirely; caPem is only read when
// tlsMode is TLSCustomCA.
//
// proxyType ("", "http", "socks5"), proxyHost, proxyPort, proxyUser and
// proxyPass configure the upstream proxy for the OUTER transport; a blank
// proxyType means the session dials the server directly.
func NewClient(host string, port int, sni, user, pass string, tlsMode int, caPem []byte, mtu int, keepaliveSec int, proxyType string, proxyHost string, proxyPort int, proxyUser string, proxyPass string) *Client {
	if tlsMode < 0 || tlsMode > tlsModeLimit {
		tlsMode = TLSVerify
	}
	var ca []byte
	if len(caPem) > 0 {
		ca = append([]byte(nil), caPem...)
	}
	return &Client{
		host: host, port: port, sni: sni, user: user, pass: pass,
		tlsMode: tlsMode, caPem: ca, mtu: mtu, kaSec: keepaliveSec,
		proxy: newProxy(proxyType, proxyHost, proxyPort, proxyUser, proxyPass),
		ring:  newLogRing(),
	}
}

// SetProtector installs the fd-protection bridge. Must be called before
// Connect on Android; a nil protector simply means no protection (used by
// tests and desktop harnesses).
func (c *Client) SetProtector(p Protector) {
	c.mu.Lock()
	c.prot = p
	c.mu.Unlock()
}

// Connect dials TCP, wraps it in TLS and runs the SSTP/PPP handshake. It
// blocks until the tunnel is up, until timeoutMs elapses (0 = engine
// default of 20s), or until it fails. On failure the error is retained for
// IsAuthError.
func (c *Client) Connect(timeoutMs int) error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return fmt.Errorf("mobile: client closed")
	}
	if c.sess != nil {
		c.mu.Unlock()
		return fmt.Errorf("mobile: already connected")
	}
	c.lastErr = nil
	c.mu.Unlock()

	// One context spans the TCP dial and the TLS handshake inside
	// tunnel.Dial; DialTimeout bounds the rest of establishment.
	ctx := context.Background()
	var cancel context.CancelFunc
	if timeoutMs > 0 {
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	} else {
		ctx, cancel = context.WithCancel(ctx)
	}

	cfg := tunnel.Config{
		Server:   c.host,
		Port:     c.port,
		SNI:      c.sni,
		Username: c.user,
		Password: c.pass,
		TLSMode:  tunnel.TLSMode(c.tlsMode),
		CACerts:  c.caPem,
		MTU:      c.mtu,
		DialConn: c.dialConn,
		Logger:   slog.New(newRingHandler(c.ring)),
	}
	if timeoutMs > 0 {
		cfg.DialTimeout = time.Duration(timeoutMs) * time.Millisecond
	}
	if c.kaSec != 0 {
		cfg.KeepAlive = time.Duration(c.kaSec) * time.Second
	}
	c.mu.Lock()
	c.cancel = cancel
	c.dialCtx = ctx
	c.mu.Unlock()

	sess, res, err := tunnel.Dial(ctx, cfg)
	cancel()
	if err != nil {
		c.mu.Lock()
		c.lastErr = err
		c.cancel = nil
		c.dialCtx = nil
		c.mu.Unlock()
		c.ring.append(fmt.Sprintf("ERROR connect failed: %v", err))
		return err
	}

	c.mu.Lock()
	c.sess = sess
	c.result = res
	c.certSHA = res.ServerCertSHA256
	c.connectN++
	c.dialCtx = nil
	c.mu.Unlock()
	return nil
}

// dialConn is tunnel.Config.DialConn: open the TCP socket, hand its fd to
// the protector before the connection is used, and return the raw conn for
// the engine to wrap with tls.Client (same SNI, same MinVersion).
//
// With an upstream proxy configured the whole hop — protected dial to the
// proxy plus its CONNECT/SOCKS5 handshake — happens here too: tunnel.Dial
// gives DialConn precedence over Config.Proxy, and the proxy socket needs
// the protector just as much as a direct one.
func (c *Client) dialConn(network, addr string) (net.Conn, error) {
	c.mu.Lock()
	p := c.prot
	ctx := c.dialCtx
	px := c.proxy
	c.mu.Unlock()
	if ctx == nil {
		ctx = context.Background()
	}
	if px != nil {
		return proxyDial(p, ctx, px, network, addr)
	}
	return protectDial(p, ctx, network, addr)
}

// protectDial dials while protecting the socket fd via the Protector bridge
// (VpnService.protect), both pre-connect via Dialer.Control and post-connect
// through syscall.Conn. Shared by Client (single session) and Bundle
// (multi-session).
func protectDial(p Protector, ctx context.Context, network, addr string) (net.Conn, error) {
	d := net.Dialer{}
	// Protect as early as the kernel allows: a socket created and marked
	// before connect() never races the VPN route.
	if p != nil {
		d.Control = func(network, address string, raw syscall.RawConn) error {
			var protErr error
			if err := raw.Control(func(fd uintptr) {
				if !p.Protect(int(fd)) {
					protErr = fmt.Errorf("mobile: protect(%d) failed", fd)
				}
			}); err != nil {
				return err
			}
			return protErr
		}
	}
	conn, err := d.DialContext(ctx, network, addr)
	if err != nil {
		return nil, fmt.Errorf("mobile: dial %s: %w", addr, err)
	}
	// The contract with the VPN service: the fd is grabbed through
	// syscall.Conn and protected before this conn is handed to the engine.
	if p != nil {
		if sc, ok := conn.(syscall.Conn); ok {
			raw, err := sc.SyscallConn()
			if err != nil {
				_ = conn.Close()
				return nil, fmt.Errorf("mobile: socket fd: %w", err)
			}
			if err := raw.Control(func(fd uintptr) { p.Protect(int(fd)) }); err != nil {
				_ = conn.Close()
				return nil, fmt.Errorf("mobile: protect fd: %w", err)
			}
		}
	}
	return conn, nil
}

// ReadIP blocks until the next inner IP packet arrives, or until the
// session is closed — in which case it returns nil, which is the signal
// the VPN service uses to tear the TUN down or start a reconnect.
func (c *Client) ReadIP() []byte {
	c.mu.Lock()
	s, closed := c.sess, c.closed
	c.mu.Unlock()
	if s == nil || closed {
		return nil
	}
	buf := make([]byte, 65535)
	n, err := s.ReadIP(buf)
	if err != nil || n <= 0 {
		return nil
	}
	return buf[:n]
}

// WriteIP sends one inner IP packet (as read from the TUN) into the tunnel.
func (c *Client) WriteIP(pkt []byte) error {
	c.mu.Lock()
	s, closed := c.sess, c.closed
	c.mu.Unlock()
	if s == nil || closed {
		return fmt.Errorf("mobile: not connected")
	}
	if len(pkt) == 0 {
		return nil
	}
	return s.WriteIP(pkt)
}

// Connected reports whether a session is live: it is false once Close has
// been called, even though the session handle is retained for StatsJSON.
func (c *Client) Connected() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.sess != nil && !c.closed
}

// StatsJSON is a snapshot of the live session as JSON, for the UI:
//
//	{"up":0,"down":0,"local_ip":"...","peer_ip":"...","dns":["..."],
//	 "mtu":1400,"local_ip6":"","peer_ip6":"","ipv6_negotiated":false,
//	 "cert_sha256":"..."}
func (c *Client) StatsJSON() string {
	c.mu.Lock()
	s := c.sess
	res := c.result
	sha := c.certSHA
	c.mu.Unlock()

	st := statsOut{
		DNS: []string{},
		MTU: res.MTU,
	}
	if s != nil {
		up, down := s.Counters()
		st.Up = up
		st.Down = down
	}
	st.LocalIP = ipString(res.LocalIP)
	st.PeerIP = ipString(res.PeerIP)
	for _, d := range res.DNS {
		if v := ipString(d); v != "" {
			st.DNS = append(st.DNS, v)
		}
	}
	// The engine negotiates IPCP (IPv4) only; these stay empty/false until
	// an IPv6CP negotiation exists. They are reported for schema stability.
	if ip := res.LocalIP; ip != nil && ip.To4() == nil && len(ip) == net.IPv6len {
		st.LocalIP6 = ip.String()
	}
	if ip := res.PeerIP; ip != nil && ip.To4() == nil && len(ip) == net.IPv6len {
		st.PeerIP6 = ip.String()
	}
	st.IPv6Negotiated = st.LocalIP6 != "" && st.PeerIP6 != ""
	st.CertSHA256 = sha

	b, err := json.Marshal(st)
	if err != nil {
		return `{"up":0,"down":0,"local_ip":"","peer_ip":"","dns":[],"mtu":0,"local_ip6":"","peer_ip6":"","ipv6_negotiated":false,"cert_sha256":""}`
	}
	return string(b)
}

type statsOut struct {
	Up             uint64   `json:"up"`
	Down           uint64   `json:"down"`
	LocalIP        string   `json:"local_ip"`
	PeerIP         string   `json:"peer_ip"`
	DNS            []string `json:"dns"`
	MTU            int      `json:"mtu"`
	LocalIP6       string   `json:"local_ip6"`
	PeerIP6        string   `json:"peer_ip6"`
	IPv6Negotiated bool     `json:"ipv6_negotiated"`
	CertSHA256     string   `json:"cert_sha256"`
}

func ipString(ip net.IP) string {
	if ip == nil {
		return ""
	}
	return ip.String()
}

// IsAuthError reports whether the most recent Connect failed on
// credentials, so the UI can prompt for a new password instead of
// retrying with backoff.
func (c *Client) IsAuthError() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return tunnel.IsAuthError(c.lastErr)
}

// LastError is the message of the most recent Connect failure ("" if none),
// flattened for the UI.
func (c *Client) LastError() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.lastErr == nil {
		return ""
	}
	return c.lastErr.Error()
}

// LogTail returns the engine log as newline-separated lines, newest last.
// The UI polls this for the connection screen's scrolling log.
func (c *Client) LogTail() string { return c.ring.String() }

// Close tears down the session (if any) and releases the client. ReadIP
// returns nil immediately afterwards. Safe to call more than once.
func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	s := c.sess // retained: StatsJSON must still report final counters
	cancel := c.cancel
	c.cancel = nil
	c.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if s != nil {
		// The session handle stays reachable so StatsJSON can still report
		// the final counters after close; ReadIP/WriteIP are gated on
		// c.closed above and the session itself is shut down here.
		return s.Close()
	}
	return nil
}

// Log line formatting helpers -------------------------------------------------

type logRing struct {
	mu    sync.Mutex
	lines []string
}

const logRingMax = 400

func newLogRing() *logRing { return &logRing{} }

func (r *logRing) append(s string) {
	r.mu.Lock()
	r.lines = append(r.lines, s)
	if len(r.lines) > logRingMax {
		r.lines = r.lines[len(r.lines)-logRingMax:]
	}
	r.mu.Unlock()
}

func (r *logRing) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}

type ringHandler struct{ r *logRing }

func newRingHandler(r *logRing) slog.Handler { return &ringHandler{r: r} }

func (h *ringHandler) Enabled(_ context.Context, _ slog.Level) bool { return true }

func (h *ringHandler) Handle(_ context.Context, rec slog.Record) error {
	var b strings.Builder
	b.WriteString(rec.Time.Format("15:04:05.000"))
	b.WriteByte(' ')
	b.WriteString(rec.Level.String())
	b.WriteByte(' ')
	b.WriteString(rec.Message)
	rec.Attrs(func(a slog.Attr) bool {
		b.WriteByte(' ')
		b.WriteString(a.Key)
		b.WriteByte('=')
		b.WriteString(a.Value.String())
		return true
	})
	h.r.append(b.String())
	return nil
}

func (h *ringHandler) WithAttrs(_ []slog.Attr) slog.Handler { return h }
func (h *ringHandler) WithGroup(_ string) slog.Handler      { return h }
