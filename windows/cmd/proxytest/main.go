// Command proxytest boots the engine + proxy data plane against a real SSTP
// server on ANY OS (it deliberately avoids Windows-only packages) so the
// v2rayN-style local proxy path can be live-verified before cross-compiling
// the Windows client:
//
//	go run ./cmd/proxytest -server 18.130.233.223 -user mohsen -pass mohsen123 -insecure
//	curl -s --socks5-hostname 127.0.0.1:10808 https://1.1.1.1/cdn-cgi/trace
//	curl -s -x http://127.0.0.1:10809 https://1.1.1.1/cdn-cgi/trace
package main

import (
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"slipstream/usage"
	"slipstream/windows/internal/engine"
	"slipstream/windows/internal/proxy"
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
	)
	flag.Parse()
	log.SetFlags(log.Ltime | log.Lmicroseconds)

	// Exercises slipstream/usage write-through persistence in cwd.
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
	log.Printf("READY socks5=127.0.0.1:%d http=127.0.0.1:%d", *socks, *httpP)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	select {
	case <-sig:
		log.Printf("signal: shutting down")
	case <-time.After(*duration):
		log.Printf("duration elapsed: shutting down")
	}
	mgr.Disconnect()
	mgr.Close()
	log.Printf("bye")
}

func logStatus(s engine.Status) {
	if s.State == "connected" {
		log.Printf("status=%s mode=%s local=%s up=%d(%d B/s) down=%d(%d B/s) total=%d/%d sessions=%d proxy_on=%v",
			s.State, s.Mode, s.LocalIP, s.Up, s.RateUp, s.Down, s.RateDown,
			s.TotalUp, s.TotalDown, s.Sessions, s.SystemProxyOn)
		return
	}
	log.Printf("status=%s state=%s attempts=%d err=%q", s.State, s.Mode, s.ReconnectAttempts, s.LastError)
}
