#!/usr/bin/env python3
"""Normalize signature-induced LINKEDIT VM reserve on unsigned comparison copies.

codesign --remove-signature removes signature data but can leave the larger
__LINKEDIT vmsize allocated by re-signing. All remaining bytes stay untouched.
This output is for byte comparison only, never for distribution or execution.
"""
import pathlib
import struct
import sys


def normalize(data: bytes) -> bytes:
    if len(data) < 32 or len(data) > 16 * 1024 * 1024:
        raise ValueError("invalid Mach-O length")
    magic, cpu = struct.unpack_from("<II", data)
    if magic != 0xFEEDFACF or cpu not in (0x0100000C, 0x01000007):
        raise ValueError("expected thin arm64/x86_64 Mach-O")
    count, command_bytes = struct.unpack_from("<II", data, 16)
    end = 32 + command_bytes
    if count > 1024 or end > len(data):
        raise ValueError("invalid load command bounds")
    output = bytearray(data)
    cursor = 32
    found = False
    for _ in range(count):
        if cursor + 8 > end:
            raise ValueError("truncated command")
        command, size = struct.unpack_from("<II", data, cursor)
        if size < 8 or cursor + size > end:
            raise ValueError("invalid command size")
        if command == 0x1D:
            raise ValueError("remove the code signature before normalization")
        if command == 0x19:
            if size < 72:
                raise ValueError("truncated segment")
            segment = data[cursor + 8:cursor + 24].rstrip(b"\0")
            if segment == b"__LINKEDIT":
                if found:
                    raise ValueError("duplicate LINKEDIT segment")
                found = True
                vm_size, file_offset, file_size = struct.unpack_from("<QQQ", data, cursor + 32)
                if file_offset + file_size > len(data) or vm_size < file_size:
                    raise ValueError("invalid LINKEDIT bounds")
                page = 16384 if cpu == 0x0100000C else 4096
                canonical_size = (file_size + page - 1) // page * page
                struct.pack_into("<Q", output, cursor + 32, canonical_size)
        cursor += size
    if cursor != end or not found:
        raise ValueError("missing or invalid LINKEDIT commands")
    return bytes(output)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: normalize-macho-signature.py TEMPORARY_COPY")
    path = pathlib.Path(sys.argv[1])
    # Never mutate a signed build output, mounted app or released artifact.
    if not path.parent.name.startswith("veilark-chrome-check."):
        raise SystemExit("refusing to modify a non-comparison file")
    path.write_bytes(normalize(path.read_bytes()))
