from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import uuid
from urllib.request import Request, urlopen

import paramiko
from cryptography.hazmat.primitives import serialization


ROOT = Path(__file__).resolve().parents[1]
OTA_DIR = ROOT / "build" / "ota"
UPLOAD_CHUNK_SIZE = 256 * 1024
UPLOAD_ATTEMPTS = 8
MAX_RELEASE_NOTES_LENGTH = 500


def canonical_payload_v2(fields: list[str]) -> bytes:
    payload = bytearray()
    for value in fields:
        encoded = value.encode("utf-8")
        payload.extend(str(len(encoded)).encode("ascii"))
        payload.extend(b":")
        payload.extend(encoded)
        payload.extend(b"\n")
    return bytes(payload)


def read_env(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip().strip("\"'")
    return result


def connect_node(env: dict[str, str]) -> paramiko.SSHClient:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        env["OTA_SSH_HOST"],
        username=env["OTA_SSH_USER"],
        password=env["OTA_SSH_PASSWORD"],
        timeout=20,
        banner_timeout=20,
        auth_timeout=20,
    )
    client.get_transport().set_keepalive(10)
    return client


def find_android_tool(name: str) -> Path:
    executable = f"{name}.bat" if os.name == "nt" else name
    discovered = shutil.which(executable)
    if discovered:
        return Path(discovered)
    configured_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk = Path(configured_sdk) if configured_sdk else Path("__missing_android_sdk__")
    if not sdk.is_dir() and os.name == "nt":
        sdk = Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
    roots = [sdk / "build-tools", sdk / "cmdline-tools"]
    matches = [path for root in roots if root.is_dir() for path in root.rglob(executable)]
    if not matches:
        raise FileNotFoundError(f"Android tool not found: {executable}")
    return sorted(matches, reverse=True)[0]


def run_android_tool(tool: Path, *args: str) -> str:
    command = [str(tool), *args]
    if os.name == "nt" and tool.suffix.lower() == ".bat":
        command = ["cmd.exe", "/d", "/c", *command]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout + result.stderr


def verify_local_apk(
    apk: Path,
    version_code: int,
    version_name: str,
    expected_package: str,
    expected_signer_sha256: str,
) -> None:
    analyzer = find_android_tool("apkanalyzer")
    signer = find_android_tool("apksigner")
    package = run_android_tool(analyzer, "manifest", "application-id", str(apk)).strip()
    actual_name = run_android_tool(analyzer, "manifest", "version-name", str(apk)).strip()
    actual_code = run_android_tool(analyzer, "manifest", "version-code", str(apk)).strip()
    if package != expected_package or actual_name != version_name or actual_code != str(version_code):
        raise RuntimeError(
            f"APK identity mismatch: package={package}, version={actual_name} ({actual_code})"
        )
    signer_report = run_android_tool(signer, "verify", "--verbose", "--print-certs", str(apk))
    normalized = signer_report.lower()
    if "verified using v2 scheme (apk signature scheme v2): true" not in normalized:
        raise RuntimeError("APK Signature Scheme v2 verification failed")
    if f"certificate sha-256 digest: {expected_signer_sha256.lower()}" not in normalized:
        raise RuntimeError("APK signer does not match the established OTA signer")


def download_sha256(url: str) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with urlopen(Request(url, method="GET"), timeout=60) as response:
        if response.status != 200:
            raise RuntimeError(f"Published APK returned HTTP {response.status}")
        while chunk := response.read(UPLOAD_CHUNK_SIZE):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest().upper(), size


def remote_sha256(client: paramiko.SSHClient, path: str) -> str:
    _, stdout, stderr = client.exec_command(f"sha256sum -- {shlex.quote(path)}")
    digest = stdout.read().decode("ascii", errors="replace").split(maxsplit=1)
    errors = stderr.read()
    if errors or stdout.channel.recv_exit_status() != 0 or not digest:
        return ""
    return digest[0].upper()


def upload_resumable(
    env: dict[str, str],
    local: Path,
    remote_temporary: str,
) -> None:
    expected_size = local.stat().st_size
    last_error: Exception | None = None
    for _ in range(UPLOAD_ATTEMPTS):
        client = None
        try:
            client = connect_node(env)
            sftp = client.open_sftp()
            try:
                try:
                    offset = sftp.stat(remote_temporary).st_size
                except OSError:
                    offset = 0
                if offset > expected_size:
                    sftp.remove(remote_temporary)
                    offset = 0
                with local.open("rb") as source:
                    source.seek(offset)
                    with sftp.file(remote_temporary, "ab") as destination:
                        destination.set_pipelined(True)
                        while True:
                            chunk = source.read(UPLOAD_CHUNK_SIZE)
                            if not chunk:
                                break
                            destination.write(chunk)
                        destination.flush()
                if sftp.stat(remote_temporary).st_size == expected_size:
                    return
            finally:
                sftp.close()
        except Exception as error:
            last_error = error
        finally:
            if client is not None:
                client.close()
    raise RuntimeError("Resumable APK upload failed") from last_error


