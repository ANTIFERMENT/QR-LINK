#!/usr/bin/env python3
"""Produce OTA update.json from an already signed APK."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import quote

def load_release_notes(version):
    filename = Path("release_notes") / f"v{version}.txt"
    if not filename.is_file():
        return f"QR LINK {version}. Подробное описание изменений для этой версии не предоставлено."
    notes = filename.read_text(encoding="utf-8").strip()
    if not notes:
        raise SystemExit("Empty release notes: " + str(filename))
    if len(notes) > 6000:
        raise SystemExit("Release notes too long: " + str(filename))
    return notes

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--github-output", required=True)
    args = parser.parse_args()

    if args.repo.casefold() != "antiferment/qr-link":
        raise SystemExit("Unexpected repository: " + args.repo)
    apk = Path(args.apk)
    if not apk.is_file():
        raise SystemExit("APK missing: " + str(apk))
    details = subprocess.check_output([args.aapt, "dump", "badging", str(apk)], text=True)
    match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", details, re.MULTILINE)
    if not match:
        raise SystemExit("Unable to parse APK version using aapt")
    package_name, code, version = match.groups()
    if not re.fullmatch(r"[A-Za-z0-9._-]+", version):
        raise SystemExit("Invalid versionName for a Git tag")
    tag = "v" + version
    sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    data = {
        "schema": 1,
        "packageName": package_name,
        "versionCode": int(code),
        "versionName": version,
        "apkUrl": f"https://github.com/{args.repo}/releases/download/{quote(tag)}/QR-LINK.apk",
        "sha256": sha,
        "sizeBytes": apk.stat().st_size,
        "publishedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "notes": load_release_notes(version)
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with open(args.github_output, "a", encoding="utf-8") as f:
        f.write("tag=" + tag + "\n")
        f.write("version=" + version + "\n")
        f.write("version_code=" + code + "\n")
    print("OTA metadata created:", package_name, version, code)

if __name__ == "__main__":
    main()
