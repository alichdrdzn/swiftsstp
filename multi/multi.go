// Package multi bundles several SSTP sessions into one logical tunnel for
// aggregate throughput: flows are pinned to sessions by 5-tuple affinity, so
// parallel connections ride different tunnels simultaneously.
//
// Each session gets its OWN inner IP from the server (they are separate PPP
// links). The bundle presents one identity to the host: the primary (first)
// session's address, added on the TUN/Wintun interface. Outbound packets get
// their source rewritten to the owning session's inner IP (SNAT) and inbound
// packets their destination rewritten back to the primary (DNAT), using
// incremental checksum updates (RFC 1624) so payloads are never touched.
// The server sees each packet arriving on the PPP link that owns its address,
// and replies land back on that link, where DNAT restores the primary address.
//
// Limits, by design: IPv4 flows are striped; IPv6 packets pin to the primary
// session (IPv6CP rarely negotiates server-side, and v6 NAT needs its own
// rewrite path). One TCP flow still uses one session — the speedup is
// aggregate across parallel flows, which is what real workloads (downloads,
// updates, browsing, speedtests) actually do.
package multi

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"log/slog"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"swiftsstp/tunnel"
)

// MaxSessions caps the bundle size (guards config typos).
const MaxSessions = 8

// DefaultReconnectBackoff mirrors reconnect.Manager's defaults.
var (
	DefaultReconnectBackoff = time.Second
	DefaultMaxBackoff       = 30 * time.Second
)

// Session is the slice of tunnel.Session the bundle needs. *tunnel.Session
// satisfies it; tests inject fakes via DialFunc.
type Session interface {
	ReadIP(buf []byte) (int, error)
	WriteIP(pkt []byte) error
	Counters() (up, down uint64)
	Close() error
	Wait() error
}

// DialFunc establishes one session. tunnel.Dial's signature fits via
// adaptation; tests inject fakes.
type DialFunc func(ctx context.Context) (Session, tunnel.Result, error)

// Config configures a bundle.
type Config struct {
	// Dial establishes one session. Required.
	Dial DialFunc
	// Sessions is the number of parallel sessions (1..MaxSessions). The
	// first is established synchronously; the rest fill in the background.
	Sessions int
	// Primary is the result of the first established session, used for the
	// bundle-level addressing. Set by Dial.
	Logger *slog.Logger
}

type slot struct {
	idx     int
	mu      sync.Mutex
	sess    Session
	ready   bool
	localIP net.IP // this session's inner IPv4 (nil until established)
	dead    bool   // permanently failed (auth) or closed
}

// Bundle is the logical tunnel over N sessions.
type Bundle struct {
	cfg     Config
	slots   []*slot
	inbound chan []byte
	done    chan struct{}

	primaryIP net.IP // primary session's inner IPv4

	closeOnce sync.Once
	logger    *slog.Logger

	rr atomic.Uint64 // round-robin over ready slots

	// flow affinity
	affMu sync.Mutex
	aff   map[uint64]affinityEntry
}

type affinityEntry struct {
	slot    *slot
	lastUse time.Time
}

const inboundQueue = 1024

// ErrClosed reports use of a closed bundle.
var ErrClosed = errors.New("multi: bundle closed")

// Dial establishes the bundle: the first session synchronously (its Result
// is returned as the bundle's), the remaining Sessions-1 in the background
// with per-slot reconnect backoff.
func Dial(ctx context.Context, cfg Config) (*Bundle, tunnel.Result, error) {
	var zero tunnel.Result
	if cfg.Dial == nil {
		return nil, zero, errors.New("multi: Dial func is required")
	}
	if cfg.Sessions < 1 {
		cfg.Sessions = 1
	}
	if cfg.Sessions > MaxSessions {
		cfg.Sessions = MaxSessions
	}
	logger := cfg.Logger
	if logger == nil {
		logger = slog.Default()
	}

	b := &Bundle{
		cfg:     cfg,
		inbound: make(chan []byte, inboundQueue),
		done:    make(chan struct{}),
		aff:     make(map[uint64]affinityEntry),
		logger:  logger,
	}
	for i := 0; i < cfg.Sessions; i++ {
		b.slots = append(b.slots, &slot{idx: i})
	}

	// First session: synchronous, establishes identity.
	sess, res, err := cfg.Dial(ctx)
	if err != nil {
		return nil, zero, fmt.Errorf("multi: primary session: %w", err)
	}
	b.primaryIP = res.LocalIP.To4()
	b.slots[0].sess = sess
	b.slots[0].ready = true
	b.slots[0].localIP = b.primaryIP
	go b.slotLoop(b.slots[0], false /*no initial retry: it's up*/)

	// Remaining sessions fill in the background.
	for i := 1; i < cfg.Sessions; i++ {
		go b.slotLoop(b.slots[i], true)
	}

	go b.affinitySweep()
	b.logger.Info("multi: bundle up", "sessions", cfg.Sessions, "primary", b.primaryIP)
	return b, res, nil
}

