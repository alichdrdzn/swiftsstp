// Package assets embeds binaries the Windows client needs at runtime.
package assets

import _ "embed"

// WintunDLL is the signed wintun.dll (amd64) from the official wintun build
// (wintun-0.14.1.zip, bin/amd64/wintun.dll). It is materialised to disk on
// first whole-device connect and pre-loaded so the golang.zx2c4.com/wintun
// binding's bare-name LoadLibraryEx finds it.
//
//go:embed wintun.dll
var WintunDLL []byte

// TrayIcon is the multi-resolution .ico for the Windows notification area
// (16, 24, 32, 48 and 256 px frames), generated from
// docs/logo/swiftsstp-icon-512.png. The shell wants raw .ico bytes rather
// than a path, because fyne.io/systray materialises them to a temp file and
// hands that to LoadImage, so the icon has to travel inside the exe.
//
//go:embed tray.ico
var TrayIcon []byte
