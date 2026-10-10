package mobile

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"sstpflash/tunnel"
)

// startEcho accepts one TCP connection and echoes everything back.
func startEcho(t *testing.T) (addr string, stop func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("echo listen: %v", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				_, _ = io.Copy(c, c)
				_ = c.Close()
			}()
		}
	}()
	return ln.Addr().String(), func() { _ = ln.Close(); <-done }
}

// startHTTPProxy is a minimal CONNECT proxy: no-auth or Basic auth toward
// the target, then a blind pipe.
func startHTTPProxy(t *testing.T, user, pass string) (addr string, stop func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("proxy listen: %v", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				br := bufio.NewReaderSize(c, 4096)
				req, err := http.ReadRequest(br)
				if err != nil {
					return
				}
				if req.Method != http.MethodConnect {
					fmt.Fprint(c, "HTTP/1.1 405 Method Not Allowed\r\n\r\n")
					return
				}
				if user != "" {
					want := "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
					if got := req.Header.Get("Proxy-Authorization"); got != want {
						fmt.Fprint(c, "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
						return
					}
				}
				up, err := net.DialTimeout("tcp", req.Host, 3*time.Second)
				if err != nil {
					fmt.Fprint(c, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
					return
				}
				defer up.Close()
				if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
					return
				}
				// Anything the client pipelined behind the request belongs
				// to the tunnel.
				if br.Buffered() > 0 {
					leftover, _ := br.Peek(br.Buffered())
					if _, err := up.Write(leftover); err != nil {
						return
					}
				}
				pipe(c, up)
			}()
		}
	}()
	return ln.Addr().String(), func() { _ = ln.Close(); <-done }
}

// startSocks5Proxy implements RFC 1928 with optional RFC 1929 auth.
func startSocks5Proxy(t *testing.T, user, pass string) (addr string, stop func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("proxy listen: %v", err)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go serveSocks5(c, user, pass)
		}
	}()
	return ln.Addr().String(), func() { _ = ln.Close(); <-done }
}

