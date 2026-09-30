import struct
import unittest
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path

spec = spec_from_file_location("normalizer", Path(__file__).with_name("normalize-macho-signature.py"))
module = module_from_spec(spec)
spec.loader.exec_module(module)


def fixture(vm_size=16384):
    header = struct.pack("<IIIIIIII", 0xFEEDFACF, 0x0100000C, 0, 6, 1, 72, 0, 0)
    segment = struct.pack("<II16sQQQQIIII", 0x19, 72, b"__LINKEDIT", 0, vm_size, 104, 8, 1, 1, 0, 0)
    return header + segment + b"payload!"


class SignatureReserveTests(unittest.TestCase):
    def test_resigning_vm_reserve_is_the_only_normalized_field(self):
        self.assertEqual(module.normalize(fixture(16384)), module.normalize(fixture(49152)))

    def test_payload_change_is_not_hidden(self):
        original = fixture()
        modified = original[:-1] + b"?"
        self.assertNotEqual(module.normalize(original), module.normalize(modified))

    def test_invalid_bounds_fail_closed(self):
        with self.assertRaises(ValueError):
            module.normalize(fixture()[:-1])

    def test_signed_input_is_rejected(self):
        signed = bytearray(fixture())
        struct.pack_into("<I", signed, 32, 0x1D)
        with self.assertRaises(ValueError):
            module.normalize(signed)


if __name__ == "__main__":
    unittest.main()
