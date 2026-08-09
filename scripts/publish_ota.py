from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import shlex
import uuid
from urllib.request import Request, urlopen

import paramiko
from cryptography.hazmat.primitives import serialization


ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
OTA_DIR = ROOT / "build" / "ota"
REMOTE_DIR = "/var/www/html/veilark"
UPDATE_ORIGIN = "https://nl2.senyasenyavski.uk:2096"
UPLOAD_CHUNK_SIZE = 256 * 1024
UPLOAD_ATTEMPTS = 8


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
        env["NETHERLANDS_NEW_HOST"],
        username=env["NETHERLANDS_NEW_USER"],
        password=env["NETHERLANDS_NEW_PASSWORD"],
        timeout=20,
        banner_timeout=20,
        auth_timeout=20,
    )
    client.get_transport().set_keepalive(10)
    return client


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
    args = parser.parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        raise FileNotFoundError(apk)
    if args.version_code < 1 or not args.version_name:
        raise ValueError("Invalid version")

    apk_name = f"veilark-{args.version_name}.apk"
    apk_bytes = apk.read_bytes()
    sha256 = hashlib.sha256(apk_bytes).hexdigest().upper()
    size = len(apk_bytes)
    apk_url = f"{UPDATE_ORIGIN}/veilark/{apk_name}"
    payload = (
        f"{args.version_code}\n{args.version_name}\n{apk_url}\n{sha256}\n{size}"
    ).encode("utf-8")

    private_key = serialization.load_pem_private_key(
        (WORKSPACE / "secrets" / "veilark-update-ed25519.pem").read_bytes(),
        password=None,
    )
    signature = private_key.sign(payload)
    private_key.public_key().verify(signature, payload)
    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": apk_url,
        "sha256": sha256,
        "size": size,
        "notes": args.notes,
        "signature": base64.b64encode(signature).decode("ascii"),
    }

    OTA_DIR.mkdir(parents=True, exist_ok=True)
    local_apk = OTA_DIR / apk_name
    local_manifest = OTA_DIR / "manifest.json"
    local_payload = OTA_DIR / "payload.txt"
    local_signature = OTA_DIR / "signature.bin"
    local_apk.write_bytes(apk_bytes)
    local_manifest.write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    local_payload.write_bytes(payload)
    local_signature.write_bytes(signature)

    env = read_env(WORKSPACE / "secrets" / "netherlands-new.env")
    remote_apk = f"{REMOTE_DIR}/{apk_name}"
    remote_apk_tmp = f"{REMOTE_DIR}/.{apk_name}.uploading"
    remote_manifest_tmp = f"{REMOTE_DIR}/.manifest.json.{uuid.uuid4().hex}.tmp"
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
            sftp.posix_rename(remote_manifest_tmp, f"{REMOTE_DIR}/manifest.json")
        finally:
            sftp.close()
    finally:
        client.close()

    request = Request(apk_url, method="HEAD")
    with urlopen(request, timeout=20) as response:
        if response.status != 200 or int(response.headers["Content-Length"]) != size:
            raise RuntimeError("Published APK verification failed")
    with urlopen(f"{UPDATE_ORIGIN}/veilark/manifest.json", timeout=20) as response:
        published = json.load(response)
    if published != manifest:
        raise RuntimeError("Published manifest verification failed")

    print(
        json.dumps(
            {
                "versionCode": args.version_code,
                "versionName": args.version_name,
                "apkUrl": apk_url,
                "sha256": sha256,
                "size": size,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
