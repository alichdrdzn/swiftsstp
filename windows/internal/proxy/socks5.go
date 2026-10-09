package proxy

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// SOCKS5 (RFC 1928) server: no-auth CONNECT (TCP, priority) and UDP
// ASSOCIATE. The listener is local-only (127.0.0.1).
func (p *Proxy) handleSOCKS(c net.Conn) {
	br := bufio.NewReaderSize(c, 64<<10)
	_ = c.SetDeadline(time.Now().Add(20 * time.Second))

	// greeting
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(br, hdr); err != nil || hdr[0] != 5 {
		return
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	noAuth := false
	for _, m := range methods {
		if m == 0x00 {
			noAuth = true
		}
	}
	if !noAuth {
		_, _ = c.Write([]byte{5, 0xFF})
		return
	}
	if _, err := c.Write([]byte{5, 0}); err != nil {
		return
	}

	// request
	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil || req[0] != 5 {
		return
	}
	cmd := req[1]
	host, port, err := readSOCKSAddr(br, req[3])
	if err != nil {
		_, _ = c.Write(socksReply(0x01, nil, 0))
		return
	}

	switch cmd {
	case 0x01: // CONNECT
		p.socksConnect(c, br, host, port)
	case 0x03: // UDP ASSOCIATE
		p.socksUDPAssociate(c, br, host, port)
	default:
		_, _ = c.Write(socksReply(0x07, nil, 0)) // command not supported
	}
}

func (p *Proxy) socksConnect(c net.Conn, br *bufio.Reader, host string, port int) {
	vn, _ := p.current()
	if vn == nil {
		_, _ = c.Write(socksReply(0x01, nil, 0))
		return
	}
	ip, err := vn.lookupIP(host)
	if err != nil {
		p.cfg.Logf("proxy: socks resolve %q: %v", host, err)
		_, _ = c.Write(socksReply(0x04, nil, 0)) // host unreachable
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	remote, err := vn.dialTCP(ctx, ip, port)
	cancel()
	if err != nil {
		p.cfg.Logf("proxy: socks connect %s:%d: %v", ip, port, err)
		_, _ = c.Write(socksReply(0x05, nil, 0)) // connection refused
		return
	}
	if la, ok := remote.LocalAddr().(*net.TCPAddr); ok {
		_, _ = c.Write(socksReply(0x00, la.IP, la.Port))
	} else {
		_, _ = c.Write(socksReply(0x00, nil, 0))
	}
	_ = c.SetDeadline(time.Time{}) // hand off to the tunnel bridge
	bridge(c, br, remote)
}

// ---- UDP ASSOCIATE ----

// udpAssoc is one client's UDP relay: local UDP socket <-> per-target
// virtual UDP sockets inside the netstack.
type udpAssoc struct {
	p      *Proxy
	relay  *net.UDPConn
	client *net.UDPAddr // where to send replies

	mu    sync.Mutex
	flow  map[string]*udpFlow
	close chan struct{}
	once  sync.Once
}

type udpFlow struct {
	vn   *virtualNet
	conn interface {
		Read([]byte) (int, error)
		Write([]byte) (int, error)
		Close() error
	}
}

func (p *Proxy) socksUDPAssociate(c net.Conn, br *bufio.Reader, host string, port int) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		_, _ = c.Write(socksReply(0x01, nil, 0))
		return
	}
	// Note: ListenUDP with port 0 binds an ephemeral local port.
	la := relay.LocalAddr().(*net.UDPAddr)
	if _, err := c.Write(socksReply(0x00, net.IPv4(127, 0, 0, 1), la.Port)); err != nil {
		_ = relay.Close()
		return
	}

	var client *net.UDPAddr
	if host != "" && host != "0.0.0.0" && host != "::" {
		if ip := net.ParseIP(host); ip != nil && port > 0 {
			client = &net.UDPAddr{IP: ip, Port: port}
		}
	}

	a := &udpAssoc{
		p:      p,
		relay:  relay,
		client: client,
		flow:   map[string]*udpFlow{},
		close:  make(chan struct{}),
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() { defer wg.Done(); a.readLoop() }()

	// Keep the association alive until the client closes the TCP control
	// connection (or the proxy tears everything down).
	_, _ = io.Copy(io.Discard, c)
	a.stop()
	wg.Wait()
}

func (a *udpAssoc) stop() {
	a.once.Do(func() {
		close(a.close)
		_ = a.relay.Close()
		a.mu.Lock()
		for _, f := range a.flow {
			_ = f.conn.Close()
		}
		a.flow = nil
		a.mu.Unlock()
	})
}

func (a *udpAssoc) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, from, err := a.relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 4 {
			continue
		}
		if buf[3] != 0x00 { // FRAG must be 0 (no IP fragmentation reassembly)
			continue
		}
		host, port, hdrLen, err := parseSOCKSUDPHeader(buf[:n])
		if err != nil {
			continue
		}
		a.mu.Lock()
		if a.client == nil {
			a.client = from
		}
		client := a.client
		a.mu.Unlock()
		if client == nil || client.Port == 0 {
			continue
		}

		vn, _ := a.p.current()
		if vn == nil {
			continue
		}
		ip, err := vn.lookupIP(host)
		if err != nil {
			continue
		}
		key := fmt.Sprintf("%s:%d", ip, port)
		f, err := a.getFlow(vn, key, ip, port)
		if err != nil {
			continue
		}
		if _, err := f.conn.Write(buf[hdrLen:n]); err != nil {
			a.dropFlow(key)
		}
	}
}