// slotLoop pumps one slot for its lifetime, redialing with backoff after
// failures (permanent stop on auth error or bundle close).
func (b *Bundle) slotLoop(s *slot, retry bool) {
	backoff := DefaultReconnectBackoff
	for {
		select {
		case <-b.done:
			return
		default:
		}
		if s.sess == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
			sess, res, err := b.cfg.Dial(ctx)
			cancel()
			if err != nil {
				if tunnel.IsAuthError(err) {
					b.logger.Error("multi: slot auth failed permanently", "slot", s.idx, "err", err)
					s.mu.Lock()
					s.dead = true
					s.mu.Unlock()
					return
				}
				b.logger.Warn("multi: slot dial failed, will retry", "slot", s.idx, "err", err, "backoff", backoff)
				select {
				case <-b.done:
					return
				case <-time.After(backoff):
				}
				backoff *= 2
				if backoff > DefaultMaxBackoff {
					backoff = DefaultMaxBackoff
				}
				continue
			}
			s.mu.Lock()
			s.sess = sess
			s.localIP = res.LocalIP.To4()
			s.ready = true
			s.mu.Unlock()
			b.logger.Info("multi: slot up", "slot", s.idx, "ip", s.localIP)
		}

		backoff = DefaultReconnectBackoff // successful establishment resets
		b.pumpSlot(s)

		// Session ended: mark not-ready, drop its affinity entries, close,
		// and (unless the bundle is done) loop to redial.
		s.mu.Lock()
		s.ready = false
		sess := s.sess
		if sess != nil {
			_ = sess.Close()
		}
		s.mu.Unlock()
		b.dropAffinity(s)
		if b.stopped() {
			return
		}
		if !retry && s.idx == 0 && sess != nil {
			// The primary session does not redial silently: surface via Wait.
			// Remaining slots keep the bundle usable meanwhile.
			b.logger.Error("multi: primary session ended", "err", sess.Wait())
		}
		s.mu.Lock()
		s.sess = nil
		s.mu.Unlock()
	}
}

