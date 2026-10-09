# Slipstream

An open-source SSTP (Secure Socket Tunneling Protocol) VPN client for Android
and Windows, built around one portable Go engine.

- **Two modes**: whole-device tunneling, or a v2rayN-style local proxy
  (SOCKS5 + HTTP) for apps that support proxies.
- **No certificate requirements**: connect to self-signed servers
  ("insecure" mode), pin a private CA, or verify normally — your choice
  per profile.
- **Everything is a profile setting**: MTU, DNS override, TLS SNI,
  custom port, IPv4/IPv6, auto-reconnect with backoff, per-app tunneling
  (Android).
- **Usage accounting**: live session up/down/rate plus persisted lifetime
  totals per server.

## Layout

| Path        | What it is                                                        |
|-------------|-------------------------------------------------------------------|
| `tunnel/`   | The engine: SSTP + PPP (MS-CHAPv2/MPPE) with a packet-level API (ReadIP/WriteIP) — feeds a VpnService TUN, a Wintun adapter, or a netstack proxy |
| `internal/` | Vendored protocol stack (PPP, MS-CHAPv2/MPPE, SSTP wire, crypto binding), derived from the MIT-licensed veepin project — see `NOTICE` |
| `mobile/`   | gomobile wrapper exposing the engine to the Android app           |
| `android/`  | Kotlin + Jetpack Compose app (VpnService, per-app rules, Material 3 dark UI) |
| `windows/`  | Wails desktop app (Wintun full-tunnel + gVisor netstack proxy mode, dark card UI) |
| `reconnect/`| Auto-reconnect manager with exponential backoff                   |
| `usage/`    | Persistent per-profile traffic counters                           |
| `cmd/sstpcli/` | Reference CLI client (Linux TUN backend) — the interop test bed |
| `docs/`     | Shared contracts (`PROFILE_SCHEMA.md`)                            |

## Interop status

Verified live against a SoftEther VPN Server (MS-SSTP listener): MS-CHAPv2
authentication, SHA-256 crypto binding, IPCP addressing/DNS, and real
traffic egress (curl through the tunnel shows the server's public IP).

## Building

```sh
# CLI (Linux)
go build ./cmd/sstpcli

# Android APK (needs Android SDK + gomobile; see android/README)
./android/gradlew -p android assembleDebug

# Windows (cross-compiles from Linux)
wails build -platform windows/amd64
```

## License

MIT (see `NOTICE` for third-party attribution).
