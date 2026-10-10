//go:build windows

// Package wintunmgr is the whole-device ("tunnel") data plane: it embeds
// wintun.dll, creates a Wintun adapter, configures address/MTU/DNS/routes
// (host route to the SSTP server via the ORIGINAL default gateway BEFORE
// installing default routes) and pumps packets between the adapter and the
// tunnel session.
//
// Everything here requires elevation (adapter creation + route/DNS changes).
package wintunmgr

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/tun"

	"sstpflash/tunnel"
	"sstpflash/windows/assets"
	"sstpflash/windows/internal/dataplane"
)

// Manager implements dataplane.DataPlane for whole-device mode.
type Manager struct {
	logf func(string, ...any)

	mu      sync.Mutex
	dev     tun.Device
	undo    []undoOp
	stop    chan struct{}
	wg      sync.WaitGroup
	started bool
}

// New builds the data plane.
func New(logf func(string, ...any)) *Manager {
	if logf == nil {
		logf = func(string, ...any) {}
	}
	return &Manager{logf: logf}
}

var (
	dllOnce sync.Once
	dllErr  error
	dllPath string
)

// ensureDLL materialises the embedded wintun.dll under the user cache dir
// and pre-loads it with an absolute path. The golang.zx2c4.com/wintun
// binding later calls LoadLibraryEx("wintun.dll", SEARCH_APPLICATION_DIR|
// SEARCH_SYSTEM32), which picks up an already-loaded module of that base
// name; the cache dir also keeps the DLL next to nothing else.
func ensureDLL() error {
	dllOnce.Do(func() {
		base, err := os.UserCacheDir()
		if err != nil {
			base = os.TempDir()
		}
		dir := filepath.Join(base, "Sstpflash")
		if err := os.MkdirAll(dir, 0o700); err != nil {
			dllErr = fmt.Errorf("wintun dir: %w", err)
			return
		}
		path := filepath.Join(dir, "wintun.dll")
		if fi, err := os.Stat(path); err != nil || fi.Size() != int64(len(assets.WintunDLL)) {
			if err := os.WriteFile(path, assets.WintunDLL, 0o600); err != nil {
				dllErr = fmt.Errorf("write wintun.dll: %w", err)
				return
			}
		}
		dllPath = path
		h, err := windows.LoadLibrary(path)
		if err != nil {
			dllErr = fmt.Errorf("preload wintun.dll: %w", err)
			return
		}
		_ = h // intentionally leaked: wintun needs it for process lifetime
	})
	return dllErr
}

// Start wires everything for a live session. sess is the engine's Stream —
// normally the multi.Bundle, so the adapter carries every bundled session's
// traffic (striped by flow affinity inside the bundle) while being
// configured from the primary's Result.
func (m *Manager) Start(sess dataplane.Stream, res tunnel.Result, cfg dataplane.Config) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.started {
		m.stopLocked()
	}

	if !windows.GetCurrentProcessToken().IsElevated() {
		return fmt.Errorf("whole-device mode requires administrator privileges (elevate and retry)")
	}
	if err := ensureDLL(); err != nil {
		return err
	}

	// Resolve the server and capture the original default gateway while
	// routing is untouched, then pin the server BEFORE any default route.
	serverIP := resolveServer(cfg.ServerHost)
	gw := defaultGateway4()
	m.logf("wintun: server %s -> %s, original gateway %v", cfg.ServerHost, serverIP, gw)
	if op, err := addHostRoute(serverIP, gw); err != nil {
		// Not fatal on its own, but the tunnel will black-hole itself
		// once the default route flips; surface it loudly.
		m.logf("wintun: WARNING host route failed: %v", err)
	} else {
		m.undo = append(m.undo, op)
		m.logf("wintun: %s", op.desc)
	}

	// Adapter up (reuses an existing "Sstpflash" adapter if present).
	dev, err := tun.CreateTUN(ifName, res.MTU)
	if err != nil {
		m.runUndoLocked()
		return fmt.Errorf("create wintun adapter: %w", err)
	}
	m.dev = dev

	// Address/MTU/DNS/routes.
	if ops, err := m.configure(res, cfg); err != nil {
		m.runUndoLocked()
		_ = dev.Close()
		m.dev = nil
		return err
	} else {
		m.undo = append(m.undo, ops...)
	}

	// Packet pumps.
	stop := make(chan struct{})
	m.stop = stop
	m.wg.Add(2)
	go m.tunToTunnel(dev, sess, stop)
	go m.tunnelToTun(dev, sess, stop)

	m.started = true
	m.logf("wintun: adapter %q up, local %s peer %s mtu %d", ifName, res.LocalIP, res.PeerIP, res.MTU)
	return nil
}

