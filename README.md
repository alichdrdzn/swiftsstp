# SwiftSSTP

An open-source SSTP (Secure Socket Tunneling Protocol) VPN client for Android
and Windows, built around one portable Go engine.

<a href="docs/logo/swiftsstp-icon.svg"><img src="docs/logo/swiftsstp-icon-512.png" width="96" alt="SwiftSSTP logo"></a>

## Features

- **Two modes**: whole-device tunneling (VpnService on Android, Wintun on
  Windows), or a v2rayN-style local proxy (SOCKS5 + HTTP) for apps that
  support proxies (Windows).
- **No certificate requirements**: connect to self-signed servers
  ("insecure" mode), pin a private CA, or verify normally — your choice
  per profile.
- **Everything is a profile setting**: MTU, DNS override, TLS SNI, custom
  port, IPv4/IPv6, auto-reconnect with backoff, upstream proxy
  (HTTP CONNECT / SOCKS5), parallel tunnels (1–8 sessions), per-app
  tunneling (Android).
- **Warp-style UI on both platforms**: Home tab with a single connect
  button and your public IP + country flag, plus Profiles / Stats / Log
  tabs.
- **Usage accounting**: live session up/down/rate plus persisted lifetime
  totals per server.

## Download

Grab the latest release for your platform from the
[Releases page](../../releases):

| Asset | For |
|-------|-----|
| `SwiftSSTP-<ver>-android.apk` | Android (arm64 + armeabi-v7a, minSdk 26) |
| `SwiftSSTP-<ver>-windows-amd64.exe` | Windows 10/11 x64 |
| `sstpcli-<ver>-linux-amd64` | Reference CLI client (Linux TUN) |

Every release ships a `SHA256SUMS` file — verify what you download.

> Android: installs directly (signed). If you previously installed a
> debug-signed build, uninstall it first — the signatures differ.

## Building from source

Prerequisites: Go 1.27+, and for the apps additionally JDK 17+, Android
SDK + NDK 27, and the [Wails v2 CLI](https://wails.io) (v2.16.0).

```sh
# Engine + CLI
go build ./...
go build ./cmd/sstpcli

# Windows client (cross-compiles fine from Linux/macOS)
cd windows && wails build -platform windows/amd64

# Android — step 1: bind the Go engine (NOT committed; rebuilt locally/CI)
ANDROID_HOME=/path/to/sdk gomobile bind -target=android -androidapi 26 \
  -javapkg swiftsstp -ldflags='-s -w' -trimpath \
  -o android/app/libs/mobile.aar ./mobile

# Android — step 2: build the app
cd android && ./gradlew assembleDebug      # or assembleRelease
```

CI runs the same steps on every push (`.github/workflows/ci.yml`); pushing
a tag like `v1.2.0` builds all binaries and publishes a GitHub Release
(`.github/workflows/release.yml`).

### Release signing (optional)

Without secrets, the release APK is signed with a debug key — installable,
but updates from a differently-signed build require an uninstall first.
For stable upgrade-in-place APKs, add these repository secrets:

```sh
base64 -w0 swiftsstp.keystore > keystore.b64   # then paste as the secret
```

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

## Layout

| Path | What it is |
|------|------------|
| `tunnel/` | The engine: SSTP + PPP (MS-CHAPv2/MPPE) with a packet-level API (ReadIP/WriteIP) — feeds a VpnService TUN, a Wintun adapter, or a netstack proxy |
| `internal/` | Vendored protocol stack (PPP, MS-CHAPv2/MPPE, SSTP wire, crypto binding), derived from the MIT-licensed veepin project — see `NOTICE` |
| `mobile/` | gomobile wrapper exposing the engine to the Android app |
| `android/` | Kotlin + Jetpack Compose app (VpnService, per-app rules, Material 3 dark UI) |
| `windows/` | Wails desktop app (Wintun full-tunnel + gVisor netstack proxy mode, buildless vanilla-JS frontend) |
| `reconnect/` | Auto-reconnect manager with exponential backoff |
| `usage/` | Persistent per-profile traffic counters |
| `cmd/sstpcli/` | Reference CLI client (Linux TUN backend) — the interop test bed |
| `docs/` | Shared contracts (`PROFILE_SCHEMA.md`) and logo sources (`logo/`) |

## Interop status

Verified live against a SoftEther VPN Server (MS-SSTP listener): MS-CHAPv2
authentication, SHA-256 crypto binding, IPCP addressing/DNS, and real
traffic egress (curl through the tunnel shows the server's public IP).

## Contributing

Issues and pull requests are welcome. Run `go test ./...`, the `windows/`
module tests, and both app builds before sending a PR — CI will check them
for you.

## License

[MIT](LICENSE) — see `NOTICE` for third-party attribution.
