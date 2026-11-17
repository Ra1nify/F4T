#!/usr/bin/env python3
"""Point patches-bundle.json at a release: version, download URL, date and signing key."""
import datetime
import json
import sys


def main():
    if len(sys.argv) < 5:
        sys.exit("usage: update_manifest.py <manifest> <version> <download_url> <public_key_file>")

    path, version, download_url, public_key_file = sys.argv[1:5]

    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)

    manifest["version"] = version
    manifest["download_url"] = download_url
    manifest["created_at"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    with open(public_key_file, encoding="utf-8") as handle:
        manifest["public_key"] = handle.read().strip()

    with open(path, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, ensure_ascii=False)
        handle.write("\n")

    print(f"update_manifest: {path} -> version {version}")


if __name__ == "__main__":
    main()