func serveSocks5(c net.Conn, user, pass string) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	greet := make([]byte, 2)
	if _, err := io.ReadFull(c, greet); err != nil {
		return
	}
	methods := make([]byte, int(greet[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	if user != "" {
		if _, err := c.Write([]byte{0x05, 0x02}); err != nil {
			return
		}
		// RFC 1929: VER ULEN UNAME PLEN PASSWD
		authVer := make([]byte, 2) // VER + ULEN
		if _, err := io.ReadFull(c, authVer); err != nil {
			return
		}
		if authVer[0] != 0x01 {
			return
		}
		u := make([]byte, int(authVer[1]))
		if _, err := io.ReadFull(c, u); err != nil {
			return
		}
		plen := make([]byte, 1)
		if _, err := io.ReadFull(c, plen); err != nil {
			return
		}
		p := make([]byte, int(plen[0]))
		if _, err := io.ReadFull(c, p); err != nil {
			return
		}
		if string(u) != user || string(p) != pass {
			c.Write([]byte{0x01, 0x01})
			return
		}
		if _, err := c.Write([]byte{0x01, 0x00}); err != nil {
			return
		}
	} else {
		if _, err := c.Write([]byte{0x05, 0x00}); err != nil {
			return
		}
	}
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(c, hdr); err != nil {
		return
	}
	var host string
	switch hdr[3] {
	case 0x01:
		b := make([]byte, 4)
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 0x03:
		l := make([]byte, 1)
		if _, err := io.ReadFull(c, l); err != nil {
			return
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(c, b); err != nil {
			return
		}
		host = string(b)
	default:
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(c, pb); err != nil {
		return
	}
	target := net.JoinHostPort(host, fmt.Sprint(int(pb[0])<<8|int(pb[1])))
	up, err := net.DialTimeout("tcp", target, 3*time.Second)
	if err != nil {
		c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer up.Close()
	if _, err := c.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0}); err != nil {
		return
	}
	_ = c.SetDeadline(time.Time{})
	pipe(c, up)
}

func pipe(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(a, b); done <- struct{}{} }()
	go func() { _, _ = io.Copy(b, a); done <- struct{}{} }()
	<-done
	_ = a.Close()
	_ = b.Close()
	<-done
}

// roundTrip dials target through px and checks the echo comes back.
func roundTrip(t *testing.T, px *tunnel.ProxyConfig, target string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := proxyDial(nil, ctx, px, "tcp", target)
	if err != nil {
		t.Fatalf("proxyDial: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(conn, "hello-proxy"); err != nil {
		t.Fatalf("write: %v", err)
	}
	buf := make([]byte, len("hello-proxy"))
	if _, err := io.ReadFull(conn, buf); err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(buf) != "hello-proxy" {
		t.Fatalf("echo mismatch: %q", buf)
	}
}

func TestNewProxy(t *testing.T) {
	if p := newProxy("", "proxy.local", 8080, "", ""); p != nil {
		t.Fatalf("blank type must mean direct, got %#v", p)
	}
	p := newProxy("http", "proxy.local", 8080, "u", "p")
	if p == nil || p.Type != "http" || p.Host != "proxy.local" || p.Port != 8080 || p.Username != "u" || p.Password != "p" {
		t.Fatalf("unexpected proxy: %#v", p)
	}
	s := newProxy("socks5", "10.0.0.1", 1080, "", "")
	if s == nil || s.Type != "socks5" {
		t.Fatalf("unexpected socks5 proxy: %#v", s)
	}
}

func TestProxyDialHTTPConnect(t *testing.T) {
	target, stopTarget := startEcho(t)
	defer stopTarget()
	proxyAddr, stopProxy := startHTTPProxy(t, "", "")
	defer stopProxy()
	host, port := splitAddr(t, proxyAddr)
	roundTrip(t, newProxy("http", host, port, "", ""), target)
}

func TestProxyDialHTTPConnectWithAuth(t *testing.T) {
	target, stopTarget := startEcho(t)
	defer stopTarget()
	proxyAddr, stopProxy := startHTTPProxy(t, "alice", "s3cret")
	defer stopProxy()
	host, port := splitAddr(t, proxyAddr)
	roundTrip(t, newProxy("http", host, port, "alice", "s3cret"), target)
}

func TestProxyDialSOCKS5(t *testing.T) {
	target, stopTarget := startEcho(t)
	defer stopTarget()
	proxyAddr, stopProxy := startSocks5Proxy(t, "", "")
	defer stopProxy()
	host, port := splitAddr(t, proxyAddr)
	roundTrip(t, newProxy("socks5", host, port, "", ""), target)
}

func TestProxyDialSOCKS5WithAuth(t *testing.T) {
	target, stopTarget := startEcho(t)
	defer stopTarget()
	proxyAddr, stopProxy := startSocks5Proxy(t, "bob", "pw")
	defer stopProxy()
	host, port := splitAddr(t, proxyAddr)
	roundTrip(t, newProxy("socks5", host, port, "bob", "pw"), target)
}

func TestProxyDialErrors(t *testing.T) {
	ctx := context.Background()
	if _, err := proxyDial(nil, ctx, newProxy("gopher", "127.0.0.1", 1080, "", ""), "tcp", "example.com:443"); err == nil {
		t.Fatal("expected unsupported-type error")
	}
	if _, err := proxyDial(nil, ctx, newProxy("http", "", 0, "", ""), "tcp", "example.com:443"); err == nil {
		t.Fatal("expected missing host/port error")
	}
	if _, err := proxyDial(nil, ctx, newProxy("socks5", "127.0.0.1", 1, "", ""), "tcp", "example.com:443"); err == nil {
		t.Fatal("expected dial failure against a dead proxy")
	}
}

// splitAddr turns "127.0.0.1:1234" into host and port for newProxy.
func splitAddr(t *testing.T, addr string) (string, int) {
	t.Helper()
	h, p, err := net.SplitHostPort(addr)
	if err != nil {
		t.Fatalf("split %q: %v", addr, err)
	}
	var port int
	if _, err := fmt.Sscanf(p, "%d", &port); err != nil {
		t.Fatalf("port %q: %v", p, err)
	}
	return strings.TrimSpace(h), port
}
