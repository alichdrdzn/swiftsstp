#!/usr/bin/env python3
"""Generate the update manifest the apps poll (docs/UPDATE_SCHEMA.md).

Run by the release workflow's publish job over the assembled asset
directory: the checksums come straight from the SHA256SUMS the release
publishes, so the manifest and that file can never disagree.

    scripts/update-manifest.py --version 0.3.0 --repo owner/name \
        --tag v0.3.0 --asset-dir out > out/update-manifest.json
"""

import argparse
import datetime
import json
import pathlib
import sys

# Platforms the channel serves. sstpcli is absent on purpose: the CLI is
# built from source, not updated in place.
PLATFORMS = {
    "android": "SwiftSSTP-{version}-android.apk",
    "windows": "SwiftSSTP-{version}-windows-amd64.exe",
}


def read_sums(path: pathlib.Path) -> dict[str, str]:
    """`<hex>  <name>` lines → {name: hex}; ignores anything malformed."""
    sums: dict[str, str] = {}
    if not path.is_file():
        return sums
    for line in path.read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and len(parts[0]) == 64:
            sums[parts[1]] = parts[0]
    return sums


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--version", required=True, help="release version, no leading v")
    ap.add_argument("--repo", required=True, help="owner/name")
    ap.add_argument("--tag", required=True, help="release tag, e.g. v0.3.0")
    ap.add_argument("--asset-dir", required=True, type=pathlib.Path)
    ap.add_argument(
        "--version-code",
        type=int,
        default=0,
        help="Android versionCode; CI's epoch-days value. 0 = unknown.",
    )
    args = ap.parse_args()

    base = f"https://github.com/{args.repo}/releases/download/{args.tag}"
    sums = read_sums(args.asset_dir / "SHA256SUMS")

    assets = {}
    for platform, pattern in PLATFORMS.items():
        name = pattern.format(version=args.version)
        path = args.asset_dir / name
        if not path.is_file():
            # A platform without an artifact in this release simply has no
            # entry; the client treats that as "no update here".
            print(f"update-manifest: skipping {platform}: {name} not in {args.asset_dir}",
                  file=sys.stderr)
            continue
        assets[platform] = {
            "name": name,
            "url": f"{base}/{name}",
            "sha256": sums.get(name, ""),
            "size": path.stat().st_size,
        }
        if not assets[platform]["sha256"]:
            print(f"update-manifest: WARNING {name} missing from SHA256SUMS",
                  file=sys.stderr)

    manifest = {
        "schema": 1,
        "channel": "stable",
        "version": args.version.lstrip("v"),
        "version_code": args.version_code,
        "published_at": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "notes_url": f"https://github.com/{args.repo}/releases/tag/{args.tag}",
        "assets": assets,
    }
    json.dump(manifest, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
