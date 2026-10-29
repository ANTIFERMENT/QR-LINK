#!/usr/bin/env python3
"""Expand a QR LINK Gradle source ZIP into the Actions workspace, safely."""
from pathlib import Path, PurePosixPath
import os
import re
import sys
import zipfile

ROOT = Path.cwd()
MAX_TOTAL = 150 * 1024 * 1024
MAX_FILE = 70 * 1024 * 1024
FORBIDDEN = (".github/", "scripts/", "ota/", ".git/", "docs/", "tests/")
SOURCE = ROOT / "QR_LINK_SOURCE.zip"


def find_source():
    """Accept the named source ZIP or exactly one uploaded QR LINK Android source ZIP."""
    if SOURCE.is_file():
        return SOURCE
    candidates = sorted(p for p in ROOT.glob("QR_LINK_v*.zip") if p.is_file())
    valid = []
    for path in candidates:
        try:
            with zipfile.ZipFile(path) as z:
                names = z.namelist()
                if (any(n.endswith(("settings.gradle", "settings.gradle.kts")) for n in names)
                        and any(n.endswith("app/src/main/AndroidManifest.xml") for n in names)):
                    valid.append(path)
        except (zipfile.BadZipFile, OSError):
            continue
    if len(valid) == 1:
        print("Auto-selected uploaded QR LINK source:", valid[0].name)
        return valid[0]
    if len(valid) > 1:
        fail("Multiple versioned source ZIP files: keep only one or upload QR_LINK_SOURCE.zip")
    fail("No app/ or Android source ZIP found. Upload QR_LINK_SOURCE.zip or QR_LINK_v*.zip")


def fail(msg):
    raise SystemExit("QR LINK import: " + msg)

def main():
    if (ROOT / "app").exists():
        print("Android source directory already present; ZIP importer not needed")
        return
    source = find_source()
    with zipfile.ZipFile(source) as z:
        infos = [x for x in z.infolist() if not x.is_dir()]
        names = [x.filename for x in infos]
        roots = sorted({n[:-len("settings.gradle")] for n in names if n.endswith("settings.gradle")}
                       | {n[:-len("settings.gradle.kts")] for n in names if n.endswith("settings.gradle.kts")}, key=len)
        if not roots:
            fail("ZIP does not contain settings.gradle or settings.gradle.kts")
        prefix = roots[0]
        if not any(n.startswith(prefix + "app/") for n in names):
            fail("ZIP does not contain an app/ module under its Gradle root")
        size_total = 0
        written = 0
        for info in infos:
            name = info.filename
            if not name.startswith(prefix):
                continue
            relative = name[len(prefix):]
            if not relative:
                continue
            relative = relative.replace("\\", "/")
            p = PurePosixPath(relative)
            if p.is_absolute() or ".." in p.parts or ":" in relative or "\x00" in relative:
                fail("unsafe ZIP path")
            if (relative.startswith(FORBIDDEN) or relative in ("local.properties", ".gitignore", "README.md", "README_RU.md")
                    or relative.startswith(".gradle/") or "/build/" in relative
                    or relative.endswith((".jks", ".keystore", ".p12", ".pfx", ".pem", ".key"))):
                print("Skipping generated, private or repository-managed file:", relative)
                continue
            if relative.startswith(FORBIDDEN):
                print("Keeping repository-managed file, skipping:", relative)
                continue
            if info.external_attr >> 16 & 0o170000 == 0o120000:
                fail("ZIP contains symlink")
            if info.file_size > MAX_FILE:
                fail("individual file too large")
            size_total += info.file_size
            if size_total > MAX_TOTAL:
                fail("source ZIP expanded size limit exceeded")
            output = ROOT.joinpath(*p.parts)
            if output.exists():
                fail("would overwrite checked-in file: " + relative)
            output.parent.mkdir(parents=True, exist_ok=True)
            with z.open(info) as src, output.open("wb") as dst:
                while True:
                    block = src.read(1024 * 1024)
                    if not block:
                        break
                    dst.write(block)
            written += 1
        print("Imported files:", written)
        manifest = ROOT / "app/src/main/AndroidManifest.xml"
        if not manifest.exists():
            fail("AndroidManifest.xml missing after source import")
    gradle = ROOT / "app/build.gradle"
    gradle_kts = ROOT / "app/build.gradle.kts"
    namespace = None
    for path in (gradle, gradle_kts):
        if path.exists():
            build_text = path.read_text(encoding="utf-8", errors="replace")
            for pattern in (r'\bnamespace\s*(?:=\s*)?["\']([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+)["\']',
                            r'\bapplicationId\s*(?:=\s*)?["\']([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+)["\']'):
                match = re.search(pattern, build_text)
                if match:
                    namespace = match.group(1)
                    break
        if namespace:
            break
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    xml = manifest.read_text(encoding="utf-8", errors="replace")
    for permission in ("android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"):
        if permission not in xml:
            tag = re.search(r"<manifest\b[^>]*>", xml, flags=re.DOTALL)
            if not tag:
                fail("Invalid AndroidManifest.xml")
            xml = xml[:tag.end()] + '\n    <uses-permission android:name="' + permission + '"/>' + xml[tag.end():]
    manifest.write_text(xml, encoding="utf-8")
    existing = list((ROOT / "app/src/main/java").rglob("QrLinkOta.java")) + list((ROOT / "app/src/main/java").rglob("UpdateManager.java"))
    if existing:
        print("Existing OTA class detected; preserving project version:", existing[0])
    elif namespace:
        template = ROOT / "ota/QrLinkOta.java.template"
        if template.is_file():
            target = ROOT / "app/src/main/java" / Path(*namespace.split(".")) / "QrLinkOta.java"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(
                template.read_text(encoding="utf-8").replace("PACKAGE_PLACEHOLDER", namespace),
                encoding="utf-8")
            print("Inserted OTA engine into package:", namespace)
    else:
        print("WARNING: No namespace in app/build.gradle; OTA Java engine not injected")
    print("QR LINK Gradle source imported safely")

if __name__ == "__main__":
    main()
