"""Local Windows source-excerpt contract tests; no network or project secret imports."""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def digest(data):
    return hashlib.sha256(data).hexdigest()


def function(text, signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 0
    for end in range(opening, len(text)):
        if text[end] == "{":
            depth += 1
        elif text[end] == "}":
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError("Unterminated excerpt: " + signature)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Patched TrustTunnelClient source checkout")
    parser.add_argument("--headers", required=True, type=Path, help="Directory containing nghttp2/nghttp2.h and nghttp2ver.h")
    parser.add_argument("--nghttp2-dll", required=True, type=Path, help="Existing Windows x64 nghttp2 runtime DLL")
    parser.add_argument("--vcvars", required=True, type=Path, help="Existing MSVC vcvars64.bat")
    parser.add_argument("--output", required=True, type=Path, help="Local directory for generated code/build evidence")
    parser.add_argument("--expect-dll-sha256", help="Optional dependency hash pin, checked before DLL loading")
    args = parser.parse_args()
    assert os.name == "nt", "This recipe uses the existing Windows MSVC toolchain"
    args.output.mkdir(parents=True, exist_ok=True)
    dll_sha = digest(args.nghttp2_dll.read_bytes())
    if args.expect_dll_sha256:
        assert dll_sha == args.expect_dll_sha256, "Runtime DLL hash mismatch"
    header = args.headers / "nghttp2/nghttp2.h"
    version_header = args.headers / "nghttp2/nghttp2ver.h"
    assert header.is_file() and version_header.is_file() and args.vcvars.is_file()
    header_version = re.search(r'#define\s+NGHTTP2_VERSION\s+"([^"]+)"', version_header.read_text())[1]
    paths = {"net": "net/src/http2.cpp", "core": "core/src/http2_upstream.cpp", "udp": "core/src/http_udp_multiplexer.cpp"}
    net, core, udp = [((args.source / paths[key]).read_text()) for key in ("net", "core", "udp")]
    start = net.index("    case NGHTTP2_WINDOW_UPDATE:", net.index("static int on_frame_recv_callback("))
    end = net.index("    default:", start)
    excerpts = {
        "AVAILABLE": function(net, "size_t http_session_available_to_write("),
        "FRAME_CASES": net[start:end],
        "UDP_STREAM": function(udp, "std::optional<uint64_t> HttpUdpMultiplexer::get_stream_id("),
        "UDP_SENT": function(udp, "void HttpUdpMultiplexer::report_sent_bytes("),
        "CORE_STREAM": function(core, "std::optional<uint32_t> Http2Upstream::get_stream_id("),
        "CORE_AVAILABLE": function(core, "size_t Http2Upstream::available_to_send("),
        "CORE_CREDIT": function(core, "void Http2Upstream::report_write_credit("),
    }
    source = Path(__file__).with_name("companion.cpp.in").read_text()
    for key, value in excerpts.items():
        source = source.replace("// EXCERPT_" + key, value)
    assert "// EXCERPT_" not in source
    source_sha = digest(source.encode())
    # A fresh build directory avoids replacing a loaded or scanner-locked import library.
    stage = args.output / source_sha[:16]
    if stage.exists():
        stage = stage.with_name(stage.name + "-" + str(os.getpid()))
    stage.mkdir()
    cpp = stage / "companion.cpp"
    cpp.write_text(source)
    dll_directory = os.add_dll_directory(str(args.nghttp2_dll.resolve().parent))
    runtime = ctypes.CDLL(str(args.nghttp2_dll.resolve()))

    class Info(ctypes.Structure):
        _fields_ = [("age", ctypes.c_int), ("version_num", ctypes.c_int),
                    ("version_str", ctypes.c_char_p), ("proto_str", ctypes.c_char_p)]

    runtime.nghttp2_version.argtypes = [ctypes.c_int]
    runtime.nghttp2_version.restype = ctypes.POINTER(Info)
    runtime_version = runtime.nghttp2_version(0).contents.version_str.decode()
    symbols = sorted(set(re.findall(r"\b(nghttp2_[a-zA-Z0-9_]+)\s*\(", source)))
    for symbol in symbols:
        assert getattr(runtime, symbol), "Missing runtime API: " + symbol
    definition = stage / "nghttp2.def"
    definition.write_text("LIBRARY " + args.nghttp2_dll.name + "\nEXPORTS\n" + "\n".join(symbols) + "\n")
    script = stage / "compile-and-run.cmd"
    script.write_text(
        '@echo off\ncall "' + str(args.vcvars.resolve()) + '" >nul\nif errorlevel 1 exit /b 1\n'
        'lib /nologo /def:"' + str(definition.resolve()) + '" /machine:x64 /out:"' + str(stage.resolve() / "nghttp2.lib") + '"\n'
        'if errorlevel 1 exit /b 1\ncl /nologo /std:c++17 /EHsc /O2 /W4 /Dssize_t=__int64 /I"' + str(args.headers.resolve()) + '" "' + str(cpp.resolve()) + '" /Fo"' + str(stage.resolve() / "companion.obj") + '" /Fe"' + str(stage.resolve() / "companion.exe") + '" /link "' + str(stage.resolve() / "nghttp2.lib") + '"\n'
        'if errorlevel 1 exit /b 1\nset "PATH=' + str(args.nghttp2_dll.resolve().parent) + ';%PATH%"\n"' + str(stage.resolve() / "companion.exe") + '"\n'
    )
    run = subprocess.run(["cmd.exe", "/d", "/c", str(script.resolve())], cwd=stage,
                         capture_output=True, text=True, timeout=120)
    (stage / "compile-run.log").write_text(run.stdout + "\n" + run.stderr)
    assert run.returncode == 0, "Local compile/run failed; inspect generated compile-run.log"
    result = json.loads(next(line for line in reversed(run.stdout.splitlines()) if line.startswith("{")))
    result.update({"execution": "LOCAL_WINDOWS_ONLY", "source_paths": paths,
                   "excerpt_sha256": {key: digest(value.encode()) for key, value in excerpts.items()},
                   "source_sha256": source_sha, "nghttp2_runtime": runtime_version,
                   "nghttp2_runtime_sha256": dll_sha, "nghttp2_header_version": header_version,
                   "nghttp2_header_sha256": digest(header.read_bytes()),
                   "nghttp2_version_header_sha256": digest(version_header.read_bytes()),
                   "platform_ssize_t_alias": "signed64", "remote_transfer": False,
                   "production_access": False, "global_package_install": False})
    (stage / "result.json").write_text(json.dumps(result, indent=2))
    assert result["tcp_client_closed_blocked"], "Client-closed connection was notified"
    print(json.dumps(result, indent=2))
    dll_directory.close()


if __name__ == "__main__":
    main()
