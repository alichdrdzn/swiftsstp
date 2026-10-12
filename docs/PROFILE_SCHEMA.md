# SwiftSSTP profile schema (v1)

Both the Android and Windows apps store server profiles in `profiles.json`
and app-wide tunnel settings in `settings.json`. The two files always live
in the same directory. Field names are canonical; apps may add
app-specific extras under "extras" but must not rename core fields.

## profiles.json: one entry per server profile

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
      "extras": {}
    }
  ]
}
```

- `tls_mode`: "verify" | "insecure" | "custom_ca"
- `ca_path`: file path of a PEM bundle; only meaningful for custom_ca.
  The certificate mode is the one connection option that stays per profile.
- `extras` (app-specific, both shells may use it):
  - Windows: `socks_port`, `http_port`, `system_proxy` (local proxy
    endpoints used in proxy mode)
  - Android: none for now
- A profile carries only what makes a connection to THAT server: address,
  credentials and certificate handling. Everything else is app-wide and
  lives in `settings.json` (below).
- File location:
  - Android: app-internal files dir (`filesDir/profiles.json`)
  - Windows: `%APPDATA%\SwiftSSTP\profiles.json` (dev: `./profiles.json`)
  - Linux/macOS (dev/test): `$SWIFTSSTP_HOME/profiles.json`, defaulting to
    `~/.config/SwiftSSTP/profiles.json`

## settings.json: app-wide, shared by every profile

```json
{
  "version": 1,
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
  "app_rules": {
    "mode": "allow",
    "packages": []
  }
}
```

- These fields used to live on each profile. They are now one set of
  values for the whole app: a connect takes the selected profile and
  applies `settings.json` on top of it before dialing.
- `mtu`: 0 = engine default (1400); otherwise clamped 576..1500
- `ipv4_enabled` / `ipv6_enabled`: address families to negotiate and route;
  at least one must be true
- `dns_override`: list of IP strings; empty = use server-provided DNS
- `auto_reconnect`: retry policy after a drop (auth errors are never
  retried). `initial_backoff_ms` / `max_backoff_ms` bound the exponential
  backoff.
- `proxy`: upstream proxy for the OUTER transport (the proxy used to reach
  the SSTP server). `type`: "" (direct) | "http" (CONNECT) | "socks5".
  Credentials optional. When set, every session connects through it.
- `tunnels`: number of parallel SSTP sessions to bundle (1..8). 1 = single
  session. Higher values spread concurrent flows across sessions for
  aggregate throughput and per-session failover.
- `app_rules` (Android only): per-app tunneling. `mode`: "allow" (only the
  listed packages use the tunnel) | "deny" (everything except them);
  `packages`: package names, empty = whole device in allow mode. Windows
  does not write this field and must tolerate its absence.
- Writers must NOT write the moved fields back into `profiles.json`.
  Readers ignore those keys when an older file still carries them.
- Migration: when `settings.json` does not exist, it is created on first
  load from the first profile in `profiles.json` (file order) that differs
  from the built-in defaults in at least one moved field; if no profile
  differs, the defaults are written. Existing installs therefore keep the
  values they had configured.

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
