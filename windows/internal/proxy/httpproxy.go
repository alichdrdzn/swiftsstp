package proxy

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"net/url"
	"strings"
	"time"
)

const maxHeaderBytes = 64 << 10

// handleHTTP serves the HTTP proxy inbound: absolute-URI plain requests
// (GET/POST/...) and CONNECT tunnels.
func (p *Proxy) handleHTTP(c net.Conn) {
	br := bufio.NewReaderSize(c, 64<<10)
	_ = c.SetDeadline(time.Now().Add(20 * time.Second))

	reqLine, err := br.ReadString('\n')
	if err != nil || len(reqLine) < 4 {
		return
	}
	parts := strings.SplitN(strings.TrimRight(reqLine, "\r\n"), " ", 3)
	if len(parts) < 3 {
		writeHTTPError(c, 400, "Bad Request")
		return
	}
	method, target, version := parts[0], parts[1], parts[2]

	headers, err := readHTTPHeaders(br)
	if err != nil {
		writeHTTPError(c, 400, "Bad Request")
		return
	}

	vn, _ := p.current()
	if vn == nil {
		writeHTTPError(c, 503, "Tunnel Not Connected")
		return
	}

	if method == "CONNECT" {
		host, port, err := splitHostPort(target)
		if err != nil {
			writeHTTPError(c, 400, "Bad Request")
			return
		}
		remote := p.dialThroughTunnel(vn, c, host, port)
		if remote == nil {
			return
		}
		if _, err := c.Write([]byte("HTTP/1.1 200 Connection established\r\n\r\n")); err != nil {
			_ = remote.Close()
			return
		}
		_ = c.SetDeadline(time.Time{})
		bridge(c, br, remote)
		return
	}

	// Plain HTTP: rewrite the absolute URI to origin-form and forward.
	u, host, port, err := parseAbsoluteTarget(target, headers)
	if err != nil {
		writeHTTPError(c, 400, "Bad Request")
		return
	}
	remote := p.dialThroughTunnel(vn, c, host, port)
	if remote == nil {
		return
	}
	_ = c.SetDeadline(time.Time{})

	path := target
	if u != nil {
		path = u.RequestURI()
		if path == "" {
			path = "/"
		}
	}
	var out strings.Builder
	fmt.Fprintf(&out, "%s %s %s\r\n", method, path, version)
	for _, h := range headers {
		lower := strings.ToLower(h.Name)
		if lower == "proxy-connection" || lower == "proxy-authorization" || lower == "connection" {
			continue
		}
		fmt.Fprintf(&out, "%s: %s\r\n", h.Name, h.Value)
	}
	out.WriteString("\r\n")
	if _, err := remote.Write([]byte(out.String())); err != nil {
		_ = remote.Close()
		return
	}
	bridge(c, br, remote)
}

type httpHeader struct{ Name, Value string }

func readHTTPHeaders(br *bufio.Reader) ([]httpHeader, error) {
	var headers []httpHeader
	total := 0
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, err
		}
		total += len(line)
		if total > maxHeaderBytes {
			return nil, fmt.Errorf("headers too large")
		}
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			return headers, nil
		}
		name, value, ok := strings.Cut(line, ":")
		if !ok {
			return nil, fmt.Errorf("bad header line")
		}
		headers = append(headers, httpHeader{Name: strings.TrimSpace(name), Value: strings.TrimSpace(value)})
	}
}

// parseAbsoluteTarget extracts host/port from an absolute URI (or the Host
// header for origin-form requests).
func parseAbsoluteTarget(target string, headers []httpHeader) (*url.URL, string, int, error) {
	if strings.Contains(target, "://") {
		u, err := url.Parse(target)
		if err != nil {
			return nil, "", 0, err
		}
		host := u.Hostname()
		port := 80
		if p := u.Port(); p != "" {
			fmt.Sscanf(p, "%d", &port)
		} else if u.Scheme == "https" {
			port = 443
		}
		if host == "" {
			return nil, "", 0, fmt.Errorf("no host")
		}
		return u, host, port, nil
	}
	// Origin-form: need Host header.
	for _, h := range headers {
		if strings.EqualFold(h.Name, "Host") {
			host, port, err := splitHostPort(h.Value)
			if err != nil {
				return nil, "", 0, err
			}
			if port == 0 {
				port = 80
			}
			return nil, host, port, nil
		}
	}
	return nil, "", 0, fmt.Errorf("no host")
}

// splitHostPort parses "host:port", "host" and "[v6]:port".
func splitHostPort(s string) (string, int, error) {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, "[") {
		end := strings.Index(s, "]")
		if end < 0 {
			return "", 0, fmt.Errorf("bad v6 literal")
		}
		host := s[1:end]
		rest := s[end+1:]
		if strings.HasPrefix(rest, ":") {
			var port int
			if _, err := fmt.Sscanf(rest[1:], "%d", &port); err != nil {
				return "", 0, fmt.Errorf("bad port")
			}
			return host, port, nil
		}
		return host, 0, nil
	}
	if i := strings.LastIndex(s, ":"); i > 0 {
		var port int
		if _, err := fmt.Sscanf(s[i+1:], "%d", &port); err != nil {
			return "", 0, fmt.Errorf("bad port")
		}
		return s[:i], port, nil
	}
	return s, 0, nil
}

// dialThroughTunnel resolves and dials, writing an HTTP error response to
// the client on failure. Returns nil on failure.
func (p *Proxy) dialThroughTunnel(vn *virtualNet, c net.Conn, host string, port int) net.Conn {
	ip, err := vn.lookupIP(host)
	if err != nil {
		p.cfg.Logf("proxy: http resolve %q: %v", host, err)
		writeHTTPError(c, 502, "Bad Gateway")
		return nil
	}
	if port == 0 {
		port = 80
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	remote, err := vn.dialTCP(ctx, ip, port)
	if err != nil {
		p.cfg.Logf("proxy: http connect %s:%d: %v", ip, port, err)
		writeHTTPError(c, 502, "Bad Gateway")
		return nil
	}
	return remote
}

func writeHTTPError(c net.Conn, code int, msg string) {
	_, _ = fmt.Fprintf(c, "HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", code, msg)
}