func (a *udpAssoc) getFlow(vn *virtualNet, key string, ip net.IP, port int) (*udpFlow, error) {
	a.mu.Lock()
	if a.flow == nil {
		a.mu.Unlock()
		return nil, fmt.Errorf("assoc closed")
	}
	if f, ok := a.flow[key]; ok {
		a.mu.Unlock()
		return f, nil
	}
	a.mu.Unlock()

	conn, err := vn.dialUDP(ip, port)
	if err != nil {
		return nil, err
	}
	f := &udpFlow{vn: vn, conn: conn}
	a.mu.Lock()
	if a.flow == nil { // closed meanwhile
		a.mu.Unlock()
		_ = conn.Close()
		return nil, fmt.Errorf("assoc closed")
	}
	a.flow[key] = f
	a.mu.Unlock()

	go a.flowReader(key, conn, ip, port)
	return f, nil
}

func (a *udpAssoc) dropFlow(key string) {
	a.mu.Lock()
	if f, ok := a.flow[key]; ok {
		delete(a.flow, key)
		a.mu.Unlock()
		_ = f.conn.Close()
		return
	}
	a.mu.Unlock()
}

// flowReader pushes datagrams coming back through the tunnel to the client,
// wrapped in the SOCKS UDP header.
func (a *udpAssoc) flowReader(key string, conn interface {
	Read([]byte) (int, error)
	Close() error
}, ip net.IP, port int) {
	defer a.dropFlow(key)
	buf := make([]byte, 65535)
	for {
		select {
		case <-a.close:
			return
		default:
		}
		n, err := conn.Read(buf)
		if err != nil {
			return
		}
		pkt := buildSOCKSUDPHeader(ip, port, buf[:n])
		a.mu.Lock()
		client := a.client
		a.mu.Unlock()
		if client == nil {
			return
		}
		if _, err := a.relay.WriteToUDP(pkt, client); err != nil {
			return
		}
	}
}

// ---- wire helpers ----

func readSOCKSAddr(br *bufio.Reader, atyp byte) (string, int, error) {
	switch atyp {
	case 0x01: // IPv4
		b := make([]byte, 4)
		if _, err := io.ReadFull(br, b); err != nil {
			return "", 0, err
		}
		port, err := readPort(br)
		return net.IP(b).String(), port, err
	case 0x03: // domain
		l := make([]byte, 1)
		if _, err := io.ReadFull(br, l); err != nil {
			return "", 0, err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(br, b); err != nil {
			return "", 0, err
		}
		port, err := readPort(br)
		return string(b), port, err
	case 0x04: // IPv6
		b := make([]byte, 16)
		if _, err := io.ReadFull(br, b); err != nil {
			return "", 0, err
		}
		port, err := readPort(br)
		return net.IP(b).String(), port, err
	default:
		return "", 0, fmt.Errorf("bad atyp %d", atyp)
	}
}

func readPort(br *bufio.Reader) (int, error) {
	b := make([]byte, 2)
	if _, err := io.ReadFull(br, b); err != nil {
		return 0, err
	}
	return int(binary.BigEndian.Uint16(b)), nil
}

func socksReply(rep byte, ip net.IP, port int) []byte {
	out := []byte{5, rep, 0x00}
	if ip4 := ip.To4(); ip4 != nil {
		out = append(out, 0x01)
		out = append(out, ip4...)
	} else if ip != nil {
		out = append(out, 0x04)
		out = append(out, ip.To16()...)
	} else {
		out = append(out, 0x01, 0, 0, 0, 0)
	}
	out = binary.BigEndian.AppendUint16(out, uint16(port))
	return out
}

// parseSOCKSUDPHeader decodes [RSV RSV FRAG][ATYP][ADDR][PORT] and returns
// the target plus the header length.
func parseSOCKSUDPHeader(b []byte) (string, int, int, error) {
	if len(b) < 4 {
		return "", 0, 0, fmt.Errorf("short")
	}
	off := 4
	switch b[3] {
	case 0x01:
		if len(b) < off+4+2 {
			return "", 0, 0, fmt.Errorf("short v4")
		}
		ip := net.IP(b[off : off+4])
		port := int(binary.BigEndian.Uint16(b[off+4 : off+6]))
		return ip.String(), port, off + 6, nil
	case 0x03:
		if len(b) < off+1 {
			return "", 0, 0, fmt.Errorf("short dom")
		}
		l := int(b[off])
		off++
		if len(b) < off+l+2 {
			return "", 0, 0, fmt.Errorf("short dom body")
		}
		host := string(b[off : off+l])
		off += l
		port := int(binary.BigEndian.Uint16(b[off : off+2]))
		return host, port, off + 2, nil
	case 0x04:
		if len(b) < off+16+2 {
			return "", 0, 0, fmt.Errorf("short v6")
		}
		ip := net.IP(b[off : off+16])
		port := int(binary.BigEndian.Uint16(b[off+16 : off+18]))
		return ip.String(), port, off + 18, nil
	default:
		return "", 0, 0, fmt.Errorf("bad atyp")
	}
}

func buildSOCKSUDPHeader(ip net.IP, port int, payload []byte) []byte {
	out := []byte{0, 0, 0}
	if ip4 := ip.To4(); ip4 != nil {
		out = append(out, 0x01)
		out = append(out, ip4...)
	} else {
		out = append(out, 0x04)
		out = append(out, ip.To16()...)
	}
	out = binary.BigEndian.AppendUint16(out, uint16(port))
	return append(out, payload...)
}
