# SwiftSSTP

An open-source SSTP (Secure Socket Tunneling Protocol) VPN client for
Android and Windows, built around a single portable Go engine.

<a href="docs/logo/swiftsstp-icon.svg"><img src="docs/logo/swiftsstp-icon-512.png" width="96" alt="SwiftSSTP logo"></a>

## Overview

SwiftSSTP establishes SSTP tunnels (PPP over HTTP over TLS) without
depending on any platform's built-in client, so connection behavior is
consistent across both supported operating systems: a profile carries the
server it dials and the certificate it is verified against, while the
tunnel options are configured once for the whole app.

## Capabilities

- **Tunnel modes**: route all device traffic through the server, or expose
  the tunnel as local SOCKS5 and HTTP proxy endpoints for applications
  that support proxy configuration (Windows).
- **Per-profile configuration**: server host and port, TLS SNI override,
  username and password, and the certificate mode (system trust store,
  pinned private CA bundle, or accept self-signed). Inner PPP
  authentication (MS-CHAPv2 family) mutually authenticates the peers
  regardless of the TLS verification mode.
- **App-wide settings** (one set of values shared by every profile, in
  `settings.json`): MTU, DNS override, IPv4/IPv6, auto-reconnect with
  exponential backoff, upstream proxy (HTTP CONNECT or SOCKS5 with
  optional credentials), 1–8 parallel sessions with independent
  failover, and, on Windows, the local proxy endpoints (SOCKS5 and HTTP)
  and the "set Windows system proxy while connected" toggle. Both shells
  expose them on their Settings tab.
- **Per-application tunneling** (Android, also app-wide): restrict the VPN
  to a listed set of applications or exclude a listed set.
- **Traffic accounting**: live session transfer rates and persistent
  lifetime totals for every profile.
- **Diagnostics**: connection log, negotiated endpoint details, and the
  server certificate's SHA-256 fingerprint for out-of-band verification.
- **Interface**: dark theme with a tabbed layout on both platforms:
  connection control and the current public address on the home tab,
  with profiles, statistics, logs and settings each in their own tab.
- **System tray** (Windows): the title-bar close hides the window while
  the tunnel keeps running; a left click on the tray icon, or a choice
  from its menu, restores the window, and the menu's Exit item closes
  the application.

## Download

Binaries for each release are published on the
[Releases page](../../releases):

| Asset | Platform |
|-------|----------|
| `SwiftSSTP-<version>-android.apk` | Android 8.0+ (arm64-v8a, armeabi-v7a) |
| `SwiftSSTP-<version>-windows-amd64.exe` | Windows 10/11 x64 |
| `sstpcli-<version>-linux-amd64` | Linux command-line client |

Each release includes a `SHA256SUMS` file; verify downloaded assets
against it. The Android package installs directly. If an earlier build
was installed with a different signature, uninstall it before
installing the release build.

## Building from source

Prerequisites: Go 1.27 or newer. Building the applications additionally
requires JDK 17, the Android SDK with NDK 27 (Android), and the
[Wails v2 CLI](https://wails.io) v2.16.0 (Windows).

```sh
# Engine and command-line client
go build ./...
go build ./cmd/sstpcli

# Windows client (cross-compiles from Linux and macOS)
cd windows && wails build -platform windows/amd64

# Android: bind the Go engine (not committed; rebuilt on each machine)
ANDROID_HOME=/path/to/sdk gomobile bind -target=android -androidapi 26 \
  -javapkg swiftsstp -ldflags='-s -w' -trimpath \
  -o android/app/libs/mobile.aar ./mobile

# Android: build the application
cd android && ./gradlew assembleDebug      # or assembleRelease
```

Continuous integration (`.github/workflows/ci.yml`) runs the test suites
and builds every target on each push. Pushing a version tag such as
`v1.2.0` triggers the release workflow
(`.github/workflows/release.yml`), which builds all binaries, signs the
Android package, and publishes a GitHub Release.

### Release signing

Without configuration, release APKs are signed with a debug key: the
package installs normally, but installing a later build with a different
signature requires uninstalling first. For consistent signatures across
releases, generate a keystore and add these repository secrets:

```sh
keytool -genkeypair -keystore swiftsstp.keystore -alias swiftsstp \
  -keyalg RSA -keysize 4096 -validity 10950
base64 -w0 swiftsstp.keystore    # value for SIGNING_KEYSTORE_BASE64
```

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | output of `base64 -w0` on the keystore |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Layout

| Path | What it is |
|------|------------|
| `tunnel/` | The engine: SSTP + PPP (MS-CHAPv2/MPPE) with a packet-level API (ReadIP/WriteIP): feeds a VpnService TUN, a Wintun adapter, or a netstack proxy |
| `internal/` | Vendored protocol stack (PPP, MS-CHAPv2/MPPE, SSTP wire, crypto binding), derived from the MIT-licensed veepin project; see `NOTICE` |
| `mobile/` | gomobile wrapper exposing the engine to the Android app |
| `android/` | Kotlin + Jetpack Compose app (VpnService, per-app rules, Material 3 dark UI) |
| `windows/` | Wails desktop app (Wintun full-tunnel + gVisor netstack proxy mode, vanilla-JS frontend embedded via go:embed) |
| `reconnect/` | Auto-reconnect manager with exponential backoff |
| `usage/` | Persistent per-profile traffic counters |
| `cmd/sstpcli/` | Reference CLI client (Linux TUN backend); the interop test bed |
| `docs/` | Shared contracts (`PROFILE_SCHEMA.md`) and logo sources (`logo/`) |

## Interop status

Verified against a SoftEther VPN Server (MS-SSTP listener): MS-CHAPv2
authentication, SHA-256 crypto binding, IPCP addressing and DNS
negotiation, and live traffic through the tunnel (an HTTP request through
the tunnel egresses via the server's public address).

## Contributing

Bug reports, feature requests, and pull requests are welcome. Before
submitting a pull request, run `go test ./...`, the `windows/` module
tests, and both application builds; continuous integration repeats these
checks.

## License

[MIT](LICENSE); see `NOTICE` for third-party attribution.
