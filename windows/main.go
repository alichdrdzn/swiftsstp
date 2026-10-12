// Command main is the SwiftSSTP Windows client: a Wails v2 desktop
// app (Go backend, buildless vanilla-JS frontend) around the SwiftSSTP
// SSTP engine, with whole-device tunnel mode (Wintun) and local proxy
// mode (gVisor netstack + SOCKS5/HTTP inbounds).
package main

import (
	"embed"
	"log"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

// The frontend is plain HTML/CSS/JS with no build step: static files live
// directly in frontend/dist and are embedded verbatim. Wails finds
// index.html in the FS and serves everything else beside it.
//
//go:embed all:frontend/dist
var assets embed.FS

func main() {
	app := NewApp()

	// Phone-shaped window: the frontend is a single 400px-wide column
	// (bottom tab bar + one stacked Home screen), matching the Android shell.
	err := wails.Run(&options.App{
		Title:             "SwiftSSTP",
		Width:             400,
		Height:            860,
		MinWidth:          360,
		MinHeight:         640,
		BackgroundColour:  &options.RGBA{R: 0, G: 0, B: 0, A: 255},
		AssetServer:       &assetserver.Options{Assets: assets},
		OnStartup:         app.startup,
		OnShutdown:        app.shutdown,
		Bind:              []interface{}{app},
		WindowStartState:  options.Normal,
		// Windows platform specifics (Wails' WebView2 loader is pure Go,
		// so this cross-compiles without CGO).
		Windows: &windows.Options{
			WebviewIsTransparent: false,
			WindowIsTranslucent:  false,
			DisableWindowIcon:    false,
			ZoomFactor:           1.0,
		},
	})
	if err != nil {
		log.Fatal(err)
	}
}
