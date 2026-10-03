import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from verify_trusttunnel_vendor import verify


class VendorVerificationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.vendor = self.root / "vendor/trusttunnel-android"
        (self.vendor / "patches").mkdir(parents=True)
        (self.root / "app/libs").mkdir(parents=True)
        self.payloads = {
            "classes.jar": b"classes",
            "AndroidManifest.xml": b"manifest",
            "R.txt": b"",
            "proguard.txt": b"rules",
            "assets/logback.xml": b"log",
            "META-INF/com/android/build/gradle/aar-metadata.properties": b"meta",
        }
        self.keys = ["classes_jar", "android_manifest_xml", "r_txt", "proguard_txt",
                     "assets_logback_xml", "aar_metadata_properties"]
        for abi, elf_class, machine, key in (
            ("arm64-v8a", 2, 183, "jni_arm64_v8a"),
            ("armeabi-v7a", 1, 40, "jni_armeabi_v7a"),
        ):
            header = bytearray(20)
            header[:6] = b"\x7fELF" + bytes((elf_class, 1))
            struct.pack_into("<H", header, 18, machine)
            self.payloads[f"jni/{abi}/libtrusttunnel_android.so"] = bytes(header)
            self.keys.append(key)
        (self.vendor / "patches/one.patch").write_bytes(b"line\r\n")
        self.receipt = {"schema": 3, "sha256": {}, "patches": [{
            "path": "patches/one.patch",
            "sha256": hashlib.sha256(b"line\n").hexdigest(),
        }]}
        self.save()

    def save(self):
        aar = self.root / "app/libs/trusttunnel-client.aar"
        with zipfile.ZipFile(aar, "w") as archive:
            for name, data in self.payloads.items():
                archive.writestr(name, data)
        self.receipt["sha256"] = {
            key: hashlib.sha256(data).hexdigest()
            for key, data in zip(self.keys, self.payloads.values())
        }
        self.receipt["sha256"]["aar"] = hashlib.sha256(aar.read_bytes()).hexdigest()
        self.write_receipt()

    def write_receipt(self):
        (self.vendor / "UPSTREAM.json").write_text(json.dumps(self.receipt), encoding="utf-8")

    def test_valid_and_crlf_patch(self):
        verify(self.root)

    def test_schema_four_canonical_patch_key(self):
        self.receipt["schema"] = 4
        patch = self.receipt["patches"][0]
        patch["sha256_lf_normalized"] = patch.pop("sha256")
        self.write_receipt()
        verify(self.root)

    def test_aar_tampering(self):
        self.receipt["sha256"]["aar"] = "0" * 64
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, "AAR"):
            verify(self.root)

    def test_wrong_native_machine(self):
        name = "jni/arm64-v8a/libtrusttunnel_android.so"
        data = bytearray(self.payloads[name])
        struct.pack_into("<H", data, 18, 40)
        self.payloads[name] = bytes(data)
        self.save()
        with self.assertRaisesRegex(ValueError, "machine"):
            verify(self.root)

    def test_extra_native_library(self):
        self.payloads["jni/x86/libunexpected.so"] = b"unexpected"
        self.save()
        with self.assertRaisesRegex(ValueError, "inventory"):
            verify(self.root)

    def test_patch_tampering(self):
        (self.vendor / "patches/one.patch").write_bytes(b"changed\n")
        with self.assertRaisesRegex(ValueError, "one.patch"):
            verify(self.root)

    def test_patch_path_escape(self):
        self.receipt["patches"][0]["path"] = "../../outside.patch"
        self.write_receipt()
        with self.assertRaises(ValueError):
            verify(self.root)


if __name__ == "__main__":
    unittest.main()
