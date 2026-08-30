from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import uuid
from urllib.request import Request, urlopen

import paramiko
from cryptography.hazmat.primitives import serialization


ROOT = Path(__file__).resolve().parents[1]
OTA_DIR = ROOT / "build" / "ota"
UPLOAD_CHUNK_SIZE = 256 * 1024
UPLOAD_ATTEMPTS = 8
MAX_RELEASE_NOTES_LENGTH = 500
MAX_MANIFEST_SIZE = 128 * 1024


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


class PinnedHostKeyPolicy(paramiko.MissingHostKeyPolicy):
    def __init__(self, expected_sha256: str) -> None:
        self.expected_sha256 = expected_sha256

    def missing_host_key(
        self,
        client: paramiko.SSHClient,
        hostname: str,
        key: paramiko.PKey,
    ) -> None:
        actual = "SHA256:" + base64.b64encode(
            hashlib.sha256(key.asbytes()).digest()
        ).decode("ascii").rstrip("=")
        if not hmac.compare_digest(actual, self.expected_sha256):
            raise paramiko.SSHException(
                f"SSH host key mismatch for {hostname}: expected pinned fingerprint"
            )


def connect_node(env: dict[str, str]) -> paramiko.SSHClient:
    host = (
        os.environ.get("OTA_SSH_HOST")
        or env.get("OTA_SSH_HOST")
        or env.get("NETHERLANDS_NEW_HOST")
        or env.get("NETHERLANDS_HOST")
    )
    user = (
        os.environ.get("OTA_SSH_USER")
        or env.get("OTA_SSH_USER")
        or env.get("NETHERLANDS_NEW_USER")
        or env.get("NETHERLANDS_USER")
    )
    password = (
        os.environ.get("OTA_SSH_PASSWORD")
        or env.get("OTA_SSH_PASSWORD")
        or env.get("NETHERLANDS_NEW_PASSWORD")
        or env.get("NETHERLANDS_PASSWORD")
    )
    host_key_sha256 = (
        os.environ.get("OTA_SSH_HOST_KEY_SHA256")
        or env.get("OTA_SSH_HOST_KEY_SHA256")
    )
    if not host or not user or not password or not host_key_sha256:
        raise ValueError(
            "SSH environment is missing OTA credentials or pinned host key fingerprint"
        )
    if re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}", host_key_sha256) is None:
        raise ValueError("OTA_SSH_HOST_KEY_SHA256 is invalid")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(PinnedHostKeyPolicy(host_key_sha256))
    client.connect(
        host,
        username=user,
        password=password,
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


def download_sha256(url: str, expected_size: int) -> tuple[str, int]:
    """Download a large APK with resumable range requests before hashing it."""
    last_error: Exception | None = None
    with tempfile.TemporaryDirectory(prefix="veilark-ota-verify-") as directory:
        destination = Path(directory) / "published.apk"
        for _ in range(UPLOAD_ATTEMPTS):
            offset = destination.stat().st_size if destination.exists() else 0
            headers = {"Cache-Control": "no-cache"}
            if offset:
                headers["Range"] = f"bytes={offset}-"
            try:
                with urlopen(Request(url, headers=headers, method="GET"), timeout=60) as response:
                    status = response.status
                    mode = "ab"
                    if offset and status == 206:
                        content_range = response.headers.get("Content-Range", "")
                        if not content_range.startswith(f"bytes {offset}-"):
                            raise RuntimeError(
                                f"Published APK returned invalid Content-Range: {content_range}"
                            )
                    elif status == 200:
                        mode = "wb"
                        offset = 0
                    elif not offset and status == 206:
                        mode = "wb"
                    else:
                        raise RuntimeError(f"Published APK returned HTTP {status}")

                    with destination.open(mode) as output:
                        while chunk := response.read(UPLOAD_CHUNK_SIZE):
                            output.write(chunk)
                            if output.tell() > expected_size:
                                raise RuntimeError("Published APK exceeded the expected size")
            except Exception as error:
                last_error = error

            actual_size = destination.stat().st_size if destination.exists() else 0
            if actual_size == expected_size:
                digest = hashlib.sha256()
                with destination.open("rb") as source:
                    while chunk := source.read(UPLOAD_CHUNK_SIZE):
                        digest.update(chunk)
                return digest.hexdigest().upper(), actual_size
            if actual_size > expected_size:
                raise RuntimeError("Published APK exceeded the expected size")

        actual_size = destination.stat().st_size if destination.exists() else 0
        raise RuntimeError(
            f"Published APK download remained incomplete: {actual_size}/{expected_size} bytes"
        ) from last_error


def remote_sha256(client: paramiko.SSHClient, path: str) -> str:
    _, stdout, stderr = client.exec_command(f"sha256sum -- {shlex.quote(path)}")
    digest = stdout.read().decode("ascii", errors="replace").split(maxsplit=1)
    errors = stderr.read()
    if errors or stdout.channel.recv_exit_status() != 0 or not digest:
        return ""
    return digest[0].upper()


def fetch_verified_live_manifest(origin: str, public_key) -> dict[str, object]:
    request = Request(
        f"{origin}/veilark/manifest.json",
        headers={"Cache-Control": "no-cache"},
        method="GET",
    )
    with urlopen(request, timeout=20) as response:
        raw = response.read(MAX_MANIFEST_SIZE + 1)
    if len(raw) > MAX_MANIFEST_SIZE:
        raise RuntimeError("Live OTA manifest is too large")
    manifest = json.loads(raw)
    fields = [
        "veilark-update-v2",
        str(manifest["versionCode"]),
        str(manifest["versionName"]),
        str(manifest["apkUrl"]),
        str(manifest["sha256"]),
        str(manifest["size"]),
        str(manifest["notes"]),
    ]
    signature = base64.b64decode(str(manifest["signatureV2"]), validate=True)
    public_key.verify(signature, canonical_payload_v2(fields))
    return manifest


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
    live_manifest = fetch_verified_live_manifest(origin, private_key.public_key())
    live_version_code = int(live_manifest["versionCode"])
    if args.version_code <= live_version_code:
        raise RuntimeError(
            f"OTA versionCode must increase monotonically: live={live_version_code}, "
            f"requested={args.version_code}"
        )
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
    downloaded_sha256, downloaded_size = download_sha256(apk_url, size)
    if downloaded_sha256 != sha256 or downloaded_size != size:
        raise RuntimeError("Downloaded production APK does not match the signed artifact")

    print(
        json.dumps({**result, "published": True}, ensure_ascii=False)
    )


if __name__ == "__main__":
    main()
