//go:build windows

// Package sysproxy toggles the Windows per-user WinINET proxy
// (HKCU\...\Internet Settings) for proxy mode's "set system proxy" option
// and restores the previous values afterwards.
package sysproxy

import (
	"fmt"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const iSettingsKey = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`

var (
	wininet              = windows.NewLazySystemDLL("wininet.dll")
	procInternetSetOption = wininet.NewProc("InternetSetOptionW")
)

// prev remembers what was configured before we touched anything so the
// restore is faithful (the user may have had their own proxy).
type saved struct {
	have     bool
	enable   uint32
	hadEnable bool
	server   string
	hadServer bool
	override string
	hadOverride bool
}

var before saved

// Set enables (enabled=true, server e.g. "http=127.0.0.1:10809;socks=127.0.0.1:10808")
// or restores (enabled=false) the user proxy.
func Set(enabled bool, server string) error {
	k, err := registry.OpenKey(registry.CURRENT_USER, iSettingsKey, registry.SET_VALUE|registry.QUERY_VALUE)
	if err != nil {
		return fmt.Errorf("open Internet Settings: %w", err)
	}
	defer k.Close()

	if enabled {
		if !before.have {
			before = saved{have: true}
			if v, _, err := k.GetIntegerValue("ProxyEnable"); err == nil {
				before.enable, before.hadEnable = uint32(v), true
			}
			if v, _, err := k.GetStringValue("ProxyServer"); err == nil {
				before.server, before.hadServer = v, true
			}
			if v, _, err := k.GetStringValue("ProxyOverride"); err == nil {
				before.override, before.hadOverride = v, true
			}
		}
		if err := k.SetDWordValue("ProxyEnable", 1); err != nil {
			return fmt.Errorf("set ProxyEnable: %w", err)
		}
		if err := k.SetStringValue("ProxyServer", server); err != nil {
			return fmt.Errorf("set ProxyServer: %w", err)
		}
		// Never proxy loopback/local names through us.
		if !before.hadOverride {
			if err := k.SetStringValue("ProxyOverride", "<local>;localhost;127.*;10.*;192.168.*"); err != nil {
				return fmt.Errorf("set ProxyOverride: %w", err)
			}
		}
		notifyWinINET()
		return nil
	}

	if !before.have {
		return nil // never enabled: nothing to restore
	}
	restore := func(name string, val string, existed bool, setStr func(string, string) error, del func(string) error) {
		if existed {
			_ = setStr(name, val)
		} else {
			_ = del(name)
		}
	}
	if before.hadEnable {
		_ = k.SetDWordValue("ProxyEnable", before.enable)
	} else {
		_ = k.DeleteValue("ProxyEnable")
	}
	restore("ProxyServer", before.server, before.hadServer, k.SetStringValue, k.DeleteValue)
	restore("ProxyOverride", before.override, before.hadOverride, k.SetStringValue, k.DeleteValue)
	before = saved{}
	notifyWinINET()
	return nil
}

// notifyWinINET broadcasts INTERNET_OPTION_SETTINGS_CHANGED +
// INTERNET_OPTION_REFRESH so running apps pick the change up.
func notifyWinINET() {
	const (
		internetOptionSettingsChanged = 39
		internetOptionRefresh         = 37
	)
	_, _, _ = procInternetSetOption.Call(0, internetOptionSettingsChanged, 0, 0)
	_, _, _ = procInternetSetOption.Call(0, internetOptionRefresh, 0, 0)
}
