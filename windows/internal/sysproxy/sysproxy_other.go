//go:build !windows

// Package sysproxy: non-Windows stub (the GUI only ships for Windows; this
// keeps the module building for the Linux proxy test).
package sysproxy

import "errors"

// Set is unsupported outside Windows.
func Set(bool, string) error {
	return errors.New("system proxy: not supported on this platform")
}
