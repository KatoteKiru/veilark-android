import runpy
import unittest
from pathlib import Path

publisher = runpy.run_path(str(Path(__file__).with_name("ota_origin.py")))


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


if __name__ == "__main__":
    unittest.main()
