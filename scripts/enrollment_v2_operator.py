#!/usr/bin/env python3
"""Offline Linux-root enrollment issuer. No remote publication or credential provisioning.

Existing Ed25519 PKCS8 PEM key and profile input must be root-owned 0600 files.
State must be an existing root-owned 0700 directory; publication an existing
root-owned non-writable-by-others directory. Caller output parent must be 0700,
outside both roots. Complete links are written once to a NEW 0600 caller file.
The journal retains ciphertext/receipts ONLY; no delivery-key recovery exists.
Python/provider memory cannot be perfectly erased. Never enable core dumps or
trace/log secret inputs when operating this tool. A reserved-but-uncommitted
request burns its revision; recovery requires a NEW request and higher revision.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import unicodedata
import uuid
from contextlib import contextmanager

try:
    import fcntl
except ImportError:  # Pure validation tests can still import this module on Windows.
    fcntl = None

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_INTEGER = 9_007_199_254_740_991
MAX_JOURNAL = 1_048_576
ID = re.compile(r"[A-Za-z0-9_-]{1,48}\Z")
HEX = re.compile(r"[0-9a-f]{64}\Z")
OUTER = b'{"alg":"dir","enc":"A256GCM","typ":"veilark-enrollment+jwe;v=2","cty":"veilark-profile+jws;v=2"}'


class IssueError(Exception):
    """Only fixed public error codes; never include paths, profiles, keys or locator."""


def require(value, code="invalid_input"):
    if not value:
        raise IssueError(code)


def compact(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def integer(value, low=1, high=MAX_INTEGER):
    require(type(value) is int and low <= value <= high)
    return value


def strict_json(data, limit):
    require(0 < len(data) <= limit and not data.startswith(b"\xef\xbb\xbf"))
    text = data.decode("utf-8", "strict")
    depth = 0
    quoted = escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "[{":
            depth += 1
            require(depth <= 8)
        elif char in "]}":
            depth -= 1
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            key.encode("utf-8", "strict")
            result[key] = value
        return result
    def number(token):
        require(re.fullmatch(r"0|[1-9][0-9]{0,15}", token) is not None)
        return integer(int(token), 0)
    def invalid(_):
        raise IssueError("invalid_input")
    return json.loads(text, object_pairs_hook=pairs, parse_int=number,
                      parse_float=invalid, parse_constant=invalid)


def fields(value, keys):
    require(type(value) is dict and set(value) == set(keys))
    return value


def ipv4(value):
    require(type(value) is str and re.fullmatch(r"(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}", value))
    octets = [int(part) for part in value.split(".")]
    require(all(part <= 255 for part in octets) and 1 <= octets[0] <= 223 and octets[0] != 127)


def validate_profile(data):
    root = fields(strict_json(data, 16_384), ("schema", "engine", "beta", "name", "endpoint", "identity", "network"))
    require(root["schema"] == "veilark-argon-profile-v1" and root["engine"] == "argon-v1" and root["beta"] is True)
    name = root["name"]
    require(type(name) is str and 1 <= len(name) <= 80)
    name.encode("utf-8", "strict")
    require(all(unicodedata.category(char) not in ("Cc", "Cf", "Cn", "Zl", "Zp") for char in name))
    endpoint = fields(root["endpoint"], ("ipv4", "port"))
    ipv4(endpoint["ipv4"]); integer(endpoint["port"], 1, 65535)
    identity = fields(root["identity"], ("client_static_private_base64", "server_static_public_base64"))
    for value in identity.values():
        require(type(value) is str and re.fullmatch(r"[A-Za-z0-9+/]{43}=", value))
        key = base64.b64decode(value, validate=True)
        require(len(key) == 32 and any(key) and base64.b64encode(key).decode("ascii") == value)
    network = fields(root["network"], ("mtu", "dns_ipv4"))
    integer(network["mtu"], 1280, 1360)
    dns = network["dns_ipv4"]
    require(type(dns) is list and 1 <= len(dns) <= 3)
    for address in dns:
        ipv4(address)
    require(len(set(dns)) == len(dns))


def build_envelope(profile, key, issuer, kid, profile_id, revision, iat, nbf, exp, locator, cek, iv):
    """Caller supplies fresh OS randomness; no fixture imports, fallback suites or keys."""
    header = compact({"alg": "Ed25519", "kid": kid, "typ": "veilark-profile+jws;v=2"})
    payload = compact({"v": 2, "purpose": "veilark.profile.enrollment.v2", "iss": issuer,
                       "lid": b64(locator), "profile_id": profile_id, "revision": revision,
                       "iat": iat, "nbf": nbf, "exp": exp, "profile_b64": b64(profile)})
    require(len(header) <= 512 and len(payload) <= 24_576)
    signed = (b64(header) + "." + b64(payload)).encode("ascii")
    jws = signed + b"." + b64(key.sign(signed)).encode("ascii")
    require(len(jws) <= 34_000 and len(locator) == 32 and len(cek) == 32 and len(iv) == 12)
    aad = b64(OUTER).encode("ascii")
    encrypted = AESGCM(bytes(cek)).encrypt(iv, jws, aad)
    result = b".".join((aad, b"", b64(iv).encode(), b64(encrypted[:-16]).encode(), b64(encrypted[-16:]).encode()))
    require(len(result) <= 65_536)
    return result


def identity_key(issuer, profile_id):
    return hashlib.sha256(bytes((len(issuer),)) + issuer.encode("ascii") +
                          bytes((len(profile_id),)) + profile_id.encode("ascii")).hexdigest()


def absolute(path):
    path = Path(path)
    require(".." not in path.parts, "unsafe_path")
    return Path(os.path.abspath(path))


@contextmanager
def directory(path, private=False):
    """Walk protected root-owned directories with O_NOFOLLOW, retaining final dirfd."""
    path = absolute(path)
    fd = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd); fd = child
            info = os.fstat(fd)
            mode = stat.S_IMODE(info.st_mode)
            require(info.st_uid == 0 and (mode & 0o022 == 0 or mode & stat.S_ISVTX), "unsafe_permissions")
        info = os.fstat(fd)
        require(info.st_uid == 0 and (stat.S_IMODE(info.st_mode) == 0o700 if private else stat.S_IMODE(info.st_mode) & 0o022 == 0), "unsafe_permissions")
        yield fd
    finally:
        os.close(fd)


def read_file(fd, name, limit, private=True, links=False):
    handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
    try:
        info = os.fstat(handle)
        require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and (links or info.st_nlink == 1), "unsafe_file")
        mode = stat.S_IMODE(info.st_mode)
        require(mode == 0o600 if private else mode & 0o022 == 0, "unsafe_permissions")
        require(0 <= info.st_size <= limit, "oversize_input")
        with os.fdopen(handle, "rb", closefd=False) as stream:
            data = stream.read(limit + 1)
        require(len(data) <= limit, "oversize_input")
        return data
    finally:
        os.close(handle)


def new_file(fd, name, data, mode=0o600):
    handle = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode, dir_fd=fd)
    try:
        os.fchmod(handle, mode)
        with os.fdopen(handle, "wb", closefd=False) as stream:
            stream.write(data); stream.flush(); os.fsync(handle)
    finally:
        os.close(handle)
    os.fsync(fd)


def journal_write(fd, journal):
    data = compact(journal)
    require(len(data) <= MAX_JOURNAL, "journal_capacity")
    temp = ".journal-" + os.urandom(16).hex()
    new_file(fd, temp, data)
    try:
        os.replace(temp, "journal.json", src_dir_fd=fd, dst_dir_fd=fd)
        os.fsync(fd)
    finally:
        try:
            os.unlink(temp, dir_fd=fd)
        except FileNotFoundError:
            pass


def journal_read(fd):
    try:
        journal = fields(strict_json(read_file(fd, "journal.json", MAX_JOURNAL), MAX_JOURNAL), ("version", "requests"))
    except FileNotFoundError:
        # Existing ciphertext without a journal cannot establish a safe revision floor.
        require(set(os.listdir(fd)) <= {"operator.lock"}, "missing_journal")
        return {"version": 1, "requests": {}}
    require(type(journal["version"]) is int and journal["version"] == 1 and type(journal["requests"]) is dict and len(journal["requests"]) <= 1000, "invalid_journal")
    revisions = set()
    for request_id, item in journal["requests"].items():
        require(str(uuid.UUID(request_id)) == request_id and uuid.UUID(request_id).version == 4, "invalid_journal")
        fields(item, ("request_digest", "issuer", "kid", "profile_id", "identity", "revision", "iat", "nbf", "exp", "locator", "phase", "sha256", "published", "disclosure_attempted"))
        require(all(type(item[name]) is str and ID.fullmatch(item[name]) for name in ("issuer", "kid", "profile_id")), "invalid_journal")
        require(item["identity"] == identity_key(item["issuer"], item["profile_id"]) and HEX.fullmatch(item["request_digest"]) and HEX.fullmatch(item["locator"]), "invalid_journal")
        revision = integer(item["revision"])
        require((item["identity"], revision) not in revisions, "invalid_journal")
        revisions.add((item["identity"], revision))
        iat, nbf, exp = (integer(item[name]) for name in ("iat", "nbf", "exp"))
        require(iat <= nbf < exp and exp - iat <= 2_592_000, "invalid_journal")
        require(type(item["published"]) is bool and type(item["disclosure_attempted"]) is bool, "invalid_journal")
        require(item["phase"] in ("reserved", "committed"), "invalid_journal")
        require((item["phase"] == "reserved" and item["sha256"] == "" and not item["published"] and not item["disclosure_attempted"]) or
                (item["phase"] == "committed" and HEX.fullmatch(item["sha256"]) and (not item["disclosure_attempted"] or item["published"])), "invalid_journal")
    return journal


def publish(fd, filename, ciphertext, sha):
    """Complete temp + hard-link gives atomic create-new, never replacement."""
    temp = ".publish-" + os.urandom(16).hex()
    new_file(fd, temp, ciphertext, 0o644)
    try:
        try:
            os.link(temp, filename, src_dir_fd=fd, dst_dir_fd=fd, follow_symlinks=False)
            os.fsync(fd)
        except FileExistsError:
            pass
        actual = read_file(fd, filename, 65_536, private=False, links=True)
        require(hashlib.sha256(actual).hexdigest() == sha, "publication_conflict")
    finally:
        os.unlink(temp, dir_fd=fd); os.fsync(fd)


def issue(options):
    require(sys.platform == "linux" and fcntl is not None and os.geteuid() == 0, "linux_root_required")
    require(all(type(value) is str and ID.fullmatch(value) for value in (options.issuer, options.kid, options.profile_id)))
    require(str(uuid.UUID(options.request_id)) == options.request_id and uuid.UUID(options.request_id).version == 4)
    revision, iat, nbf, exp = (integer(value) for value in (options.revision, options.iat, options.nbf, options.exp))
    require(iat <= nbf < exp and exp - iat <= 2_592_000)
    now = integer(int(time.time()))
    require(iat <= now + 300 and nbf <= now + 300 and now < exp, "invalid_import_window")
    state, publication, output = (absolute(value) for value in (options.state_dir, options.publication_dir, options.link_output))
    require(state != publication and state not in publication.parents and publication not in state.parents, "unsafe_path")
    require(output != state and state not in output.parents and output != publication and publication not in output.parents, "unsafe_output")
    key_path, profile_path = absolute(options.signing_key), absolute(options.profile)
    require(all(path != root and root not in path.parents for path in (key_path, profile_path)
                for root in (state, publication)), "unsafe_private_input")
    with directory(key_path.parent) as fd:
        key = serialization.load_pem_private_key(read_file(fd, key_path.name, 4096), password=None)
    require(isinstance(key, Ed25519PrivateKey), "invalid_signing_key")
    with directory(profile_path.parent) as fd:
        profile = read_file(fd, profile_path.name, 16_384)
    validate_profile(profile)
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    digest = hashlib.sha256(compact({"issuer": options.issuer, "kid": options.kid, "profile_id": options.profile_id,
        "revision": revision, "iat": iat, "nbf": nbf, "exp": exp, "profile_sha256": hashlib.sha256(profile).hexdigest(),
        "signing_public_sha256": hashlib.sha256(public).hexdigest()})).hexdigest()
    with directory(state, private=True) as state_fd, directory(publication) as pub_fd, directory(output.parent, private=True) as out_fd:
        lock = os.open("operator.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=state_fd)
        try:
            info = os.fstat(lock)
            require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_nlink == 1 and stat.S_IMODE(info.st_mode) == 0o600, "unsafe_file")
            fcntl.flock(lock, fcntl.LOCK_EX)
            now = integer(int(time.time()))
            require(iat <= now + 300 and nbf <= now + 300 and now < exp, "invalid_import_window")
            journal = journal_read(state_fd)
            item = journal["requests"].get(options.request_id)
            if item is not None:
                require(item["request_digest"] == digest, "request_conflict")
                require(item["phase"] == "committed", "reserved_key_unavailable_reissue_higher_revision")
                ciphertext = read_file(state_fd, item["locator"] + ".jwe", 65_536)
                require(hashlib.sha256(ciphertext).hexdigest() == item["sha256"], "ciphertext_conflict")
                publish(pub_fd, item["locator"] + ".jwe", ciphertext, item["sha256"])
                item["published"] = True; journal_write(state_fd, journal)
                return {"status": "issued_key_unavailable", "revision": item["revision"], "sha256": item["sha256"]}
            require(len(journal["requests"]) < 1000, "journal_capacity")
            identity = identity_key(options.issuer, options.profile_id)
            floor = max((entry["revision"] for entry in journal["requests"].values() if entry["identity"] == identity), default=0)
            require(revision == floor + 1, "revision_conflict")
            require(not os.path.lexists(output), "output_exists")
            locator = os.urandom(32)
            item = {"request_digest": digest, "issuer": options.issuer, "kid": options.kid, "profile_id": options.profile_id,
                    "identity": identity, "revision": revision, "iat": iat, "nbf": nbf, "exp": exp, "locator": locator.hex(),
                    "phase": "reserved", "sha256": "", "published": False, "disclosure_attempted": False}
            journal["requests"][options.request_id] = item
            journal_write(state_fd, journal)  # Revision/locator burned before encryption.
            cek = bytearray(os.urandom(32))
            try:
                ciphertext = build_envelope(profile, key, options.issuer, options.kid, options.profile_id,
                    revision, iat, nbf, exp, locator, cek, os.urandom(12))
                new_file(state_fd, locator.hex() + ".jwe", ciphertext)
                item["sha256"] = hashlib.sha256(ciphertext).hexdigest(); item["phase"] = "committed"
                journal_write(state_fd, journal)
                publish(pub_fd, locator.hex() + ".jwe", ciphertext, item["sha256"])
                item["published"] = True; journal_write(state_fd, journal)
                # No fallible output operation precedes the durable attempt marker.
                item["disclosure_attempted"] = True; journal_write(state_fd, journal)
                link = ("veilark://enroll/v2?t=" + b64(locator) + "#k=" + b64(cek)).encode("ascii")
                require(len(link) <= 160)
                new_file(out_fd, output.name, link + b"\n")
                return {"status": "staged_link_disclosed_to_caller_file", "revision": revision, "sha256": item["sha256"]}
            finally:
                cek[:] = b"\0" * len(cek)
        finally:
            os.close(lock)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise IssueError("invalid_arguments")


def main(argv=None):
    try:
        parser = Parser(description=__doc__)
        for name in ("state-dir", "publication-dir", "signing-key", "profile", "issuer", "kid", "profile-id", "request-id", "link-output"):
            parser.add_argument("--" + name, required=True)
        for name in ("revision", "iat", "nbf", "exp"):
            parser.add_argument("--" + name, required=True, type=int)
        result = issue(parser.parse_args(argv))
        print(json.dumps(result, separators=(",", ":")))
        return 3 if result["status"] == "issued_key_unavailable" else 0
    except IssueError as failure:
        print(json.dumps({"status": "error", "code": str(failure)}), file=sys.stderr)
        return 2
    except Exception:
        print('{"status":"error","code":"operator_operation_failed"}', file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
