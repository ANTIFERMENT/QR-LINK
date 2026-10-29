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
FORBIDDEN = (".github/", "scripts/", "ota/", ".git/")
SOURCE = ROOT / "QR_LINK_SOURCE.zip"

def fail(msg):
    raise SystemExit("QR LINK import: " + msg)

def main():
    if (ROOT / "app").exists():
        print("Android source directory already present; ZIP importer not needed")
        return
    if not SOURCE.is_file():
        fail("No app/ or QR_LINK_SOURCE.zip. Upload Android source ZIP.")
    with zipfile.ZipFile(SOURCE) as z:
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
    print("QR LINK Gradle source imported safely")

if __name__ == "__main__":
    main()
