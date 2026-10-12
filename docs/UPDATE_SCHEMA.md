# Update manifest schema

The update channel reads a small JSON manifest that the release workflow
attaches to every GitHub Release as `update-manifest.json`. Both shells
fetch it, compare `version` against their own build, and offer the
matching asset. Version 1.

## Where it comes from

Stable channel only, no extra hosting:

```
https://github.com/<owner>/<repo>/releases/latest/download/update-manifest.json
```

That URL is a GitHub redirect into the release-asset CDN: no API call, no
token, no per-IP rate limit (the REST API allows 60 unauthenticated
requests per hour per IP, which a few hundred clients behind one NAT would
exhaust in minutes). It returns an `etag`, so clients revalidate with
`If-None-Match` and get `304` when nothing changed.

`releases/latest` deliberately skips prereleases, so cutting a prerelease
never disturbs stable clients. A beta channel would add a second URL
pointing at the newest prerelease; nothing below has to change for that.

## Fields

| Field | Type | Meaning |
|-------|------|---------|
| `schema` | int | Manifest format version; clients reject anything they do not know. `1` today. |
| `channel` | string | `"stable"` in this schema; reserved for `beta` later. |
| `version` | string | Dotted release version WITHOUT the leading `v`, e.g. `"0.3.0"`. The only field clients compare. |
| `version_code` | int | Android `versionCode` the release was built with (epoch days in CI). Advisory: clients MUST compare semver, and use this only to reason about Android downgrades. |
| `published_at` | string | RFC 3339 UTC timestamp. |
| `notes_url` | string | Release page, for the "what's new" affordance. |
| `min_version` | string | Optional. When set and the running version is older, the update is mandatory: the UI offers no "later". |
| `assets` | object | Per-platform artifact, keyed by platform. |
| `assets.<platform>.name` | string | Release asset filename. |
| `assets.<platform>.url` | string | Absolute download URL. Absolute so a mirror can host the same manifest with mirrored binaries. |
| `assets.<platform>.sha256` | string | Lowercase hex digest, copied from `SHA256SUMS`. Clients MUST verify the download against it before handing it to the OS. |
| `assets.<platform>.size` | int | Bytes, for progress reporting and a cheap sanity check. |

Platforms in use: `android` (`SwiftSSTP-<version>-android.apk`) and
`windows` (`SwiftSSTP-<version>-windows-amd64.exe`). `sstpcli` is not in
the manifest: the CLI is built from source or a package manager.

## Client rules

- Compare `version` as semver against the running build. A build that does
  not know its own version (`"dev"`, i.e. anything built outside CI) must
  never claim an update exists.
- The check is MANUAL ONLY (user preference): the client asks for the
  manifest when the user taps "Check for updates" — never on launch,
  never on a timer, never in the background. Cache the body and the etag
  so that one request is cheap, and always tell the user the outcome:
  the offer, "you are on the latest version", or an honest failure line.
- The check must never block the tunnel: it runs off the connection
  path, and a build that does not know its version never requests at all.
- Verify `sha256` of the downloaded file before installing anything.
- A manifest whose `assets` entry for this platform is missing is not an
  error: the platform simply has no update in that release.

## Adding a mirror

Put the mirror's own copy of the manifest behind the same
`releases/latest/download` path on that host, with `assets.*.url` rewritten
to its own binaries, and list the mirror after GitHub in the client's URL
list. Clients try the URLs in order and stop at the first that answers.
No client release is needed to add one.

## Publishing

`scripts/update-manifest.py` builds the file from the assembled assets and
`SHA256SUMS`; the `publish` job in `.github/workflows/release.yml` runs it
and attaches the result with the binaries. It is deliberately generated
from the same checksums the release publishes, so the manifest and the
`SHA256SUMS` file can never disagree.
