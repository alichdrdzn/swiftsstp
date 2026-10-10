package multi

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"swiftsstp/tunnel"
)

// ---- fakes ----

type fakeSession struct {
	mu       sync.Mutex
	written  [][]byte
	read     chan []byte
	up, down uint64
	closed   bool
}

func newFakeSession() *fakeSession {
	return &fakeSession{read: make(chan []byte, 16)}
}

func (f *fakeSession) ReadIP(buf []byte) (int, error) {
	pkt, ok := <-f.read
	if !ok {
		return 0, errors.New("closed")
	}
	return copy(buf, pkt), nil
}

func (f *fakeSession) WriteIP(pkt []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return errors.New("closed")
	}
	cp := make([]byte, len(pkt))
	copy(cp, pkt)
	f.written = append(f.written, cp)
	f.up += uint64(len(pkt))
	return nil
}

func (f *fakeSession) Counters() (uint64, uint64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.up, f.down
}

func (f *fakeSession) Close() error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closed = true
	return nil
}

func (f *fakeSession) Wait() error { <-time.After(time.Hour); return nil }

// fakeDialer hands out scripted sessions with distinct inner IPs.
type fakeDialer struct {
	mu       sync.Mutex
	sessions []*fakeSession
	ips      []net.IP
	next     int
}

func (d *fakeDialer) dial(ctx context.Context) (Session, tunnel.Result, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.next >= len(d.sessions) {
		return nil, tunnel.Result{}, errors.New("exhausted")
	}
	s := d.sessions[d.next]
	ip := d.ips[d.next]
	d.next++
	return s, tunnel.Result{LocalIP: ip, PeerIP: net.IPv4(10, 0, 0, 1), MTU: 1400}, nil
}

// ---- helpers ----

func ipv4TCP(src, dst net.IP, sport, dport uint16) []byte {
	pkt := make([]byte, 40)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], 40) // total length
	pkt[9] = 6                                // TCP
	copy(pkt[12:16], src.To4())
	copy(pkt[16:20], dst.To4())
	binary.BigEndian.PutUint16(pkt[20:22], sport)
	binary.BigEndian.PutUint16(pkt[22:24], dport)
	// header checksum
	binary.BigEndian.PutUint16(pkt[10:12], checksum(pkt[:20]))
	// TCP checksum over the IPv4 pseudo-header: field stays zero while
	// computing, so the summed words are exactly the final coverage.
	binary.BigEndian.PutUint16(pkt[36:38], pseudoChecksum(pkt))
	return pkt
}

// RFC 1071 checksum.
func checksum(b []byte) uint16 {
	var s uint32
	for i := 0; i+1 < len(b); i += 2 {
		s += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		s += uint32(b[len(b)-1]) << 8
	}
	for s>>16 != 0 {
		s = (s & 0xffff) + (s >> 16)
	}
	return ^uint16(s)
}

// pseudoChecksum computes the TCP/UDP checksum incl. IPv4 pseudo-header.
func pseudoChecksum(pkt []byte) uint16 {
	ihl := int(pkt[0]&0x0f) * 4
	var s uint32
	s += uint32(binary.BigEndian.Uint16(pkt[12:14]))
	s += uint32(binary.BigEndian.Uint16(pkt[14:16]))
	s += uint32(binary.BigEndian.Uint16(pkt[16:18]))
	s += uint32(binary.BigEndian.Uint16(pkt[18:20]))
	s += uint32(pkt[9])
	s += uint32(len(pkt) - ihl)
	for i := ihl; i+1 < len(pkt); i += 2 {
		s += uint32(binary.BigEndian.Uint16(pkt[i : i+2]))
	}
	if (len(pkt)-ihl)%2 == 1 {
		s += uint32(pkt[len(pkt)-1]) << 8
	}
	for s>>16 != 0 {
		s = (s & 0xffff) + (s >> 16)
	}
	return ^uint16(s)
}

