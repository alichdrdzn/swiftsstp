# SSTPflash profile schema (v1)

Both the Android and Windows apps store profiles as JSON with this exact
shape. Field names are canonical; apps may add app-specific extras under
"extras" but must not rename core fields.

```json
{
  "version": 1,
  "profiles": [
    {
      "id": "random-hex-16",
      "name": "My server",
      "host": "18.130.233.223",
      "port": 443,
      "sni": "",
      "username": "mohsen",
      "password": "mohsen123",
      "tls_mode": "insecure",
      "ca_path": "",
      "mtu": 0,
      "ipv4_enabled": true,
      "ipv6_enabled": false,
      "dns_override": [],
      "auto_reconnect": {
        "enabled": true,
        "initial_backoff_ms": 1000,
        "max_backoff_ms": 30000
      },
      "proxy": {
        "type": "",
        "host": "",
        "port": 0,
        "username": "",
        "password": ""
      },
      "tunnels": 1,
      "extras": {}
    }
  ]
}
```

- `tls_mode`: "verify" | "insecure" | "custom_ca"
- `ca_path`: file path of a PEM bundle; only meaningful for custom_ca
- `mtu`: 0 = engine default (1400); otherwise clamped 576..1500
- `dns_override`: list of IP strings; empty = use server-provided DNS
- `proxy`: upstream proxy for the OUTER transport (v2rayN-style outbound
  proxy). `type`: "" (direct) | "http" (CONNECT) | "socks5". Credentials
  optional. When set, every session of this profile connects through it.
- `tunnels`: number of parallel SSTP sessions to bundle (1..8). 1 = single
  session. Higher values spread concurrent flows across sessions for
  aggregate throughput and per-session failover.
- `extras.allowed_apps` (Android): package names for per-app tunneling;
  empty = whole device
- File location:
  - Android: app-internal files dir (`filesDir/profiles.json`)
  - Windows: `%APPDATA%\SSTPflash\profiles.json` (dev: `./profiles.json`)

# Usage stats (separate file, same directory, `usage-stats.json`)

```json
{
  "version": 1,
  "servers": {
    "<profile-id>": {
      "total_up": 123456,
      "total_down": 654321,
      "sessions": 12,
      "first_used": "2026-10-09T20:00:02Z",
      "last_used": "2026-10-10T08:11:00Z"
    }
  }
}
```

Counters are cumulative inner bytes for that profile across all sessions and
app restarts. Sessions increment on each successful tunnel establishment.
