package main

import (
	"context"
	"os"
	goruntime "runtime" // the standard library one, not the Wails runtime below
	"time"

	"fyne.io/systray"

	"github.com/wailsapp/wails/v2/pkg/runtime"

	// Aliased because package main already has an `assets` variable for the
	// embedded frontend, and this package carries the tray icon instead.
	winassets "swiftsstp/windows/assets"
)

// exitGrace is how long the tray Exit item gives the Wails quit to take the
// process down before it finishes the job itself. The Wails quit is
// asynchronous on Windows: runtime.Quit posts to the main thread and the
// process leaves through the message loop, running OnShutdown on the way out.
// A few seconds is far longer than a healthy shutdown needs, so reaching this
// limit means the graceful path is already broken.
const exitGrace = 3 * time.Second

// startTray brings up the notification area icon and keeps it alive until the
// app leaves. It blocks inside systray.Run, which is why App.startup runs it
// on its own goroutine: the Wails message loop owns the main thread.
//
// This icon is the app's whole exit story on Windows. The title bar close
// button only hides the window (see App.onBeforeClose), so without the tray
// menu there would be no way to quit at all. That is the v2rayN contract this
// client follows: close means "keep running quietly", the tray menu means
// "stop", and a left click on the icon is the shortcut back to the window.
func (a *App) startTray() {
	// Win32 rule, and the reason this goroutine never moves again: a window
	// belongs to the thread that created it, and only that thread can pump
	// its message queue. systray gives the icon a hidden window and then
	// blocks in GetMessage, so without the lock the Go scheduler could put
	// the window creation and the pump on different threads, and every tray
	// click would land in a queue nothing ever reads. The pin costs one
	// thread for the lifetime of the app, which is exactly how long the tray
	// icon lives.
	goruntime.LockOSThread()

	systray.Run(func() {
		systray.SetIcon(winassets.TrayIcon)
		systray.SetTooltip("SwiftSSTP")

		// Deliberately minimal: Open and Exit, nothing else. Connection
		// state lives in the window, and there are no start-with-Windows or
		// auto-connect switches to expose here.
		open := systray.AddMenuItem("Open", "Show the SwiftSSTP window")
		systray.AddSeparator()
		exit := systray.AddMenuItem("Exit", "Disconnect and quit SwiftSSTP")

		// A left click toggles the window. Installing this callback also
		// replaces systray's default of popping the menu on left click, so
		// the menu stays on the right button where Windows users expect it.
		systray.SetOnTapped(func() { a.toggleWindow() })

		// Menu clicks arrive as channel receives, one per item. A single
		// goroutine drains both so the ready callback can return into
		// systray's own event loop.
		go func() {
			for {
				select {
				case <-open.ClickedCh:
					a.showWindow()
				case <-exit.ClickedCh:
					a.exitFromTray()
					return
				}
			}
		}()
	}, nil)
}

// hideWindow parks the window in the tray. Hiding is not closing: the engine
// manager, the tunnel and the local proxy keep running untouched, only the
// window stops being visible.
func (a *App) hideWindow(ctx context.Context) {
	runtime.Hide(ctx)
	a.winHidden.Store(true)
}

// showWindow brings the window back from the tray. Unminimise deliberately
// comes before Show: a window that was hidden while minimised is still
// minimised when it becomes visible again, so showing it alone would leave a
// taskbar button with no usable window behind it.
func (a *App) showWindow() {
	ctx := a.ctx
	if ctx == nil {
		return
	}
	if runtime.WindowIsMinimised(ctx) {
		runtime.WindowUnminimise(ctx)
	}
	runtime.Show(ctx)
	a.winHidden.Store(false)
}

// toggleWindow is the tray icon's left click: hidden to visible and back. It
// keeps its own idea of whether the window was parked on purpose, because
// Wails has no "is the window hidden" query to ask.
func (a *App) toggleWindow() {
	ctx := a.ctx
	if ctx == nil {
		return
	}
	if a.winHidden.Load() {
		a.showWindow()
		return
	}
	a.hideWindow(ctx)
}

// onBeforeClose is options.App.OnBeforeClose. The title bar close button must
// never exit this app: the tunnel has to outlive a closed window, so the
// window is hidden instead and the close itself is refused.
//
// The refusal is the load-bearing half, and it is the opposite of what the
// obvious snippet suggests. In Wails v2.16.0 the Windows frontend routes the
// main window's WM_CLOSE straight to Frontend.Quit() (see
// internal/frontend/desktop/windows/frontend.go: mainWindow.OnClose().Bind
// calls f.Quit()), and Quit only returns early when OnBeforeClose reports
// true. Hiding the window and then returning false would hide it and shut the
// app down in the same breath, quietly turning this feature into "close
// exits". True means "handled it myself", so the window survives, the app
// keeps running and the tray keeps its job.
//
// A deliberate exit from the tray is the single exception: exitFromTray sets
// the flag before anything is torn down, so this hook lets runtime.Quit pass
// instead of blocking it.
func (a *App) onBeforeClose(ctx context.Context) bool {
	if a.exiting.Load() {
		return false
	}
	a.hideWindow(ctx)
	return true
}

// exitFromTray is the tray menu's Exit item and the app's only exit path on
// Windows. It must leave nothing behind: the tunnel comes down and the
// process cannot survive as a zombie if the graceful quit wedges.
func (a *App) exitFromTray() {
	// Announce the deliberate exit before anything is torn down, or the quit
	// below would bounce straight off onBeforeClose and hide the window
	// instead of ending the app.
	a.exiting.Store(true)

	// Watchdog armed before the quit is requested: if Wails has not taken the
	// process down inside the grace period, os.Exit does. The manager close
	// has already been requested by then, so this cannot strand a tunnel
	// behind a hung UI; it only fires when the graceful path is already
	// broken, which is exactly when an unhangable exit is wanted.
	go func() {
		time.Sleep(exitGrace)
		os.Exit(0)
	}()

	if ctx := a.ctx; ctx != nil {
		// Runs OnShutdown, which calls a.mgr.Close() and brings the tunnel
		// and the local proxy down before the process goes away.
		runtime.Quit(ctx)
	}

	// Drop the icon as soon as the quit is on its way, so a slow shutdown
	// cannot leave a dead entry in the notification area. v1.12.2 spells
	// this call Quit (there is no systray.Exit), it returns immediately, and
	// it ends the tray loop only, never the process.
	systray.Quit()
}
