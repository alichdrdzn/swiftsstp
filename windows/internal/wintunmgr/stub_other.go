//go:build !windows

// Package wintunmgr: non-Windows stub so the module builds everywhere; the
// engine refuses whole-device mode when the real manager is absent.
package wintunmgr

import (
	"errors"

	"sstpflash/tunnel"
	"sstpflash/windows/internal/dataplane"
)

// Manager is the Windows-only whole-device data plane.
type Manager struct{}

// New returns a stub that fails on Start (whole-device mode is Windows-only).
func New(logf func(string, ...any)) *Manager {
	_ = logf
	return &Manager{}
}

// Start always fails off Windows.
func (m *Manager) Start(*tunnel.Session, tunnel.Result, dataplane.Config) error {
	return errors.New("wintun: whole-device mode requires Windows")
}

// Stop is a no-op.
func (m *Manager) Stop() {}

// Close is a no-op.
func (m *Manager) Close() error { return nil }