// pumpSlot drains one session's inbound packets into the bundle queue,
// rewriting destination back to the primary address when needed.
func (b *Bundle) pumpSlot(s *slot) {
	buf := make([]byte, 65535)
	for {
		s.mu.Lock()
		sess := s.sess
		local := s.localIP
		s.mu.Unlock()
		if sess == nil {
			return
		}
		n, err := sess.ReadIP(buf)
		if err != nil {
			return
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		if local != nil && b.primaryIP != nil && !local.Equal(b.primaryIP) {
			if version := pkt[0] >> 4; version == 4 {
				inboundDNAT(pkt, local, b.primaryIP)
			}
		}
		select {
		case b.inbound <- pkt:
		case <-b.done:
			return
		default:
			// consumer stalled; drop like tunnel.Session does
		}
	}
}

// WriteIP routes one inner packet into the bundle: the flow's pinned (or a
// freshly chosen) session, with source NAT to that session's inner address.
func (b *Bundle) WriteIP(pkt []byte) error {
	if len(pkt) < 20 {
		return errors.New("multi: short packet")
	}
	if b.stopped() {
		return ErrClosed
	}
	version := pkt[0] >> 4
	if version != 4 {
		// IPv6 (and anything else) rides the primary session untouched.
		if s := b.primary(); s != nil {
			s.mu.Lock()
			sess := s.sess
			s.mu.Unlock()
			if sess != nil {
				return sess.WriteIP(pkt)
			}
		}
		return ErrClosed
	}

	s := b.pick(pkt)
	if s == nil {
		return ErrClosed
	}
	s.mu.Lock()
	out := pkt
	if s.localIP != nil && b.primaryIP != nil && !s.localIP.Equal(b.primaryIP) {
		out = make([]byte, len(pkt))
		copy(out, pkt)
		outboundSNAT(out, b.primaryIP, s.localIP)
	}
	sess := s.sess
	s.mu.Unlock()
	if sess == nil {
		return ErrClosed
	}
	return sess.WriteIP(out)
}

// pick returns the session for this packet: the flow's affinity slot when
// still ready, otherwise a ready slot chosen round-robin and recorded.
func (b *Bundle) pick(pkt []byte) *slot {
	key, complete := flowKey(pkt)

	// Fragmented traffic keys on addresses+protocol only, so all fragments
	// of one flow take the same path (the rewrite keeps sources consistent).
	if !complete {
		k2, _ := addrProtoKey(pkt)
		key = k2
	}

	b.affMu.Lock()
	if e, ok := b.aff[key]; ok {
		delete(b.aff, key) // re-adding below keeps lastUse fresh
		b.affMu.Unlock()
		e.slot.mu.Lock()
		ready := e.slot.ready && !e.slot.dead
		e.slot.mu.Unlock()
		if ready {
			b.affMu.Lock()
			b.aff[key] = affinityEntry{slot: e.slot, lastUse: time.Now()}
			b.affMu.Unlock()
			return e.slot
		}
	} else {
		b.affMu.Unlock()
	}

	// Choose among ready slots round-robin.
	n := len(b.slots)
	for tried := 0; tried < n; tried++ {
		i := int(b.rr.Add(1) % uint64(n))
		s := b.slots[i]
		s.mu.Lock()
		ready := s.ready && !s.dead && s.localIP != nil
		s.mu.Unlock()
		if ready {
			b.affMu.Lock()
			b.aff[key] = affinityEntry{slot: s, lastUse: time.Now()}
			b.affMu.Unlock()
			return s
		}
	}
	// Nothing ready: fall back to primary (may block/err on a dead session).
	return b.primary()
}

func (b *Bundle) primary() *slot {
	if len(b.slots) == 0 {
		return nil
	}
	return b.slots[0]
}

func (b *Bundle) dropAffinity(s *slot) {
	b.affMu.Lock()
	for k, e := range b.aff {
		if e.slot == s {
			delete(b.aff, k)
		}
	}
	b.affMu.Unlock()
}

// affinitySweep evicts stale flow entries so the map stays bounded.
func (b *Bundle) affinitySweep() {
	t := time.NewTicker(60 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-b.done:
			return
		case <-t.C:
			cutoff := time.Now().Add(-5 * time.Minute)
			b.affMu.Lock()
			for k, e := range b.aff {
				if e.lastUse.Before(cutoff) {
					delete(b.aff, k)
				}
			}
			b.affMu.Unlock()
		}
	}
}

// ReadIP returns the next packet of the merged stream (already DNAT'd to the
// primary address).
func (b *Bundle) ReadIP(buf []byte) (int, error) {
	select {
	case pkt, ok := <-b.inbound:
		if !ok {
			return 0, ErrClosed
		}
		return copy(buf, pkt), nil
	case <-b.done:
		select {
		case pkt := <-b.inbound:
			return copy(buf, pkt), nil
		default:
			return 0, ErrClosed
		}
	}
}

// StatsJSON reports aggregate and per-session counters.
func (b *Bundle) StatsJSON() string {
	type sessStat struct {
		Index   int    `json:"index"`
		LocalIP string `json:"local_ip"`
		Up      uint64 `json:"up"`
		Down    uint64 `json:"down"`
		Ready   bool   `json:"ready"`
	}
	type stats struct {
		Up       uint64     `json:"up"`
		Down     uint64     `json:"down"`
		Sessions []sessStat `json:"sessions"`
	}
	var st stats
	for _, s := range b.slots {
		s.mu.Lock()
		ss := sessStat{Index: s.idx, Ready: s.ready}
		if s.localIP != nil {
			ss.LocalIP = s.localIP.String()
		}
		sess := s.sess
		s.mu.Unlock()
		if sess != nil {
			ss.Up, ss.Down = sess.Counters()
		}
		st.Up += ss.Up
		st.Down += ss.Down
		st.Sessions = append(st.Sessions, ss)
	}
	out, _ := json.Marshal(st)
	return string(out)
}

// Counters returns aggregate inner bytes.
func (b *Bundle) Counters() (up, down uint64) {
	var st struct {
		Up   uint64 `json:"up"`
		Down uint64 `json:"down"`
	}
	_ = json.Unmarshal([]byte(b.StatsJSON()), &st)
	return st.Up, st.Down
}

// Wait blocks until every session is gone and the bundle is done.
func (b *Bundle) Wait() error {
	<-b.done
	return nil
}

// Close tears down all sessions.
func (b *Bundle) Close() error {
	b.closeOnce.Do(func() {
		close(b.done)
		for _, s := range b.slots {
			s.mu.Lock()
			if s.sess != nil {
				_ = s.sess.Close()
			}
			s.dead = true
			s.ready = false
			s.mu.Unlock()
		}
	})
	return nil
}

