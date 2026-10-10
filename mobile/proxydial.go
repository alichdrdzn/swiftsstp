package mobile

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"sstpflash/tunnel"
)

// newProxy builds the engine's upstream-proxy config from the flattened
// constructor parameters. A blank proxyType means "direct" and yields nil —
// the callers store that nil and leave the transport on the direct path.
func newProxy(proxyType, proxyHost string, proxyPort int, proxyUser, proxyPass string) *tunnel.ProxyConfig {
	if strings.TrimSpace(proxyType) == "" {
		return nil
	}
	return &tunnel.ProxyConfig{
		Type:     strings.TrimSpace(proxyType),
		Host:     strings.TrimSpace(proxyHost),
		Port:     proxyPort,
		Username: proxyUser,
		Password: proxyPass,
	}
}

// proxyDial opens the outer transport through px and returns a conn that has
// already completed the proxy handshake toward target and is therefore ready
// for the caller's tls.Client — exactly what tunnel.Config.DialConn must
// hand back.
//
// The hop lives here instead of tunnel.Config.Proxy because tunnel.Dial
// prefers DialConn when both are set, and the Android path must own
// connection establishment: every socket fd — including the socket to the
// proxy itself — has to reach VpnService.protect before connect() or it
// would route back into the TUN it is carrying. tunnel.proxyDial dials with
// its own net.Dialer and has no hook for the protector, so the same
// CONNECT/SOCKS5 exchange is performed here over a protected conn.
func proxyDial(p Protector, ctx context.Context, px *tunnel.ProxyConfig, network, target string) (net.Conn, error) {
	if err := validateProxy(px); err != nil {
		return nil, err
	}
	conn, err := protectDial(p, ctx, network, net.JoinHostPort(px.Host, strconv.Itoa(px.Port)))
	if err != nil {
		return nil, err
	}
	// Bound the handshake so a hung proxy cannot stall establishment.
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	var err2 error
	switch px.Type {
	case "http":
		err2 = proxyHTTPConnect(conn, px, target)
	case "socks5":
		err2 = proxySOCKS5Connect(ctx, conn, px, target)
	default:
		err2 = fmt.Errorf("unsupported proxy type %q (want http or socks5)", px.Type)
	}
	if err2 != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("proxy %s handshake: %w", px.Type, err2)
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, nil
}

// validateProxy mirrors tunnel.ProxyConfig.validate (unexported there).
func validateProxy(px *tunnel.ProxyConfig) error {
	if px == nil {
		return fmt.Errorf("proxy config is nil")
	}
	switch px.Type {
	case "http", "socks5":
	default:
		return fmt.Errorf("unsupported proxy type %q (want http or socks5)", px.Type)
	}
	if px.Host == "" || px.Port == 0 {
		return fmt.Errorf("proxy host and port are required")
	}
	return nil
}

// proxyHTTPConnect performs the RFC 9110 CONNECT exchange (same shape as
// tunnel.proxyHTTPConnect, over the already-protected conn).
func proxyHTTPConnect(conn net.Conn, px *tunnel.ProxyConfig, target string) error {
	var b strings.Builder
	fmt.Fprintf(&b, "CONNECT %s HTTP/1.1\r\nHost: %s\r\n", target, target)
	if px.Username != "" || px.Password != "" {
		cred := base64.StdEncoding.EncodeToString([]byte(px.Username + ":" + px.Password))
		fmt.Fprintf(&b, "Proxy-Authorization: Basic %s\r\n", cred)
	}
	b.WriteString("Proxy-Connection: keep-alive\r\n\r\n")
	if _, err := io.WriteString(conn, b.String()); err != nil {
		return err
	}
	// Read exactly the header block; bufio would over-read into the TLS
	// stream that starts immediately after CRLFCRLF.
	br := bufio.NewReaderSize(conn, 1024)
	status, err := http.ReadResponse(br, &http.Request{Method: http.MethodConnect})
	if err != nil {
		return err
	}
	defer status.Body.Close()
	if status.StatusCode < 200 || status.StatusCode > 299 {
		return fmt.Errorf("proxy refused CONNECT: %s", status.Status)
	}
	if br.Buffered() > 0 {
		// Extremely rare: proxy pipelined bytes.
		leftover, _ := br.Peek(br.Buffered())
		return fmt.Errorf("proxy sent %d unexpected early bytes", len(leftover))
	}
	return nil
}

