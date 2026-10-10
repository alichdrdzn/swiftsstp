// Command sstpcli is the Sstpflash reference client: it dials an SSTP
// server, brings the inner IP packets onto a local TUN device, and prints
// usage counters. It exists to prove interop end-to-end and to give the
// desktop/android shells a known-good engine call sequence.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"

	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/unix"

	"sstpflash/multi"
	"sstpflash/tunnel"
)

func main() {
	var (
		server   = flag.String("server", "", "SSTP server host or IP")
		port     = flag.Int("port", 443, "server port")
		sni      = flag.String("sni", "", "TLS SNI override (default: server)")
		user     = flag.String("user", "", "username")
		pass     = flag.String("pass", os.Getenv("SSTP_PASS"), "password (or SSTP_PASS env)")
		insec    = flag.Bool("insecure", false, "skip TLS certificate verification")
		proxy    = flag.String("proxy", "", "upstream proxy for the outer transport: http://[user:pass@]host:port or socks5://[user:pass@]host:port")
		caFile   = flag.String("ca", "", "PEM file with which to verify the server")
		mtu      = flag.Int("mtu", 0, "inner MTU (0 = default 1400)")
		tunName  = flag.String("tun", "sstp0", "TUN device name (empty = no device, print result only)")
		routes   = flag.String("route", "", "comma-separated CIDRs to route into the tunnel (e.g. 1.1.1.1/32)")
		nat      = flag.Bool("nat", false, "MASQUERADE tunnel-bound traffic (requires root)")
		tunnels  = flag.Int("tunnels", 1, "parallel SSTP sessions to bundle (1-8, aggregate throughput)")
		stats    = flag.Duration("stats", 5*time.Second, "stats print interval (0 = off)")
		duration = flag.Duration("duration", 0, "exit after this long (0 = until signal)")
	)
	flag.Parse()

	if *server == "" || *user == "" || *pass == "" {
		flag.Usage()
		log.Fatal("need -server, -user and -pass/SSTP_PASS")
	}

	cfg := tunnel.Config{
		Server:   *server,
		Port:     *port,
		SNI:      *sni,
		Username: *user,
		Password: *pass,
		MTU:      *mtu,
	}
	if *proxy != "" {
		pc, err := parseProxyURL(*proxy)
		if err != nil {
			log.Fatalf("-proxy: %v", err)
		}
		cfg.Proxy = pc
		log.Printf("via %s proxy %s:%d", pc.Type, pc.Host, pc.Port)
	}
	switch {
	case *insec:
		cfg.TLSMode = tunnel.TLSInsecure
	case *caFile != "":
		pem, err := os.ReadFile(*caFile)
		if err != nil {
			log.Fatalf("read CA file: %v", err)
		}
		cfg.TLSMode = tunnel.TLSCustomCA
		cfg.CACerts = pem
	}

	ctx := context.Background()
	if *duration > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, *duration)
		defer cancel()
	}

	// tunnelIO is the packet-level surface both a single Session and a
	// multi-session Bundle provide.
	type tunnelIO interface {
		ReadIP([]byte) (int, error)
		WriteIP([]byte) error
		Counters() (uint64, uint64)
		Wait() error
		Close() error
	}

	log.Printf("dialing %s:%d (sni=%q insecure=%v tunnels=%d)...", *server, *port, cfg.SNI, *insec, *tunnels)
	start := time.Now()
	var sess tunnelIO
	var res tunnel.Result
	if *tunnels > 1 {
		b, r, err := multi.Dial(ctx, multi.Config{
			Sessions: *tunnels,
			Dial: func(ctx context.Context) (multi.Session, tunnel.Result, error) {
				s, r, err := tunnel.Dial(ctx, cfg)
				if err != nil {
					return nil, r, err
				}
				return s, r, nil
			},
		})
		if err != nil {
			if tunnel.IsAuthError(err) {
				log.Fatalf("AUTH REJECTED: %v", err)
			}
			log.Fatalf("bundle: %v", err)
		}
		sess, res = b, r
	} else {
		s, r, err := tunnel.Dial(ctx, cfg)
		if err != nil {
			if tunnel.IsAuthError(err) {
				log.Fatalf("AUTH REJECTED: %v", err)
			}
			log.Fatalf("dial: %v", err)
		}
		sess, res = s, r
	}
	defer sess.Close()
	log.Printf("UP in %s: local=%s peer=%s dns=%v mtu=%d certsha256=%s",
		time.Since(start).Round(time.Millisecond), res.LocalIP, res.PeerIP, res.DNS, res.MTU, res.ServerCertSHA256[:16])

	var dev *os.File
	if *tunName != "" {
		f, err := openTUN(*tunName)
		if err != nil {
			log.Fatalf("open TUN: %v", err)
		}
		dev = f
		defer dev.Close()

		run("ip", "addr", "add", fmt.Sprintf("%s/32", res.LocalIP), "peer", fmt.Sprintf("%s/32", res.PeerIP), "dev", *tunName)
		run("ip", "link", "set", "dev", *tunName, "up", "mtu", fmt.Sprint(res.MTU))
		for _, r := range strings.Split(*routes, ",") {
			if r != "" {
				run("ip", "route", "add", r, "dev", *tunName)
			}
		}
		if *nat {
			run("sysctl", "-qw", "net.ipv4.ip_forward=1")
			run("iptables", "-t", "nat", "-A", "POSTROUTING", "-o", *tunName, "-j", "MASQUERADE")
		}

		// pump: TUN <-> tunnel
		go func() {
			buf := make([]byte, 65535)
			for {
				n, err := dev.Read(buf)
				if err != nil {
					return
				}
				if err := sess.WriteIP(buf[:n]); err != nil {
					log.Printf("write to tunnel: %v", err)
					return
				}
			}
		}()
		go func() {
			buf := make([]byte, 65535)
			for {
				n, err := sess.ReadIP(buf)
				if err != nil {
					return
				}
				if _, err := dev.Write(buf[:n]); err != nil {
					log.Printf("write to TUN: %v", err)
					return
				}
			}
		}()
		log.Printf("device %s up; routes: %s", *tunName, *routes)
	}

	if *stats > 0 && *tunName != "" {
		go func() {
			t := time.NewTicker(*stats)
			defer t.Stop()
			var lastUp, lastDown uint64
			for range t.C {
				up, down := sess.Counters()
				log.Printf("stats: up=%d B (%.1f KB/s) down=%d B (%.1f KB/s)",
					up, float64(up-lastUp)/(*stats).Seconds()/1024,
					down, float64(down-lastDown)/(*stats).Seconds()/1024)
				lastUp, lastDown = up, down
			}
		}()
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	waitCh := make(chan error, 1)
	go func() { waitCh <- sess.Wait() }()

	select {
	case <-sig:
		log.Printf("signal, closing")
	case err := <-waitCh:
		log.Printf("session ended: %v", err)
	case <-ctx.Done():
		log.Printf("duration reached")
	}
	up, down := sess.Counters()
	log.Printf("final: up=%d B down=%d B", up, down)
}

