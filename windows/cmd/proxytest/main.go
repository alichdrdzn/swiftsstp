// Command proxytest boots the engine + proxy data plane against a real SSTP
// server on ANY OS (it deliberately avoids Windows-only packages) so the
// v2rayN-style local proxy path can be live-verified before cross-compiling
// the Windows client:
//
//	go run ./cmd/proxytest -server 18.130.233.223 -user mohsen -pass mohsen123 -insecure
//	curl -s --socks5-hostname 127.0.0.1:10808 https://1.1.1.1/cdn-cgi/trace
//	curl -s -x http://127.0.0.1:10809 https://1.1.1.1/cdn-cgi/trace
//
// New feature flags (per-profile upstream proxy + 1..8 bundled tunnels):
//
//	go run ./cmd/proxyd -listen 127.0.0.1:3128 -user proxyuser -pass proxypass
//	go run ./cmd/proxytest -tunnels 3 -proxy socks5://proxyuser:proxypass@127.0.0.1:3128
//	go run ./cmd/proxytest -tunnels 3 -proxy http://proxyuser:proxypass@127.0.0.1:3128
package main

import (
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"sstpflash/usage"
	"sstpflash/windows/internal/engine"
	"sstpflash/windows/internal/proxy"
)

func main() {
	var (
		server   = flag.String("server", "18.130.233.223", "SSTP server host")
		port     = flag.Int("port", 443, "SSTP port")
		user     = flag.String("user", "mohsen", "username")
		pass     = flag.String("pass", "mohsen123", "password")
		insecure = flag.Bool("insecure", true, "skip TLS verification")
		socks    = flag.Int("socks", 10808, "SOCKS5 listen port")
		httpP    = flag.Int("http", 10809, "HTTP proxy listen port")
		duration = flag.Duration("duration", 10*time.Minute, "exit after this long")
		proxyURL = flag.String("proxy", "", "upstream proxy for the OUTER transport: http://[user:pass@]host:port or socks5://[user:pass@]host:port")
		tunnels  = flag.Int("tunnels", 1, "parallel SSTP sessions to bundle (1..8)")
	)
	flag.Parse()
	log.SetFlags(log.Ltime | log.Lmicroseconds)

	// Exercises sstpflash/usage write-through persistence in cwd.
	u, _ := usage.Open("usage-stats.json")

	pxy := proxy.New(proxy.Config{
		SOCKSPort: *socks,
		HTTPPort:  *httpP,
		Logf:      log.Printf,
	})

	mgr := engine.NewManager(engine.Options{
		Proxy:    pxy,
		Usage:    u,
		OnStatus: logStatus,
		Logf:     log.Printf,
	})

	p := engine.NewProfile()
	p.ID = "proxytest"
	p.Name = "proxytest"
	p.Host = *server
	p.Port = *port
	p.Username = *user
	p.Password = *pass
	if *insecure {
		p.TLSMode = "insecure"
	}
	p.Tunnels = *tunnels
	if *proxyURL != "" {
		px, err := parseProxyURL(*proxyURL)
		if err != nil {
			log.Fatalf("-proxy: %v", err)
		}
		p.Proxy = px
	}
	if err := p.Validate(); err != nil {
		log.Fatalf("profile: %v", err)
	}
	log.Printf("profile: %d tunnel(s), upstream proxy %s", p.TunnelCount(), proxyLabel(p))

	if err := mgr.Connect(p, engine.ModeProxy); err != nil {
		log.Fatalf("connect: %v", err)
	}

	deadline := time.Now().Add(90 * time.Second)
	for time.Now().Before(deadline) {
		s := mgr.Status()
		switch s.State {
		case "connected":
			log.Printf("UP: local=%s peer=%s dns=%v mtu=%d cert=%s",
				s.LocalIP, s.PeerIP, s.DNS, s.MTU, s.CertFingerprint)
		case "auth_error":
			log.Fatalf("AUTH REJECTED: %s", s.LastError)
		case "disconnected", "error":
			// with auto_reconnect the loop keeps retrying; log only
		}
		if s.State == "connected" {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	if s := mgr.Status(); s.State != "connected" {
		log.Fatalf("tunnel did not come up; state=%s err=%s", s.State, s.LastError)
	}

	// The first session is up synchronously; the rest fill in the
	// background. Wait for them so the per-session output is meaningful.
	waitForTunnels(mgr, p.TunnelCount(), 60*time.Second)

	log.Printf("READY socks5=127.0.0.1:%d http=127.0.0.1:%d", *socks, *httpP)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	select {
	case <-sig:
		log.Printf("signal: shutting down")
	case <-time.After(*duration):
		log.Printf("duration elapsed: shutting down")
	}
	// Final per-session snapshot while the bundle is still up: proves the
	// per-session counters the UI chips read out of bundle.StatsJSON.
	if s := mgr.Status(); len(s.TunnelSessions) > 0 {
		log.Printf("final tunnels %d/%d %s",
			readyCount(s.TunnelSessions), p.TunnelCount(), sessionsBrief(s.TunnelSessions))
	}
	mgr.Disconnect()
	mgr.Close()
	log.Printf("bye")
}

// waitForTunnels polls until every slot reports ready (or the deadline),
// logging each per-session snapshot it has not printed before.
func waitForTunnels(mgr *engine.Manager, want int, timeout time.Duration) {
	deadline := time.Now().Add(timeout)
	last := ""
	readyN := 0
	for time.Now().Before(deadline) {
		s := mgr.Status()
		brief := sessionsBrief(s.TunnelSessions)
		if brief != last {
			log.Printf("tunnels %d/%d: %s", readyCount(s.TunnelSessions), want, brief)
			last = brief
		}
		readyN = readyCount(s.TunnelSessions)
		if readyN >= want && len(s.TunnelSessions) >= want {
			return
		}
		if s.State != "connected" && s.State != "connecting" && s.State != "reconnecting" {
			log.Printf("tunnels: state=%s err=%q", s.State, s.LastError)
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	log.Printf("tunnels: only %d/%d ready after %s (continuing)", readyN, want, timeout)
}

func readyCount(ts []engine.TunnelStat) int {
	n := 0
	for _, t := range ts {
		if t.Ready {
			n++
		}
	}
	return n
}

// sessionsBrief renders "[idx ip ready up/down]" per session.
func sessionsBrief(ts []engine.TunnelStat) string {
	if len(ts) == 0 {
		return "(none)"
	}
	parts := make([]string, 0, len(ts))
	for _, t := range ts {
		state := "down"
		if t.Ready {
			state = "ready"
		}
		parts = append(parts, fmt.Sprintf("[%d %s %s up=%d down=%d]",
			t.Index, orDash(t.LocalIP), state, t.Up, t.Down))
	}
	return strings.Join(parts, " ")
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

// parseProxyURL turns http://[user:pass@]host:port (or socks5://...) into
// the profile's proxy block.
func parseProxyURL(s string) (engine.Proxy, error) {
	u, err := url.Parse(s)
	if err != nil {
		return engine.Proxy{}, fmt.Errorf("parse %q: %w", s, err)
	}
	var px engine.Proxy
	switch u.Scheme {
	case "http":
		px.Type = "http"
	case "socks5", "socks5h", "socks":
		px.Type = "socks5"
	case "https":
		return engine.Proxy{}, fmt.Errorf("TLS to the proxy itself is not supported (use http://)")
	default:
		return engine.Proxy{}, fmt.Errorf("unsupported scheme %q (want http or socks5)", u.Scheme)
	}
	host := u.Hostname()
	portStr := u.Port()
	if host == "" || portStr == "" {
		return engine.Proxy{}, fmt.Errorf("proxy URL needs host:port, e.g. %s://127.0.0.1:3128", u.Scheme)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return engine.Proxy{}, fmt.Errorf("bad proxy port %q", portStr)
	}
	px.Host, px.Port = host, port
	if u.User != nil {
		px.Username = u.User.Username()
		px.Password, _ = u.User.Password()
	}
	return px, nil
}

func proxyLabel(p engine.Profile) string {
	if l := p.Proxy.Label(); l != "" {
		return l
	}
	return "direct"
}

func logStatus(s engine.Status) {
	if s.State == "connected" {
		px := s.Proxy
		if px == "" {
			px = "direct"
		}
		log.Printf("status=%s mode=%s local=%s up=%d(%d B/s) down=%d(%d B/s) total=%d/%d sessions=%d tunnels=%d proxy=%s",
			s.State, s.Mode, s.LocalIP, s.Up, s.RateUp, s.Down, s.RateDown,
			s.TotalUp, s.TotalDown, s.Sessions, s.Tunnels, px)
		return
	}
	log.Printf("status=%s state=%s attempts=%d err=%q", s.State, s.Mode, s.ReconnectAttempts, s.LastError)
}
