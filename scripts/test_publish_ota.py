import runpy
import errno
import ast
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

publisher = runpy.run_path(str(Path(__file__).with_name("ota_origin.py")))
# Execute the actual staging function without importing optional deployment
# dependencies. CI contract tests use only the standard library and no network.
tree = ast.parse(Path(__file__).with_name("publish_ota.py").read_text(encoding="utf-8"))
stage = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "stage_release_apk")
release = types.ModuleType("ota_publisher_under_test")
release.__dict__.update(Path=Path, errno=errno, connect_node=None,
                        upload_resumable=None, remote_sha256=None)
exec(compile(ast.Module(body=[stage], type_ignores=[]), "publish_ota.py", "exec"), release.__dict__)


class AndroidOtaOriginTest(unittest.TestCase):
    def test_installed_private_client_origin_is_accepted(self):
        self.assertEqual(publisher["OTA_ORIGIN"], publisher["validate_ota_origin"]("https://nl2.senyasenyavski.uk:2096/"))

    def test_browser_catalogue_origin_is_not_an_ota_origin(self):
        for value in ("https://nl2.senyasenyavski.uk", "https://nl2.senyasenyavski.uk:443"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                publisher["validate_ota_origin"](value)

    def test_untrusted_origins_and_paths_are_rejected(self):
        for value in ("http://nl2.senyasenyavski.uk:2096", "https://example.org:2096", "https://nl2.senyasenyavski.uk:2096/veilark", "https://user@nl2.senyasenyavski.uk:2096", "https://nl2.senyasenyavski.uk:2096?redirect=1", "https://nl2.senyasenyavski.uk:2096#download", "https://nl2.senyasenyavski.uk:2096.evil.example", ""):
            with self.subTest(value=value), self.assertRaises(ValueError):
                publisher["validate_ota_origin"](value)


class AndroidOtaImmutableReleaseTest(unittest.TestCase):
    def setUp(self):
        self.client = Mock()
        self.sftp = self.client.open_sftp.return_value
        self.connect = patch.object(release, "connect_node", return_value=self.client).start()
        self.upload = patch.object(release, "upload_resumable").start()
        self.digest = patch.object(release, "remote_sha256", return_value="ABC").start()
        self.addCleanup(patch.stopall)

    def stage(self, code=80, name="0.9.0-beta10"):
        release.stage_release_apk({}, Path("candidate.apk"), "/ota/release.apk", "/ota/.uploading",
                                  "ABC", code, name,
                                  {"versionCode": 79, "versionName": "0.9.0-beta9"})

    def assert_no_remote_mutation(self):
        self.upload.assert_not_called()
        self.sftp.posix_rename.assert_not_called()

    def test_equal_and_lower_counters_reject_before_connect(self):
        for code in (78, 79):
            with self.subTest(code=code), self.assertRaises(RuntimeError):
                self.stage(code=code)
        self.connect.assert_not_called()
        self.assert_no_remote_mutation()

    def test_higher_counter_cannot_reuse_live_name(self):
        with self.assertRaises(RuntimeError):
            self.stage(name="0.9.0-beta9")
        self.connect.assert_not_called()
        self.assert_no_remote_mutation()

    def test_existing_historical_url_with_changed_or_unverifiable_bytes_rejects(self):
        for digest in ("DIFFERENT", ""):
            self.digest.return_value = digest
            with self.subTest(digest=digest), self.assertRaises(RuntimeError):
                self.stage()
        self.assert_no_remote_mutation()

    def test_identical_staged_apk_is_reused_without_upload(self):
        self.stage()
        self.digest.assert_called_once_with(self.client, "/ota/release.apk")
        self.assert_no_remote_mutation()

    def test_unknown_stat_failure_never_means_absent(self):
        self.sftp.stat.side_effect = OSError(errno.EACCES, "denied")
        with self.assertRaises(OSError):
            self.stage()
        self.assert_no_remote_mutation()

    def test_new_url_uploads_and_promotes_only_after_digest_matches(self):
        self.sftp.stat.side_effect = FileNotFoundError(errno.ENOENT, "missing")
        self.stage()
        self.upload.assert_called_once()
        self.digest.assert_called_once_with(self.client, "/ota/.uploading")
        self.sftp.posix_rename.assert_called_once_with("/ota/.uploading", "/ota/release.apk")

    def test_corrupted_upload_is_not_promoted(self):
        self.sftp.stat.side_effect = FileNotFoundError(errno.ENOENT, "missing")
        self.digest.return_value = "DIFFERENT"
        with self.assertRaises(RuntimeError):
            self.stage()
        self.upload.assert_called_once()
        self.sftp.posix_rename.assert_not_called()


if __name__ == "__main__":
    unittest.main()
