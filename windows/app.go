package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"
	xproxy "golang.org/x/net/proxy"

	"swiftsstp/usage"
	"swiftsstp/windows/internal/engine"
	"swiftsstp/windows/internal/proxy"
	"swiftsstp/windows/internal/store"
	"swiftsstp/windows/internal/sysproxy"
)

const maxLogLines = 500

// App is the single bound struct. Instead of generated per-method bindings,
// it exposes one Call(action, argsJSON) entry point (pragmatic for headless
// builds) plus two push events: "status" (engine snapshots) and "log".
type App struct {
	ctx   context.Context
	mgr   *engine.Manager
	pxy   *proxy.Proxy
	usage *usage.Store

	mu   sync.Mutex
	logs []string
}

// NewApp wires the engine, data planes and persistence together.
func NewApp() *App {
	a := &App{}
	u, err := store.OpenUsage()
	if err != nil {
		fmt.Printf("swiftsstp: usage store unavailable: %v\n", err)
	} else {
		a.usage = u
	}
	a.pxy = proxy.New(proxy.Config{Logf: a.logf})
	a.mgr = engine.NewManager(engine.Options{
		Proxy:          a.pxy,
		Tunnel:         tunnelDataPlane(a.logf),
		Usage:          a.usage,
		OnStatus:       a.onStatus,
		Logf:           a.logf,
		SetSystemProxy: sysproxy.Set,
	})
	return a
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	a.logf("swiftsstp: ui ready")
}

func (a *App) shutdown(context.Context) {
	a.mgr.Close()
}

// ---- events ----

func (a *App) onStatus(s engine.Status) {
	if a.ctx == nil {
		return
	}
	runtime.EventsEmit(a.ctx, "status", s)
}

func (a *App) logf(format string, args ...any) {
	line := fmt.Sprintf("%s  %s", time.Now().Format("15:04:05.000"), fmt.Sprintf(format, args...))
	a.mu.Lock()
	a.logs = append(a.logs, line)
	if len(a.logs) > maxLogLines {
		a.logs = a.logs[len(a.logs)-maxLogLines:]
	}
	a.mu.Unlock()
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "log", line)
	}
}

// ---- binding ----

type callResult struct {
	OK    bool   `json:"ok"`
	Data  any    `json:"data,omitempty"`
	Error string `json:"error,omitempty"`
}

// Call executes one UI action. args is a JSON object; the return value is
// {"ok":true,"data":...} or {"ok":false,"error":"..."}.
func (a *App) Call(action, args string) string {
	data, err := a.dispatch(action, []byte(args))
	var out []byte
	if err != nil {
		out, _ = json.Marshal(callResult{OK: false, Error: err.Error()})
	} else {
		out, _ = json.Marshal(callResult{OK: true, Data: data})
	}
	return string(out)
}