def main() -> None:
    parser = argparse.ArgumentParser(description="Sign and atomically publish a Veilark OTA build")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--notes", required=True)
    parser.add_argument("--origin", required=True, help="Public HTTPS origin without a path")
    parser.add_argument("--expected-package", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)
    parser.add_argument("--signing-key", type=Path, required=True)
    parser.add_argument(
        "--server-env",
        type=Path,
        help="SSH environment file; required unless --prepare-only is used",
    )
    parser.add_argument("--remote-dir", default="/var/www/html/veilark")
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Create and verify signed local OTA artifacts without uploading",
    )
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        raise FileNotFoundError(apk)
    if args.version_code < 1 or not args.version_name:
        raise ValueError("Invalid version")
    notes = args.notes.strip()
    if not notes or len(notes) > MAX_RELEASE_NOTES_LENGTH:
        raise ValueError("Release notes must contain 1-500 characters")
    origin = args.origin.rstrip("/")
    if not origin.startswith("https://"):
        raise ValueError("OTA origin must use HTTPS")
    expected_signer = args.expected_signer_sha256.strip().lower()
    if re.fullmatch(r"[0-9a-f]{64}", expected_signer) is None:
        raise ValueError("Expected signer SHA-256 must contain 64 hexadecimal characters")
    verify_local_apk(
        apk,
        args.version_code,
        args.version_name,
        args.expected_package,
        expected_signer,
    )

    apk_name = f"veilark-{args.version_name}.apk"
    apk_bytes = apk.read_bytes()
    sha256 = hashlib.sha256(apk_bytes).hexdigest().upper()
    size = len(apk_bytes)
    apk_url = f"{origin}/veilark/{apk_name}"
    legacy_payload = (
        f"{args.version_code}\n{args.version_name}\n{apk_url}\n{sha256}\n{size}"
    ).encode("utf-8")
    payload_v2 = canonical_payload_v2(
        [
            "veilark-update-v2",
            str(args.version_code),
            args.version_name,
            apk_url,
            sha256,
            str(size),
            notes,
        ]
    )

    private_key = serialization.load_pem_private_key(
        args.signing_key.resolve().read_bytes(),
        password=None,
    )
    legacy_signature = private_key.sign(legacy_payload)
    signature_v2 = private_key.sign(payload_v2)
    private_key.public_key().verify(legacy_signature, legacy_payload)
    private_key.public_key().verify(signature_v2, payload_v2)
    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": apk_url,
        "sha256": sha256,
        "size": size,
        "notes": notes,
        "signature": base64.b64encode(legacy_signature).decode("ascii"),
        "signatureV2": base64.b64encode(signature_v2).decode("ascii"),
    }

    OTA_DIR.mkdir(parents=True, exist_ok=True)
    local_apk = OTA_DIR / apk_name
    local_manifest = OTA_DIR / "manifest.json"
    local_payload = OTA_DIR / "payload-v2.bin"
    local_signature = OTA_DIR / "signature-v2.bin"
    local_apk.write_bytes(apk_bytes)
    local_manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    local_payload.write_bytes(payload_v2)
    local_signature.write_bytes(signature_v2)

    result = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": apk_url,
        "sha256": sha256,
        "size": size,
    }
    if args.prepare_only:
        print(json.dumps({**result, "published": False}, ensure_ascii=False))
        return

    if args.server_env is None:
        raise ValueError("--server-env is required when publishing")
    env = read_env(args.server_env.resolve())
    remote_dir = args.remote_dir.rstrip("/")
    remote_apk = f"{remote_dir}/{apk_name}"
    remote_apk_tmp = f"{remote_dir}/.{apk_name}.uploading"
    remote_manifest_tmp = f"{remote_dir}/.manifest.json.{uuid.uuid4().hex}.tmp"
    client = connect_node(env)
    try:
        remote_matches = remote_sha256(client, remote_apk) == sha256
    finally:
        client.close()
    if not remote_matches:
        upload_resumable(env, local_apk, remote_apk_tmp)
        client = connect_node(env)
        try:
            if remote_sha256(client, remote_apk_tmp) != sha256:
                raise RuntimeError("Remote APK SHA-256 mismatch")
            sftp = client.open_sftp()
            try:
                sftp.posix_rename(remote_apk_tmp, remote_apk)
            finally:
                sftp.close()
        finally:
            client.close()

    client = connect_node(env)
    try:
        sftp = client.open_sftp()
        try:
            sftp.put(str(local_manifest), remote_manifest_tmp)
            if sftp.stat(remote_apk).st_size != size:
                raise RuntimeError("Remote APK size mismatch")
            sftp.posix_rename(remote_manifest_tmp, f"{remote_dir}/manifest.json")
        finally:
            sftp.close()
    finally:
        client.close()

    request = Request(apk_url, method="HEAD")
    with urlopen(request, timeout=20) as response:
        if response.status != 200 or int(response.headers["Content-Length"]) != size:
            raise RuntimeError("Published APK verification failed")
    with urlopen(f"{origin}/veilark/manifest.json", timeout=20) as response:
        published = json.load(response)
    if published != manifest:
        raise RuntimeError("Published manifest verification failed")
    downloaded_sha256, downloaded_size = download_sha256(apk_url)
    if downloaded_sha256 != sha256 or downloaded_size != size:
        raise RuntimeError("Downloaded production APK does not match the signed artifact")

    print(
        json.dumps({**result, "published": True}, ensure_ascii=False)
    )


if __name__ == "__main__":
    main()
