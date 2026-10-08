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

    def use_schema_five(self):
        self.receipt["schema"] = 5
        self.receipt["patches"] = []
        for name in (
            "0001-android-per-app-routing.patch",
            "0002-android-lifecycle-hardening.patch",
            "0003-post-close-terminal-fence.patch",
            "0004-http2-flow-control.patch",
            "0005-android-metering-inheritance.patch",
        ):
            content = f"patch {name}\n".encode()
            (self.vendor / "patches" / name).write_bytes(content.replace(b"\n", b"\r\n"))
            self.receipt["patches"].append({
                "path": f"patches/{name}",
                "sha256_lf_normalized": hashlib.sha256(content).hexdigest(),
            })
        self.write_receipt()

    def test_schema_five_verifies_all_five_canonical_patches(self):
        self.use_schema_five()
        verify(self.root)

    def test_schema_five_metering_patch_tampering(self):
        self.use_schema_five()
        (self.vendor / "patches/0005-android-metering-inheritance.patch").write_bytes(b"tampered\n")
        with self.assertRaisesRegex(ValueError, "0005-android-metering"):
            verify(self.root)

    def test_schema_five_cannot_omit_metering_patch(self):
        self.use_schema_five()
        self.receipt["patches"].pop()
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, "inventory/order"):
            verify(self.root)

    def test_schema_five_requires_patch_order(self):
        self.use_schema_five()
        self.receipt["patches"][3], self.receipt["patches"][4] = self.receipt["patches"][4], self.receipt["patches"][3]
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, "inventory/order"):
            verify(self.root)

    def test_schema_five_classes_tampering_still_rejected(self):
        self.use_schema_five()
        self.payloads["classes.jar"] = b"changed Java adapter"
        aar = self.root / "app/libs/trusttunnel-client.aar"
        with zipfile.ZipFile(aar, "w") as archive:
            for name, data in self.payloads.items():
                archive.writestr(name, data)
        self.receipt["sha256"]["aar"] = hashlib.sha256(aar.read_bytes()).hexdigest()
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, "classes.jar"):
            verify(self.root)

    def test_unknown_schema_remains_rejected(self):
        self.receipt["schema"] = 6
        self.write_receipt()
        with self.assertRaisesRegex(ValueError, "Unsupported"):
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