// configure applies address, metric, default routes and DNS; returns undos.
func (m *Manager) configure(res tunnel.Result, cfg dataplane.Config) ([]undoOp, error) {
	local := res.LocalIP
	switch {
	case local.To4() != nil && cfg.IPv4:
		ops, err := configureAddress4(local.To4(), res.MTU)
		if err != nil {
			return nil, fmt.Errorf("wintun address: %w", err)
		}
		setInterfaceMetric1()
		if err := addDefaultRoute4(); err != nil {
			return ops, err
		}
		ops = append(ops, undoOp{desc: "default route 0.0.0.0/0", fn: deleteDefaultRoute4})
		if op, err := setDNS4(cfg.DNS); err != nil {
			return ops, fmt.Errorf("wintun dns: %w", err)
		} else {
			ops = append(ops, op)
			m.logf("wintun: dns %v via %s", cfg.DNS, op.desc)
		}
		return ops, nil
	default:
		// IPv6-only negotiation (Result.LocalIP is a v6 address).
		ops, err := configureAddress6(local, res.MTU)
		if err != nil {
			return nil, fmt.Errorf("wintun address v6: %w", err)
		}
		if err := addDefaultRoute6(); err != nil {
			return ops, err
		}
		ops = append(ops, undoOp{desc: "default route ::/0", fn: deleteDefaultRoute6})
		if op, err := setDNS6(cfg.DNS); err != nil {
			return ops, fmt.Errorf("wintun dns v6: %w", err)
		} else {
			ops = append(ops, op)
		}
		return ops, nil
	}
}

// tunToTunnel: Wintun -> SSTP (or -> bundle, which picks the session).
func (m *Manager) tunToTunnel(dev tun.Device, sess dataplane.Stream, stop chan struct{}) {
	defer m.wg.Done()
	bufs := [][]byte{make([]byte, 65535)}
	sizes := make([]int, 1)
	for {
		n, err := dev.Read(bufs, sizes, 0)
		if err != nil {
			return
		}
		for i := 0; i < n; i++ {
			if sizes[i] <= 0 {
				continue
			}
			if err := sess.WriteIP(bufs[i][:sizes[i]]); err != nil {
				m.logf("wintun: write to tunnel: %v", err)
				return
			}
		}
		select {
		case <-stop:
			return
		default:
		}
	}
}

// tunnelToTun: SSTP -> Wintun (packets arrive merged from every session,
// already DNAT'd to the primary inner address).
func (m *Manager) tunnelToTun(dev tun.Device, sess dataplane.Stream, stop chan struct{}) {
	defer m.wg.Done()
	buf := make([]byte, 65535)
	for {
		select {
		case <-stop:
			return
		default:
		}
		n, err := sess.ReadIP(buf)
		if err != nil {
			return
		}
		if _, err := dev.Write([][]byte{buf[:n]}, 0); err != nil {
			m.logf("wintun: write to adapter: %v", err)
			return
		}
	}
}

// Stop detaches the session: pumps exit, adapter closes, configuration is
// rolled back in reverse order.
func (m *Manager) Stop() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stopLocked()
}

func (m *Manager) stopLocked() {
	if m.stop != nil {
		close(m.stop)
		m.stop = nil
	}
	if m.dev != nil {
		_ = m.dev.Close()
		m.dev = nil
	}
	m.wg.Wait()
	m.runUndoLocked()
	m.started = false
}

func (m *Manager) runUndoLocked() {
	for i := len(m.undo) - 1; i >= 0; i-- {
		if err := m.undo[i].fn(); err != nil {
			m.logf("wintun: undo %s: %v", m.undo[i].desc, err)
		}
	}
	m.undo = nil
}

// Close releases everything (idempotent).
func (m *Manager) Close() error {
	m.Stop()
	return nil
}