func (b *Bundle) stopped() bool {
	select {
	case <-b.done:
		return true
	default:
		return false
	}
}

// ---- packet surgery ----

// flowKey hashes the 5-tuple (IPv4). complete=false when the packet carries
// no port fields (non-TCP/UDP or non-first fragment).
func flowKey(pkt []byte) (uint64, bool) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return 0, false
	}
	ihl := int(pkt[0]&0x0f) * 4
	if ihl < 20 || len(pkt) < ihl {
		return 0, false
	}
	proto := pkt[9]
	src := pkt[12:16]
	dst := pkt[16:20]
	h := fnv.New64a()
	h.Write(src)
	h.Write(dst)
	h.Write([]byte{proto})
	if proto == 6 || proto == 17 { // TCP, UDP
		if fragOff := binary.BigEndian.Uint16(pkt[6:8]) & 0x1fff; fragOff == 0 {
			if len(pkt) >= ihl+4 {
				h.Write(pkt[ihl : ihl+4])
				return h.Sum64(), true
			}
		}
	}
	return h.Sum64(), false
}

// addrProtoKey is the fragment-safe fallback key.
func addrProtoKey(pkt []byte) (uint64, bool) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return 0, false
	}
	h := fnv.New64a()
	h.Write(pkt[12:20])
	h.Write([]byte{pkt[9]})
	return h.Sum64(), true
}

// outboundSNAT rewrites source oldIP -> newIP in place: IP header checksum
// and TCP/UDP checksums are fixed incrementally (RFC 1624); payload untouched.
func outboundSNAT(pkt []byte, oldIP, newIP net.IP) {
	rewrite(pkt, 12, oldIP, newIP, true)
}

// inboundDNAT rewrites destination newIP-owning packets back to the primary:
// dst slotIP -> primaryIP.
func inboundDNAT(pkt []byte, slotIP, primaryIP net.IP) {
	rewrite(pkt, 16, slotIP, primaryIP, false)
}

// rewrite substitutes oldIP with newIP at byte offset (12=src, 16=dst) and
// patches the IPv4 header checksum plus, for TCP/UDP, the L4 checksum.
func rewrite(pkt []byte, offset int, oldIP, newIP net.IP, isSrc bool) {
	o := oldIP.To4()
	n := newIP.To4()
	if o == nil || n == nil || len(pkt) < 20 {
		return
	}
	if !bytesEqual4(pkt[offset:offset+4], o) {
		return
	}
	// IP header checksum (offset 10).
	binary.BigEndian.PutUint16(pkt[10:12], csumUpdate(binary.BigEndian.Uint16(pkt[10:12]), o, n))
	copy(pkt[offset:offset+4], n)

	proto := pkt[9]
	if proto != 6 && proto != 17 {
		return // ICMPv4's checksum has no pseudo-header; ESP/GRE: rare, unpatched
	}
	ihl := int(pkt[0]&0x0f) * 4
	if len(pkt) < ihl+8 {
		return
	}
	if fragOff := binary.BigEndian.Uint16(pkt[6:8]) & 0x1fff; fragOff != 0 {
		return // non-first fragment has no L4 header
	}
	if proto == 17 && len(pkt) >= ihl+8 {
		if binary.BigEndian.Uint16(pkt[ihl+6:ihl+8]) == 0 {
			return // UDP checksum unset (legal in IPv4)
		}
	}
	csumOff := ihl + 16
	if proto == 17 {
		csumOff = ihl + 6
	}
	if len(pkt) < csumOff+2 {
		return
	}
	binary.BigEndian.PutUint16(pkt[csumOff:csumOff+2],
		csumUpdate(binary.BigEndian.Uint16(pkt[csumOff:csumOff+2]), o, n))
}

// csumUpdate applies RFC 1624 incremental update for a 16-bit field whose
// pseudo-header/covered words changed from oldIP to newIP (4 bytes each).
func csumUpdate(csum uint16, oldIP, newIP []byte) uint16 {
	s := uint32(^csum) // one's-complement inverse of the field
	for i := 0; i < 4; i += 2 {
		old := uint32(binary.BigEndian.Uint16(oldIP[i : i+2]))
		nw := uint32(binary.BigEndian.Uint16(newIP[i : i+2]))
		s += ^old & 0xffff
		s += nw
	}
	for s>>16 != 0 {
		s = (s & 0xffff) + (s >> 16)
	}
	return ^uint16(s)
}

func bytesEqual4(a, b []byte) bool {
	return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]
}
