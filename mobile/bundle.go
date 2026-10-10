package mobile

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"sstpflash/multi"
	"sstpflash/tunnel"
)

// Bundle is the gomobile binding for the multi-session engine: N parallel
// SSTP sessions presented as one tunnel, for aggregate throughput and
// per-session failover. API mirrors Client (Connect/ReadIP/WriteIP/
// StatsJSON/Close) plus per-session visibility in StatsJSON.
type Bundle struct {
	host, sni, user, pass string
	port                  int
	tlsMode               int
	caPem                 []byte
	mtu, kaSec, sessions  int

	mu      sync.Mutex
	prot    Protector
	inner   *multi.Bundle
	res     tunnel.Result
	lastErr error
	closed  bool
	created bool

	ring *logRing
}

// NewBundle creates a bundle controller. sessions is clamped to 1..8 by the
// multi package. tlsMode: 0=verify 1=insecure 2=custom_ca.
func NewBundle(host string, port int, sni, user, pass string, tlsMode int, caPem []byte, mtu int, keepaliveSec int, sessions int) *Bundle {
	if tlsMode < 0 || tlsMode > tlsModeLimit {
		tlsMode = TLSVerify
	}
	var ca []byte
	if len(caPem) > 0 {
		ca = append([]byte(nil), caPem...)
	}
	return &Bundle{
		host: host, port: port, sni: sni, user: user, pass: pass,
		tlsMode: tlsMode, caPem: ca, mtu: mtu, kaSec: keepaliveSec,
		sessions: sessions, ring: newLogRing(),
	}
}

// SetProtector installs the fd-protection bridge (all sessions' sockets are
// protected through it). Must be called before Connect on Android.
func (b *Bundle) SetProtector(p Protector) {
	b.mu.Lock()
	b.prot = p
	b.mu.Unlock()
}

// Connect establishes the primary session synchronously (TUN config comes
// from its Result) and fills the remaining sessions in the background.
func (b *Bundle) Connect(timeoutMs int) error {
	b.mu.Lock()
	if b.closed || b.created {
		b.mu.Unlock()
		return fmt.Errorf("mobile: bundle closed or already connected")
	}
	b.lastErr = nil
	b.mu.Unlock()

	ctx := context.Background()
	var cancel context.CancelFunc
	if timeoutMs > 0 {
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
	} else {
		ctx, cancel = context.WithCancel(ctx)
	}

	logger := slog.New(newRingHandler(b.ring))
	dial := func(dctx context.Context) (multi.Session, tunnel.Result, error) {
		return b.dialOne(dctx, logger)
	}

	inner, res, err := multi.Dial(ctx, multi.Config{Dial: dial, Sessions: b.sessions, Logger: logger})
	cancel()
	if err != nil {
		b.mu.Lock()
		b.lastErr = err
		b.mu.Unlock()
		b.ring.append(fmt.Sprintf("ERROR bundle connect failed: %v", err))
		return err
	}
	b.mu.Lock()
	b.inner = inner
	b.res = res
	b.created = true
	b.mu.Unlock()
	b.ring.append(fmt.Sprintf("bundle up: %d session(s), primary %s", b.sessions, res.LocalIP))
	return nil
}

// dialOne establishes one session of the bundle through the protected dial.
func (b *Bundle) dialOne(ctx context.Context, logger *slog.Logger) (multi.Session, tunnel.Result, error) {
	b.mu.Lock()
	p := b.prot
	b.mu.Unlock()
	cfg := tunnel.Config{
		Server:   b.host,
		Port:     b.port,
		SNI:      b.sni,
		Username: b.user,
		Password: b.pass,
		TLSMode:  tunnel.TLSMode(b.tlsMode),
		CACerts:  b.caPem,
		MTU:      b.mtu,
		Logger:   logger,
		DialConn: func(network, addr string) (net.Conn, error) {
			return protectDial(p, ctx, network, addr)
		},
	}
	if b.kaSec != 0 {
		cfg.KeepAlive = time.Duration(b.kaSec) * time.Second
	}
	return tunnel.Dial(ctx, cfg)
}

// ReadIP blocks until the next inner IP packet, nil when the bundle is down.
func (b *Bundle) ReadIP() []byte {
	b.mu.Lock()
	inner := b.inner
	b.mu.Unlock()
	if inner == nil {
		return nil
	}
	buf := make([]byte, 65535)
	n, err := inner.ReadIP(buf)
	if err != nil || n == 0 {
		return nil
	}
	return buf[:n]
}

// WriteIP sends one inner packet into the bundle (flow-affinity routed,
// source-NAT'd to the owning session).
func (b *Bundle) WriteIP(pkt []byte) error {
	b.mu.Lock()
	inner := b.inner
	b.mu.Unlock()
	if inner == nil {
		return fmt.Errorf("mobile: bundle not connected")
	}
	return inner.WriteIP(pkt)
}

// StatsJSON: primary TUN config plus aggregate and per-session counters.
func (b *Bundle) StatsJSON() string {
	b.mu.Lock()
	inner := b.inner
	res := b.res
	b.mu.Unlock()
	if inner == nil {
		return `{"up":0,"down":0,"local_ip":"","peer_ip":"","dns":[],"mtu":0,"cert_sha256":"","sessions":[]}`
	}
	var per struct {
		Up       uint64 `json:"up"`
		Down     uint64 `json:"down"`
		Sessions []any  `json:"sessions"`
	}
	_ = json.Unmarshal([]byte(inner.StatsJSON()), &per)
	out := map[string]any{
		"up":              per.Up,
		"down":            per.Down,
		"sessions":        per.Sessions,
		"local_ip":        ipString(res.LocalIP),
		"peer_ip":         ipString(res.PeerIP),
		"dns":             dnsStrings(res.DNS),
		"mtu":             res.MTU,
		"cert_sha256":     res.ServerCertSHA256,
		"local_ip6":       ipString(res.LocalIP6),
		"peer_ip6":        ipString(res.PeerIP6),
		"ipv6_negotiated": res.IPv6Negotiated,
	}
	enc, _ := json.Marshal(out)
	return string(enc)
}

// dnsStrings renders a resolver list for StatsJSON.
func dnsStrings(ips []net.IP) []string {
	out := []string{}
	for _, d := range ips {
		if v := ipString(d); v != "" {
			out = append(out, v)
		}
	}
	return out
}

// IsAuthError reports whether the last Connect failed on credentials.
func (b *Bundle) IsAuthError() bool {
	b.mu.Lock()
	err := b.lastErr
	b.mu.Unlock()
	return err != nil && tunnel.IsAuthError(err)
}

// LastError returns the last connect/close error as text ("" if none).
func (b *Bundle) LastError() string {
	b.mu.Lock()
	err := b.lastErr
	b.mu.Unlock()
	if err == nil {
		return ""
	}
	return err.Error()
}

// LogTail returns the engine log ring (Kotlin polls it for the UI).
func (b *Bundle) LogTail() string { return b.ring.String() }

// Close tears down every session.
func (b *Bundle) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closed = true
	if b.inner != nil {
		_ = b.inner.Close()
	}
	return nil
}
