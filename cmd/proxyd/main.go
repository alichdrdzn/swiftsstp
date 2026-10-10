// Command proxyd is a minimal upstream proxy for testing SwiftSSTP's
// "connect via a proxy" feature: it speaks HTTP CONNECT and SOCKS5 on one
// port (distinguished per connection by the first byte: 'C'/'G'/'P' = HTTP
// method line, 0x05 = SOCKS5). Optional basic auth for HTTP, optional
// RFC 1929 user/pass for SOCKS5. Dev/test tool, not a production proxy.
package main

import (
	"bufio"
	"encoding/base64"
	"flag"
	"io"
	"log"
	"net"
	"net/http"
	"sync"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:3128", "listen address")
	user := flag.String("user", "", "require this username (both protocols)")
	pass := flag.String("pass", "", "password for -user")
	flag.Parse()

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("proxyd on %s (auth=%v)", *listen, *user != "")
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handle(c, *user, *pass)
	}
}

func handle(c net.Conn, user, pass string) {
	defer c.Close()
	br := bufio.NewReader(c)
	first, err := br.Peek(1)
	if err != nil {
		return
	}
	if first[0] == 0x05 {
		socks5(c, br, user, pass)
		return
	}
	httpConnect(c, br, user, pass)
}

func dialTarget(addr string) (net.Conn, error) { return net.Dial("tcp", addr) }

func httpConnect(c net.Conn, br *bufio.Reader, user, pass string) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if req.Method != http.MethodConnect {
		// Plain GET: serve a tiny status page (proves HTTP proxying too).
		io.WriteString(c, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nproxyd ok\n")
		return
	}
	if user != "" {
		auth := req.Header.Get("Proxy-Authorization")
		want := "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
		if auth != want {
			io.WriteString(c, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n")
			return
		}
	}
	up, err := dialTarget(req.Host)
	if err != nil {
		io.WriteString(c, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
		log.Printf("CONNECT %s: %v", req.Host, err)
		return
	}
	defer up.Close()
	if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	log.Printf("CONNECT %s established", req.Host)
	relay(c, br, up)
}

func socks5(c net.Conn, br *bufio.Reader, user, pass string) {
	var hdr [2]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, int(hdr[1]))
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	if user != "" {
		hasUserPass := false
		for _, m := range methods {
			if m == 0x02 {
				hasUserPass = true
			}
		}
		if !hasUserPass {
			c.Write([]byte{0x05, 0xFF})
			return
		}
		c.Write([]byte{0x05, 0x02})
		var ah [2]byte
		if _, err := io.ReadFull(br, ah[:]); err != nil || ah[0] != 0x01 {
			return
		}
		u := make([]byte, int(ah[1]))
		io.ReadFull(br, u)
		var plen [1]byte
		io.ReadFull(br, plen[:])
		p := make([]byte, int(plen[0]))
		io.ReadFull(br, p)
		if string(u) != user || string(p) != pass {
			c.Write([]byte{0x01, 0x01})
			return
		}
		c.Write([]byte{0x01, 0x00})
	} else {
		c.Write([]byte{0x05, 0x00})
	}
	var req [4]byte
	if _, err := io.ReadFull(br, req[:]); err != nil || req[0] != 0x05 || req[1] != 0x01 {
		return
	}
	var host string
	switch req[3] {
	case 0x01:
		var a [4]byte
		io.ReadFull(br, a[:])
		host = net.IP(a[:]).String()
	case 0x04:
		var a [16]byte
		io.ReadFull(br, a[:])
		host = net.IP(a[:]).String()
	case 0x03:
		var l [1]byte
		io.ReadFull(br, l[:])
		b := make([]byte, int(l[0]))
		io.ReadFull(br, b)
		host = string(b)
	}
	var port [2]byte
	io.ReadFull(br, port[:])
	addr := net.JoinHostPort(host, itoa(int(port[0])<<8|int(port[1])))
	up, err := dialTarget(addr)
	if err != nil {
		c.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		log.Printf("SOCKS5 %s: %v", addr, err)
		return
	}
	defer up.Close()
	c.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	log.Printf("SOCKS5 %s established", addr)
	relay(c, br, up)
}

func relay(a net.Conn, abr *bufio.Reader, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); io.Copy(b, abr) }() // abr drains any buffered bytes first
	go func() { defer wg.Done(); io.Copy(a, b) }()
	wg.Wait()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [6]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

