"""Check vendored TrustTunnel AAR against its recorded source/build receipt."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import zipfile


ROOT = Path(__file__).resolve().parents[1]


def check_hash(data: bytes, expected: str, label: str) -> None:
    if hashlib.sha256(data).hexdigest() != expected.lower():
        raise ValueError(f"SHA-256 mismatch: {label}")


def verify(root: Path = ROOT) -> None:
    vendor = root / "vendor" / "trusttunnel-android"
    receipt = json.loads((vendor / "UPSTREAM.json").read_text(encoding="utf-8"))
    if receipt.get("schema") not in (3, 4, 5):
        raise ValueError("Unsupported vendor receipt schema")
    if receipt["schema"] == 5:
        expected_patches = [
            "patches/0001-android-per-app-routing.patch",
            "patches/0002-android-lifecycle-hardening.patch",
            "patches/0003-post-close-terminal-fence.patch",
            "patches/0004-http2-flow-control.patch",
            "patches/0005-android-metering-inheritance.patch",
        ]
        if [patch["path"] for patch in receipt["patches"]] != expected_patches:
            raise ValueError("Unexpected schema 5 patch inventory/order")
    hashes = receipt["sha256"]
    aar = root / "app" / "libs" / "trusttunnel-client.aar"
    check_hash(aar.read_bytes(), hashes["aar"], "AAR")
    entries = {
        "classes.jar": "classes_jar",
        "AndroidManifest.xml": "android_manifest_xml",
        "R.txt": "r_txt",
        "proguard.txt": "proguard_txt",
        "assets/logback.xml": "assets_logback_xml",
        "META-INF/com/android/build/gradle/aar-metadata.properties": "aar_metadata_properties",
        "jni/arm64-v8a/libtrusttunnel_android.so": "jni_arm64_v8a",
        "jni/armeabi-v7a/libtrusttunnel_android.so": "jni_armeabi_v7a",
    }
    with zipfile.ZipFile(aar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("AAR contains duplicate entries")
        expected_native = {name for name in entries if name.startswith("jni/")}
        actual_native = {name for name in names if name.startswith("jni/") and name.endswith(".so")}
        if actual_native != expected_native:
            raise ValueError("Unexpected native ABI/library inventory")
        for name, key in entries.items():
            if archive.getinfo(name).file_size > 256 * 1024 * 1024:
                raise ValueError(f"Oversized AAR entry: {name}")
            data = archive.read(name)
            check_hash(data, hashes[key], name)
            if name in expected_native:
                elf_class, machine = (2, 183) if "arm64-v8a" in name else (1, 40)
                if len(data) < 20 or data[:6] != b"\x7fELF" + bytes((elf_class, 1)):
                    raise ValueError(f"Wrong ELF class/endianness: {name}")
                if struct.unpack_from("<H", data, 18)[0] != machine:
                    raise ValueError(f"Wrong ELF machine: {name}")
    for patch in receipt["patches"]:
        path = (vendor / patch["path"]).resolve()
        path.relative_to((vendor / "patches").resolve())
        canonical = path.read_bytes().replace(b"\r\n", b"\n")
        expected = patch.get("sha256_lf_normalized", patch.get("sha256"))
        if not expected:
            raise ValueError("Missing canonical patch checksum")
        check_hash(canonical, expected, patch["path"])


if __name__ == "__main__":
    verify()
    print("TrustTunnel AAR, native ABIs, classes and canonical patch hashes verified")