// parseProxyURL turns http://user:pass@host:port or socks5://host:port into
// a tunnel.ProxyConfig.
func parseProxyURL(raw string) (*tunnel.ProxyConfig, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	var typ string
	switch u.Scheme {
	case "http", "https":
		typ = "http"
	case "socks5", "socks5h":
		typ = "socks5"
	default:
		return nil, fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	port, err := strconv.Atoi(u.Port())
	if err != nil || port == 0 {
		if port == 0 {
			port = 443
		} else {
			return nil, fmt.Errorf("bad port %q", u.Port())
		}
	}
	pc := &tunnel.ProxyConfig{Type: typ, Host: u.Hostname(), Port: port}
	if u.User != nil {
		pc.Username = u.User.Username()
		pc.Password, _ = u.User.Password()
	}
	return pc, nil
}

// openTUN attaches to a TUN device via the TUNSETIFF ioctl (no persistence;
// the device disappears when the returned fd closes).
func openTUN(name string) (*os.File, error) {
	fd, err := unix.Open("/dev/net/tun", unix.O_RDWR, 0)
	if err != nil {
		return nil, err
	}
	type ifreq struct {
		Name  [unix.IFNAMSIZ]byte
		Flags uint16
		_     [22]byte
	}
	var ifr ifreq
	copy(ifr.Name[:], name)
	ifr.Flags = unix.IFF_TUN | unix.IFF_NO_PI
	_, _, errno := unix.Syscall(unix.SYS_IOCTL, uintptr(fd), uintptr(unix.TUNSETIFF), uintptr(unsafe.Pointer(&ifr)))
	if errno != 0 {
		unix.Close(fd)
		return nil, fmt.Errorf("TUNSETIFF %s: %v", name, errno)
	}
	return os.NewFile(uintptr(fd), name), nil
}

func run(name string, args ...string) {
	out, err := exec.Command(name, args...).CombinedOutput()
	if err != nil {
		log.Printf("%s %s: %v (%s)", name, strings.Join(args, " "), err, strings.TrimSpace(string(out)))
	}
}