// proxySOCKS5Connect performs RFC 1928 (plus RFC 1929 auth) toward target.
func proxySOCKS5Connect(ctx context.Context, conn net.Conn, px *tunnel.ProxyConfig, target string) error {
	// Greeting: VER=5, methods; offer no-auth and (if configured) user/pass.
	methods := []byte{0x00} // no-auth
	if px.Username != "" || px.Password != "" {
		methods = append(methods, 0x02)
	}
	greet := append([]byte{0x05, byte(len(methods))}, methods...)
	if _, err := conn.Write(greet); err != nil {
		return err
	}
	var sel [2]byte
	if _, err := io.ReadFull(conn, sel[:]); err != nil {
		return err
	}
	if sel[0] != 0x05 {
		return fmt.Errorf("not SOCKS5 (ver %#x)", sel[0])
	}
	switch sel[1] {
	case 0x00:
	case 0x02:
		u := []byte(px.Username)
		w := []byte(px.Password)
		if len(u) > 255 || len(w) > 255 {
			return fmt.Errorf("proxy credentials too long")
		}
		req := append([]byte{0x01, byte(len(u))}, u...)
		req = append(req, byte(len(w)))
		req = append(req, w...)
		if _, err := conn.Write(req); err != nil {
			return err
		}
		var rep [2]byte
		if _, err := io.ReadFull(conn, rep[:]); err != nil {
			return err
		}
		if rep[1] != 0x00 {
			return fmt.Errorf("proxy auth failed (status %#x)", rep[1])
		}
	case 0xFF:
		return fmt.Errorf("proxy offers no acceptable auth method")
	default:
		return fmt.Errorf("proxy chose unexpected method %#x", sel[1])
	}

	// CONNECT request with the target address in the right ATYP form.
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return err
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("bad target port %q", portStr)
	}
	req := []byte{0x05, 0x01, 0x00}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(req, 0x01)
			req = append(req, v4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		if len(host) > 255 {
			return fmt.Errorf("target host too long")
		}
		req = append(req, 0x03, byte(len(host)))
		req = append(req, host...)
	}
	req = append(req, byte(port>>8), byte(port))
	if _, err := conn.Write(req); err != nil {
		return err
	}

	// Reply: VER REP RSV ATYP + BND.ADDR BND.PORT. The bind address length
	// depends on ATYP, so parse the header first.
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return err
	}
	if hdr[0] != 0x05 {
		return fmt.Errorf("bad SOCKS5 reply version %#x", hdr[0])
	}
	if hdr[1] != 0x00 {
		return fmt.Errorf("SOCKS5 CONNECT failed: %s", socks5ReplyName(hdr[1]))
	}
	var addrLen int
	switch hdr[3] {
	case 0x01:
		addrLen = 4
	case 0x04:
		addrLen = 16
	case 0x03:
		var l [1]byte
		if _, err := io.ReadFull(conn, l[:]); err != nil {
			return err
		}
		addrLen = int(l[0])
	default:
		return fmt.Errorf("bad SOCKS5 ATYP %#x", hdr[3])
	}
	if _, err := io.ReadFull(conn, make([]byte, addrLen+2)); err != nil {
		return err
	}
	_ = ctx
	return nil
}

func socks5ReplyName(code byte) string {
	switch code {
	case 0x01:
		return "general failure"
	case 0x02:
		return "connection not allowed"
	case 0x03:
		return "network unreachable"
	case 0x04:
		return "host unreachable"
	case 0x05:
		return "connection refused"
	case 0x06:
		return "TTL expired"
	case 0x07:
		return "command not supported"
	case 0x08:
		return "address type not supported"
	}
	return fmt.Sprintf("code %#x", code)
}
