//go:build windows

package main

import (
	"slipstream/windows/internal/dataplane"
	"slipstream/windows/internal/wintunmgr"
)

// tunnelDataPlane returns the whole-device (Wintun) data plane on Windows.
func tunnelDataPlane(logf func(string, ...any)) dataplane.DataPlane {
	return wintunmgr.New(logf)
}
