//go:build windows

package main

import (
	"swiftsstp/windows/internal/dataplane"
	"swiftsstp/windows/internal/wintunmgr"
)

// tunnelDataPlane returns the whole-device (Wintun) data plane on Windows.
func tunnelDataPlane(logf func(string, ...any)) dataplane.DataPlane {
	return wintunmgr.New(logf)
}
