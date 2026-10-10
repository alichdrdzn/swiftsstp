package mobile

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

// Live checks against the reference SoftEther server. They are skipped
// unless SSTP_LIVE=1 is set, so ordinary `go test ./...` never leaves the
// machine:
//
//	SSTP_LIVE=1 go test ./mobile/ -run Live -v
const (
	liveHost     = "18.130.233.223"
	liveUser     = "mohsen"
	livePassword = "mohsen123"
)

// primaryIP pulls local_ip out of a StatsJSON snapshot ("" when absent).
func primaryIP(stats string) string {
	var o struct {
		LocalIP string `json:"local_ip"`
	}
	_ = json.Unmarshal([]byte(stats), &o)
	return o.LocalIP
}

func liveEnabled(t *testing.T) {
	t.Helper()
	if os.Getenv("SSTP_LIVE") == "" {
		t.Skip("set SSTP_LIVE=1 to run against the reference server")
	}
}

func TestLiveClientDirect(t *testing.T) {
	liveEnabled(t)
	c := NewClient(liveHost, 443, "", liveUser, livePassword, TLSInsecure, nil, 0, 30, "", "", 0, "", "")
	if err := c.Connect(30000); err != nil {
		t.Fatalf("direct connect: %v", err)
	}
	defer c.Close()
	if primaryIP(c.StatsJSON()) == "" {
		t.Fatalf("no negotiated local_ip: %s", c.StatsJSON())
	}
}

func TestLiveClientThroughHTTPProxy(t *testing.T) {
	liveEnabled(t)
	proxyAddr, stop := startHTTPProxy(t, "alice", "s3cret")
	defer stop()
	host, port := splitAddr(t, proxyAddr)

	c := NewClient(liveHost, 443, "", liveUser, livePassword, TLSInsecure, nil, 0, 30, "http", host, port, "alice", "s3cret")
	if err := c.Connect(30000); err != nil {
		t.Fatalf("connect via HTTP CONNECT proxy: %v", err)
	}
	defer c.Close()
	stats := c.StatsJSON()
	if primaryIP(stats) == "" {
		t.Fatalf("no negotiated local_ip: %s", stats)
	}
	t.Logf("via HTTP proxy: %s", stats)
}

func TestLiveBundleThroughSOCKS5Proxy(t *testing.T) {
	liveEnabled(t)
	proxyAddr, stop := startSocks5Proxy(t, "bob", "pw")
	defer stop()
	host, port := splitAddr(t, proxyAddr)

	b := NewBundle(liveHost, 443, "", liveUser, livePassword, TLSInsecure, nil, 0, 30, 2, "socks5", host, port, "bob", "pw")
	if err := b.Connect(30000); err != nil {
		t.Fatalf("bundle connect via SOCKS5 proxy: %v", err)
	}
	defer b.Close()
	stats := b.StatsJSON()
	if primaryIP(stats) == "" {
		t.Fatalf("missing primary local_ip: %s", stats)
	}
	if !strings.Contains(stats, `"sessions":[{`) {
		t.Fatalf("missing sessions array: %s", stats)
	}
	// The secondary sessions fill in the background; wait for them so the
	// test proves N sessions really ride the proxy.
	var ready int
	for i := 0; i < 20; i++ {
		stats = b.StatsJSON()
		var o struct {
			Sessions []struct {
				Ready bool `json:"ready"`
			} `json:"sessions"`
		}
		_ = json.Unmarshal([]byte(stats), &o)
		if len(o.Sessions) == 0 {
			t.Fatalf("no sessions reported: %s", stats)
		}
		ready = 0
		for _, s := range o.Sessions {
			if s.Ready {
				ready++
			}
		}
		if ready == len(o.Sessions) {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if ready != 2 {
		t.Fatalf("only %d/2 sessions ready through the proxy: %s", ready, stats)
	}
	t.Logf("bundle via SOCKS5 proxy: %s", stats)
}
