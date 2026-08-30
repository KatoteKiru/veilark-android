#!/usr/bin/env python3
"""Fail closed when an Android artifact contains bundled access material."""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path


PROFILE_URI = re.compile(
    rb"(?i)(?:tt|vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard)://"
    rb"[^\x00-\x20\"'<>]{12,}"
)
SUBSCRIPTION_URL = re.compile(
    rb"(?i)https?://[^\x00-\x20\"'<>]{3,}/(?:sub|subscription)/"
    rb"[^\x00-\x20\"'<>]{8,}"
)
FORBIDDEN_ENTRY_PARTS = ("builtin_trust_profiles", "embedded_trust_profiles")
MAX_ENTRY_SIZE = 256 * 1024 * 1024


def scan(apk: Path) -> list[str]:
    findings: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            lowered = info.filename.lower()
            if any(marker in lowered for marker in FORBIDDEN_ENTRY_PARTS):
                findings.append(f"forbidden bundled-profile entry: {info.filename}")
            if info.is_dir() or info.file_size == 0:
                continue
            if info.file_size > MAX_ENTRY_SIZE:
                findings.append(f"entry exceeds scan limit: {info.filename}")
                continue
            payload = archive.read(info)
            if PROFILE_URI.search(payload):
                findings.append(f"embedded access URI in: {info.filename}")
            if SUBSCRIPTION_URL.search(payload):
                findings.append(f"embedded subscription URL in: {info.filename}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2

    findings = scan(args.apk)
    if findings:
        print("Artifact access-material scan failed:", file=sys.stderr)
        for finding in findings:
            print(f"- {finding}", file=sys.stderr)
        return 1
    print(f"Artifact access-material scan passed: {args.apk.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
