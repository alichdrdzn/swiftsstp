//go:build !windows

package main

import "sstpflash/windows/internal/dataplane"

// tunnelDataPlane returns nil off Windows: the engine then refuses
// whole-device mode up front instead of dialing and failing (see
// cmd/proxytest, which exercises proxy mode on Linux).
func tunnelDataPlane(func(string, ...any)) dataplane.DataPlane {
	return nil
}