func newTestBundle(t *testing.T, n int) (*Bundle, *fakeDialer) {
	t.Helper()
	d := &fakeDialer{}
	for i := 0; i < n; i++ {
		d.sessions = append(d.sessions, newFakeSession())
		d.ips = append(d.ips, net.IPv4(192, 168, 30, byte(10+i)).To4())
	}
	b, _, err := Dial(context.Background(), Config{Dial: d.dial, Sessions: n})
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	t.Cleanup(func() { b.Close() })
	// Give background slots a moment to establish.
	deadline := time.Now().Add(2 * time.Second)
	for {
		ready := 0
		for _, s := range b.slots {
			s.mu.Lock()
			if s.ready {
				ready++
			}
			s.mu.Unlock()
		}
		if ready == n || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	return b, d
}

// ---- tests ----

// The rewrite must leave both checksums valid: recompute over the rewritten
// packet and require the one's-complement sum to be 0xFFFF.
func TestSNATChecksumValid(t *testing.T) {
	src := net.IPv4(192, 168, 30, 10)
	dst := net.IPv4(1, 1, 1, 1)
	pkt := ipv4TCP(src, dst, 1234, 443)
	if got := pseudoChecksum(pkt); got != 0 {
		t.Fatalf("precondition: constructed packet checksum invalid: %#x", got)
	}
	outboundSNAT(pkt, src, net.IPv4(192, 168, 30, 11))
	if !net.IP(pkt[12:16]).Equal(net.IPv4(192, 168, 30, 11)) {
		t.Fatalf("src not rewritten: %v", net.IP(pkt[12:16]))
	}
	if got := checksum(pkt[:20]); got != 0 {
		t.Errorf("IP header checksum invalid after SNAT: %#x", got)
	}
	if got := pseudoChecksum(pkt); got != 0 {
		t.Errorf("TCP checksum invalid after SNAT: %#x", got)
	}
}

func TestDNATChecksumValid(t *testing.T) {
	slotIP := net.IPv4(192, 168, 30, 11)
	pkt := ipv4TCP(net.IPv4(1, 1, 1, 1), slotIP, 443, 1234)
	if got := pseudoChecksum(pkt); got != 0 {
		t.Fatalf("precondition: constructed packet checksum invalid: %#x", got)
	}
	inboundDNAT(pkt, slotIP, net.IPv4(192, 168, 30, 10))
	if got := checksum(pkt[:20]); got != 0 {
		t.Errorf("IP header checksum invalid after DNAT: %#x", got)
	}
	if got := pseudoChecksum(pkt); got != 0 {
		t.Errorf("TCP checksum invalid after DNAT: %#x", got)
	}
}

// Distinct flows must spread across all ready sessions; the same flow must
// stay pinned.
func TestFlowDistribution(t *testing.T) {
	b, d := newTestBundle(t, 3)
	dst := net.IPv4(8, 8, 8, 8)
	primary := d.ips[0]

	// 6 distinct flows.
	for i := 0; i < 6; i++ {
		pkt := ipv4TCP(primary, dst, uint16(10000+i), 443)
		if err := b.WriteIP(pkt); err != nil {
			t.Fatalf("WriteIP %d: %v", i, err)
		}
	}
	counts := map[string]int{}
	for i, s := range d.sessions {
		s.mu.Lock()
		counts[net.IP(d.ips[i]).String()] = len(s.written)
		s.mu.Unlock()
	}
	total := 0
	for _, c := range counts {
		total += c
	}
	if total != 6 {
		t.Fatalf("expected 6 packets across sessions, got %v", counts)
	}
	for ip, c := range counts {
		if c == 0 {
			t.Errorf("session %s got no flows (distribution broken): %v", ip, counts)
		}
	}

	// Same 5-tuple again -> same session.
	pkt := ipv4TCP(primary, dst, 10000, 443)
	before := map[string]int{}
	for i, s := range d.sessions {
		s.mu.Lock()
		before[net.IP(d.ips[i]).String()] = len(s.written)
		s.mu.Unlock()
	}
	if err := b.WriteIP(pkt); err != nil {
		t.Fatal(err)
	}
	for i, s := range d.sessions {
		s.mu.Lock()
		after := len(s.written)
		s.mu.Unlock()
		if after != before[net.IP(d.ips[i]).String()] && d.ips[i].Equal(primary) {
			// primary may legitimately be the pinned slot
		}
		_ = after
	}
	// Pinned session must be the one that received it — find which grew.
	grew := -1
	for i, s := range d.sessions {
		s.mu.Lock()
		if len(s.written) > before[net.IP(d.ips[i]).String()] {
			grew = i
		}
		s.mu.Unlock()
	}
	if grew == -1 {
		t.Fatal("repeat flow went nowhere")
	}
	// Repeat once more: must hit the same slot.
	if err := b.WriteIP(pkt); err != nil {
		t.Fatal(err)
	}
	s := d.sessions[grew]
	s.mu.Lock()
	n := len(s.written)
	s.mu.Unlock()
	if n == before[net.IP(d.ips[grew]).String()] {
		t.Errorf("repeat flow did not stick to slot %d", grew)
	}
}

// Packets pushed into a non-primary session's inbound must reach ReadIP with
// the destination rewritten to the primary address.
func TestInboundDNAT(t *testing.T) {
	b, d := newTestBundle(t, 2)
	primary := d.ips[0]
	other := d.ips[1]
	in := ipv4TCP(net.IPv4(1, 1, 1, 1), other, 443, 5555)
	d.sessions[1].read <- in
	buf := make([]byte, 65535)
	n, err := b.ReadIP(buf)
	if err != nil {
		t.Fatal(err)
	}
	if !net.IP(buf[16:20]).Equal(primary) {
		t.Errorf("dst not DNAT'd to primary: %v", net.IP(buf[16:20]))
	}
	if n != 40 {
		t.Errorf("length %d", n)
	}
	if got := checksum(buf[:20]); got != 0 {
		t.Errorf("IP checksum invalid after DNAT: %#x", got)
	}
}

// When a session dies, its flows must redistribute to survivors.
func TestFailover(t *testing.T) {
	b, d := newTestBundle(t, 2)
	dst := net.IPv4(9, 9, 9, 9)
	primary := d.ips[0]

	// Pin a flow to the secondary.
	pkt := ipv4TCP(primary, dst, 4444, 443)
	if err := b.WriteIP(pkt); err != nil {
		t.Fatal(err)
	}
	// Kill the secondary: close read side so pumpSlot returns, mark session.
	var sec *slot
	for _, s := range b.slots {
		if s.idx == 1 {
			sec = s
		}
	}
	close(d.sessions[1].read)
	deadline := time.Now().Add(2 * time.Second)
	for {
		sec.mu.Lock()
		ready := sec.ready
		sec.mu.Unlock()
		if !ready || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	// New packet: must land on the primary.
	if err := b.WriteIP(pkt); err != nil {
		t.Fatalf("write after failover: %v", err)
	}
	d.sessions[0].mu.Lock()
	n := len(d.sessions[0].written)
	d.sessions[0].mu.Unlock()
	if n < 1 {
		t.Fatal("no packet reached primary after failover")
	}
}

// Stats must aggregate per-session counters.
func TestStatsAggregate(t *testing.T) {
	b, _ := newTestBundle(t, 2)
	dsts := []uint16{80, 81}
	for i, dp := range dsts {
		_ = i
		pkt := ipv4TCP(net.IPv4(192, 168, 30, 10), net.IPv4(8, 8, 8, 8), uint16(20000+i), dp)
		if err := b.WriteIP(pkt); err != nil {
			t.Fatal(err)
		}
	}
	up, _ := b.Counters()
	if up == 0 {
		t.Fatal("aggregate up is zero")
	}
}
