// Package proxy is the v2rayN-style local proxy data plane: a gVisor
// netstack fed by the SSTP session plus SOCKS5 and HTTP listeners on
// 127.0.0.1. It is OS-neutral Go so the whole path (local client -> listener
// -> netstack -> tunnel -> server egress) can be live-tested on Linux.
package proxy

import (
	"fmt"
	"net"
	"sync"

	"sstpflash/tunnel"
	"sstpflash/windows/internal/dataplane"
)

// Config configures the local listeners.
type Config struct {
	SOCKSPort int // default dataplane.DefaultSocksPort (10808)
	HTTPPort  int // default dataplane.DefaultHTTPPort (10809)
	Logf      func(format string, args ...any)
}

// Proxy implements dataplane.DataPlane for proxy mode. Listeners are bound
// on the first Start and stay up across auto-reconnects; Close (user
// disconnect) releases them and is followed by a fresh bind on next Start.
type Proxy struct {
	cfg Config

	mu   sync.Mutex
	vn   *virtualNet // active netstack; nil while the tunnel is down
	sess *tunnel.Session
	res  tunnel.Result

	socksLn     net.Listener
	httpLn      net.Listener
	boundSOCKS  int
	boundHTTP   int
	conns    map[net.Conn]struct{}
	closing  bool // true while Close() is tearing listeners down
	wg       sync.WaitGroup // accept loops + in-flight handlers
}

// New builds the proxy data plane. Ports fall back to 10808/10809.
func New(cfg Config) *Proxy {
	if cfg.SOCKSPort <= 0 {
		cfg.SOCKSPort = dataplane.DefaultSocksPort
	}
	if cfg.HTTPPort <= 0 {
		cfg.HTTPPort = dataplane.DefaultHTTPPort
	}
	if cfg.Logf == nil {
		cfg.Logf = func(string, ...any) {}
	}
	return &Proxy{cfg: cfg, conns: map[net.Conn]struct{}{}}
}

// Ports reports the bound listener ports (useful after binding to port 0).
func (p *Proxy) Ports() (socks, http int) { return p.cfg.SOCKSPort, p.cfg.HTTPPort }

// Start binds listeners if needed and attaches a fresh netstack to sess.
func (p *Proxy) Start(sess *tunnel.Session, res tunnel.Result, cfg dataplane.Config) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	// Profile may carry different listener ports than the previous one:
	// rebind when they changed.
	if cfg.SOCKSPort > 0 {
		p.cfg.SOCKSPort = cfg.SOCKSPort
	}
	if cfg.HTTPPort > 0 {
		p.cfg.HTTPPort = cfg.HTTPPort
	}
	if p.socksLn != nil && (p.boundSOCKS != p.cfg.SOCKSPort || p.boundHTTP != p.cfg.HTTPPort) {
		_ = p.socksLn.Close()
		_ = p.httpLn.Close()
		p.socksLn, p.httpLn = nil, nil
	}
	if p.socksLn == nil {
		p.closing = false
		socksLn, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p.cfg.SOCKSPort))
		if err != nil {
			return fmt.Errorf("proxy: socks listen 127.0.0.1:%d: %w", p.cfg.SOCKSPort, err)
		}
		httpLn, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p.cfg.HTTPPort))
		if err != nil {
			_ = socksLn.Close()
			return fmt.Errorf("proxy: http listen 127.0.0.1:%d: %w", p.cfg.HTTPPort, err)
		}
		p.socksLn, p.httpLn = socksLn, httpLn
		p.boundSOCKS, p.boundHTTP = p.cfg.SOCKSPort, p.cfg.HTTPPort
		p.wg.Add(2)
		go p.acceptLoop(socksLn, p.handleSOCKS)
		go p.acceptLoop(httpLn, p.handleHTTP)
		p.cfg.Logf("proxy: socks5 on 127.0.0.1:%d, http on 127.0.0.1:%d", p.cfg.SOCKSPort, p.cfg.HTTPPort)
	}
	// Replace any stack left from a previous session.
	if p.vn != nil {
		p.vn.close()
		p.vn = nil
	}
	vn, err := newVirtualNet(sess, res, cfg.DNS, cfg.IPv4, cfg.IPv6, p.cfg.Logf)
	if err != nil {
		p.sess = nil
		return err
	}
	p.vn, p.sess = vn, sess
	p.res = res
	p.cfg.Logf("proxy: netstack up, local %s dns %v mtu %d", res.LocalIP, cfg.DNS, res.MTU)
	return nil
}

// Stop tears down the netstack for the current session; listeners stay up.
func (p *Proxy) Stop() {
	p.mu.Lock()
	vn := p.vn
	p.vn, p.sess = nil, nil
	p.mu.Unlock()
	if vn != nil {
		vn.close()
	}
}

// Close stops and releases listeners; safe to Start again afterwards.
func (p *Proxy) Close() error {
	p.Stop()
	p.mu.Lock()
	p.closing = true
	socksLn, httpLn := p.socksLn, p.httpLn
	p.socksLn, p.httpLn = nil, nil
	conns := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		conns = append(conns, c)
	}
	p.conns = map[net.Conn]struct{}{}
	p.mu.Unlock()
	if socksLn != nil {
		_ = socksLn.Close()
	}
	if httpLn != nil {
		_ = httpLn.Close()
	}
	for _, c := range conns {
		_ = c.Close()
	}
	p.wg.Wait()
	return nil
}

// current returns the live stack + session (either may be nil).
func (p *Proxy) current() (*virtualNet, *tunnel.Session) {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.vn, p.sess
}

func (p *Proxy) acceptLoop(ln net.Listener, handler func(net.Conn)) {
	defer p.wg.Done()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		p.mu.Lock()
		if p.closing {
			p.mu.Unlock()
			_ = c.Close()
			return
		}
		p.conns[c] = struct{}{}
		p.mu.Unlock()
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			defer func() {
				p.mu.Lock()
				delete(p.conns, c)
				p.mu.Unlock()
				_ = c.Close()
			}()
			handler(c)
		}()
	}
}
