package main

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/wailsapp/wails/v2/pkg/runtime"

	"sstpflash/windows/internal/engine"
	"sstpflash/windows/internal/proxy"
	"sstpflash/windows/internal/store"
	"sstpflash/usage"
	"sstpflash/windows/internal/sysproxy"
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
		fmt.Printf("sstpflash: usage store unavailable: %v\n", err)
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
	a.logf("sstpflash: ui ready")
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
