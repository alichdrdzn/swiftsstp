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