func (a *App) dispatch(action string, args []byte) (any, error) {
	switch action {
	case "ping":
		return "pong", nil

	case "app_info":
		dir, err := store.Dir()
		if err != nil {
			return nil, err
		}
		return map[string]any{"config_dir": dir}, nil

	// ---- profiles ----
	case "profiles_list":
		list, err := store.List()
		if err != nil {
			return nil, err
		}
		return map[string]any{"profiles": list}, nil

	case "profile_new":
		return engine.NewProfile(), nil

	case "profile_save":
		var in struct {
			Profile engine.Profile `json:"profile"`
		}
		if err := json.Unmarshal(args, &in); err != nil {
			return nil, err
		}
		if err := in.Profile.Validate(); err != nil {
			return nil, err
		}
		saved, err := store.Upsert(in.Profile)
		if err != nil {
			return nil, err
		}
		a.logf("profile %q saved", saved.Name)
		return map[string]any{"profile": saved}, nil

	case "profile_delete":
		var in struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(args, &in); err != nil {
			return nil, err
		}
		if err := store.Delete(in.ID); err != nil {
			return nil, err
		}
		a.logf("profile %s deleted", in.ID)
		return nil, nil

	case "profile_duplicate":
		var in struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(args, &in); err != nil {
			return nil, err
		}
		list, err := store.List()
		if err != nil {
			return nil, err
		}
		for _, p := range list {
			if p.ID == in.ID {
				cp := p
				cp.ID = store.NewID()
				cp.Name = p.Name + " copy"
				saved, err := store.Upsert(cp)
				if err != nil {
					return nil, err
				}
				return map[string]any{"profile": saved}, nil
			}
		}
		return nil, fmt.Errorf("profile %s not found", in.ID)

	// ---- connection ----
	case "connect":
		var in struct {
			ID   string `json:"id"`
			Mode string `json:"mode"`
		}
		if err := json.Unmarshal(args, &in); err != nil {
			return nil, err
		}
		list, err := store.List()
		if err != nil {
			return nil, err
		}
		var found *engine.Profile
		for i := range list {
			if list[i].ID == in.ID {
				found = &list[i]
			}
		}
		if found == nil {
			return nil, fmt.Errorf("profile %s not found", in.ID)
		}
		// Restart if something is already up (also covers mode changes).
		a.mgr.Disconnect()
		if err := a.mgr.Connect(*found, engine.Mode(in.Mode)); err != nil {
			return nil, err
		}
		a.logf("connect requested: %q mode=%s", found.Name, in.Mode)
		return nil, nil

	case "disconnect":
		a.mgr.Disconnect()
		return nil, nil

	case "status":
		return a.mgr.Status(), nil

	// ---- geoip: public egress IP as seen by Cloudflare ----
	case "geoip":
		return a.geoIP()

	// ---- usage / logs ----
	case "usage_totals":
		var in struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(args, &in); err != nil {
			return nil, err
		}
		if a.usage == nil {
			return map[string]any{"snapshot": usage.Snapshot{}}, nil
		}
		return map[string]any{"snapshot": a.usage.Get(in.ID)}, nil

	case "logs":
		a.mu.Lock()
		lines := append([]string(nil), a.logs...)
		a.mu.Unlock()
		return map[string]any{"lines": lines}, nil

	case "logs_clear":
		a.mu.Lock()
		a.logs = nil
		a.mu.Unlock()
		return nil, nil

	default:
		return nil, fmt.Errorf("unknown action %q", action)
	}
}

// ---- geoip ----

const geoTraceURL = "https://www.cloudflare.com/cdn-cgi/trace"

// geoIP reports the machine's public egress address as seen by Cloudflare's
// cdn-cgi/trace endpoint: {ip, country, colo}.
//
// Dial path: while connected in local-proxy mode the request goes through the
// app's own SOCKS5 inbound so the address reported is the *tunnelled* egress
// (what remote sites see), not the host's own. In whole-device mode or when
// disconnected it dials normally — system routes already carry the traffic
// through Wintun while the tunnel is up.
func (a *App) geoIP() (any, error) {
	st := a.mgr.Status()

	base := &net.Dialer{Timeout: 4 * time.Second}
	var dial xproxy.Dialer = base
	if st.State == "connected" && st.Mode == string(engine.ModeProxy) && st.SocksPort > 0 {
		d, err := xproxy.SOCKS5("tcp", fmt.Sprintf("127.0.0.1:%d", st.SocksPort), nil, base)
		if err != nil {
			return nil, fmt.Errorf("geoip: socks dialer: %w", err)
		}
		dial = d
	}

	transport := &http.Transport{
		DisableKeepAlives: true,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			if cd, ok := dial.(xproxy.ContextDialer); ok {
				return cd.DialContext(ctx, network, addr)
			}
			// Dialers without context support: run the dial in the
			// background and honour the deadline ourselves.
			type result struct {
				c net.Conn
				e error
			}
			ch := make(chan result, 1)
			go func() {
				c, e := dial.Dial(network, addr)
				ch <- result{c, e}
			}()
			select {
			case r := <-ch:
				return r.c, r.e
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		},
	}
	defer transport.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, geoTraceURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "SwiftSSTP")

	resp, err := (&http.Client{Transport: transport, Timeout: 4 * time.Second}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("geoip: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("geoip: trace endpoint returned %s", resp.Status)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if err != nil {
		return nil, fmt.Errorf("geoip: read: %w", err)
	}

	out := map[string]string{}
	for _, line := range strings.Split(string(body), "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		switch k {
		case "ip", "loc", "colo":
			out[k] = v
		}
	}
	if out["ip"] == "" {
		return nil, fmt.Errorf("geoip: no ip= line in trace")
	}
	return map[string]any{
		"ip":      out["ip"],
		"country": out["loc"],
		"colo":    out["colo"],
	}, nil
}
