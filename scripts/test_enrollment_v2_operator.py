"""Offline tests: ephemeral synthetic keys/profiles only; never operator secrets.

Run: python -m unittest discover -s scripts -p test_enrollment_v2_operator.py
Linux-root-only filesystem cases skip explicitly on other hosts. No publication
directory outside TemporaryDirectory, network, SSH, deployment or production key.
"""
import base64
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

import enrollment_v2_operator as operator


def profile_bytes(name="PUBLIC SYNTHETIC TEST - NEVER CONNECT"):
    return operator.compact({"schema": "veilark-argon-profile-v1", "engine": "argon-v1", "beta": True,
        "name": name, "endpoint": {"ipv4": "192.0.2.1", "port": 443},
        "identity": {"client_static_private_base64": base64.b64encode(bytes([1]) * 32).decode(),
                     "server_static_public_base64": base64.b64encode(bytes([2]) * 32).decode()},
        "network": {"mtu": 1280, "dns_ipv4": ["1.1.1.1"]}})


def passage_value():
    value = json.loads(profile_bytes())
    value["schema"] = "veilark-argon-relay-profile-v1"
    value["exits"] = [value.pop("endpoint")]
    value["wire_profile"] = {"id": 3, "feedback_version": 1}
    value["relays"] = [{"uri": "turn:relay.example.test:3478?transport=udp", "credential": {
        "source": "service", "service": "coturn-rest", "parameters": {
            "username": "4102444800:synthetic", "password": base64.b64encode(bytes([3]) * 20).decode()}}}]
    return value


def unb64(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def decode(ciphertext, link, public):
    locator = link.split("?t=")[1].split("#k=")[0]
    cek = unb64(link.split("#k=")[1])
    parts = ciphertext.decode("ascii").split(".")
    assert len(parts) == 5 and parts[1] == "" and unb64(parts[0]) == operator.OUTER
    jws = AESGCM(cek).decrypt(unb64(parts[2]), unb64(parts[3]) + unb64(parts[4]), parts[0].encode()).decode().split(".")
    public.verify(unb64(jws[2]), (jws[0] + "." + jws[1]).encode("ascii"))
    payload = json.loads(unb64(jws[1]))
    assert payload["lid"] == locator
    assert json.loads(unb64(jws[0]))["alg"] == "Ed25519"
    return payload


class CodecTests(unittest.TestCase):
    def test_exact_jose_bytes_verify_with_independent_primitive_consumer(self):
        key = Ed25519PrivateKey.generate()  # Ephemeral synthetic signing key only.
        profile = profile_bytes()
        locator, cek, iv = os.urandom(32), os.urandom(32), os.urandom(12)
        cipher = operator.build_envelope(profile, key, "test-issuer", "test-key", "test-profile", 1,
                                        1000, 1000, 1100, locator, cek, iv)
        link = "veilark://enroll/v2?t=" + operator.b64(locator) + "#k=" + operator.b64(cek)
        payload = decode(cipher, link, key.public_key())
        self.assertEqual(profile, unb64(payload["profile_b64"]))
        self.assertEqual("veilark.profile.enrollment.v2", payload["purpose"])
        self.assertEqual(111, len(link)); self.assertLessEqual(len(cipher), 65_536)

    def test_profile_strictness_matches_v1_only_semantics(self):
        operator.validate_profile(profile_bytes())
        value = json.loads(profile_bytes())
        bad = []
        changed = copy.deepcopy(value); changed["wire_profile"] = {"id": 2, "feedback_version": 1}; bad.append(operator.compact(changed))
        changed = copy.deepcopy(value); changed["schema"] = "veilark-argon-profile-v2"; bad.append(operator.compact(changed))
        changed = copy.deepcopy(value); changed["endpoint"]["port"] = True; bad.append(operator.compact(changed))
        changed = copy.deepcopy(value); changed["network"]["dns_ipv4"] *= 2; bad.append(operator.compact(changed))
        changed = copy.deepcopy(value); changed["endpoint"]["ipv4"] = "127.0.0.1"; bad.append(operator.compact(changed))
        changed = copy.deepcopy(value); changed["name"] = "bad\u2028name"; bad.append(operator.compact(changed))
        bad.extend((b'{' + b'"\\u0073chema":"veilark-argon-profile-v1",' + profile_bytes()[1:],
                    b"\xef\xbb\xbf" + profile_bytes(), profile_bytes().replace(b'"port":443', b'"port":443e0'),
                    b" " * 16_385, b'{"name":"\xff"}'))
        for data in bad:
            with self.subTest(length=len(data)), self.assertRaises((operator.IssueError, ValueError, UnicodeError)):
                operator.validate_profile(data)

    def test_passage_owned_coturn_profile_and_mtu_bounds(self):
        value = passage_value()
        for mtu in (1280, 1357):
            value["network"]["mtu"] = mtu
            operator.validate_profile(operator.compact(value))
        value["exits"] = [{"ipv4": "192.0.2.1", "port": port} for port in range(443, 447)]
        value["relays"] *= 8
        operator.validate_profile(operator.compact(value))
        # The existing direct schema retains its distinct 1360-byte MTU bound.
        direct = json.loads(profile_bytes()); direct["network"]["mtu"] = 1360
        operator.validate_profile(operator.compact(direct))

    def test_passage_rejects_wrong_wire_credentials_and_schema_fields(self):
        mutations = {
            "missing-wire": lambda p: p.pop("wire_profile"),
            "carrier2": lambda p: p["wire_profile"].update(id=2),
            "boolean-wire": lambda p: p["wire_profile"].update(feedback_version=True),
            "wrong-feedback": lambda p: p["wire_profile"].update(feedback_version=2),
            "mtu-too-large": lambda p: p["network"].update(mtu=1358),
            "mtu-too-small": lambda p: p["network"].update(mtu=1279),
            "static": lambda p: p["relays"][0].update(credential={"source": "static", "username": "test", "password": "test"}),
            "foreign-service": lambda p: p["relays"][0]["credential"].update(service="other-service"),
            "root-secret": lambda p: p["relays"][0]["credential"]["parameters"].update(shared_secret="SYNTHETIC_ROOT_SECRET"),
            "raw-password": lambda p: p["relays"][0]["credential"]["parameters"].update(password="SYNTHETIC_ROOT_SECRET"),
            "missing-parameters": lambda p: p["relays"][0]["credential"].pop("parameters"),
            "invalid-expiry": lambda p: p["relays"][0]["credential"]["parameters"].update(username="0:synthetic"),
            "non-ascii-user": lambda p: p["relays"][0]["credential"]["parameters"].update(username="4102444800:\u00e9"),
            "user-too-large": lambda p: p["relays"][0]["credential"]["parameters"].update(username="4102444800:" + "x" * 502),
            "extra-endpoint": lambda p: p.update(endpoint={"ipv4": "192.0.2.1", "port": 443}),
            "duplicate-exit": lambda p: p["exits"].append(copy.deepcopy(p["exits"][0])),
            "no-exits": lambda p: p.update(exits=[]),
            "too-many-exits": lambda p: p.update(exits=[{"ipv4": "192.0.2.1", "port": n} for n in range(1, 6)]),
            "no-relays": lambda p: p.update(relays=[]),
            "too-many-relays": lambda p: p.update(relays=p["relays"] * 9),
        }
        for name, mutate in mutations.items():
            value = passage_value(); mutate(value)
            with self.subTest(case=name), self.assertRaises(operator.IssueError):
                operator.validate_profile(operator.compact(value))

    def test_passage_turn_endpoint_matches_installed_udp_subset(self):
        accepted = ("TURN:Relay.Example.Test.", "turn:192.0.2.1:65535", "turn:relay.example.test?transport=udp")
        rejected = ("turns:relay.example.test", "turn:relay.example.test?transport=tcp", "turn:localhost",
                    "turn:relay.example.test:03478", "turn:relay.example.test:0", "turn:relay.example.test:65536",
                    "turn:user@relay.example.test", "turn:relay.example.test/", "turn:01.2.3.4", "turn:224.0.0.1",
                    "turn:[2001:db8::1]", "turn:-bad.example.test", "turn:" + "x" * 64 + ".test")
        for uri in accepted:
            value = passage_value(); value["relays"][0]["uri"] = uri
            with self.subTest(uri=uri):
                operator.validate_profile(operator.compact(value))
        for uri in rejected:
            value = passage_value(); value["relays"][0]["uri"] = uri
            with self.subTest(uri=uri), self.assertRaises(operator.IssueError):
                operator.validate_profile(operator.compact(value))

    def test_cli_root_authorization_has_no_test_override_or_secret_error(self):
        args = []
        for name in ("state-dir", "publication-dir", "signing-key", "profile", "issuer", "kid", "profile-id", "request-id", "link-output"):
            args += ["--" + name, "SECRET_MUST_NOT_APPEAR"]
        for name in ("revision", "iat", "nbf", "exp"):
            args += ["--" + name, "1"]
        with patch.object(operator.sys, "platform", "linux"), patch.object(operator.os, "geteuid", return_value=1000, create=True), patch("sys.stderr", new_callable=io.StringIO) as error:
            self.assertEqual(2, operator.main(args))
            self.assertNotIn("SECRET_MUST_NOT_APPEAR", error.getvalue())
            self.assertIn("linux_root_required", error.getvalue())


@unittest.skipUnless(sys.platform == "linux" and hasattr(os, "geteuid") and os.geteuid() == 0,
                     "requires actual Linux root and POSIX permissions/flock")
class JournalFilesystemTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="public-enrollment-operator-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"; self.publication = self.root / "publication"; self.caller = self.root / "caller"
        for directory, mode in ((self.state, 0o700), (self.publication, 0o755), (self.caller, 0o700)):
            directory.mkdir(mode=mode); directory.chmod(mode)
        self.signer = Ed25519PrivateKey.generate()  # Synthetic test-only random key.
        self.key_file = self.root / "synthetic-signing-key.pem"
        self.key_file.write_bytes(self.signer.private_bytes(serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8, serialization.NoEncryption())); self.key_file.chmod(0o600)
        self.profile_file = self.root / "synthetic-profile.json"
        self.profile_file.write_bytes(profile_bytes()); self.profile_file.chmod(0o600)
        now = int(time.time())
        self.options = SimpleNamespace(state_dir=self.state, publication_dir=self.publication,
            signing_key=self.key_file, profile=self.profile_file, issuer="test-issuer", kid="test-key",
            profile_id="test-profile", request_id=str(uuid.uuid4()), revision=1, iat=now, nbf=now, exp=now + 3600,
            link_output=self.caller / "link.txt")

    def journal(self):
        return json.loads((self.state / "journal.json").read_bytes())

    def next_options(self, revision=2, output="link2.txt"):
        result = copy.copy(self.options)
        result.request_id = str(uuid.uuid4()); result.revision = revision
        result.link_output = self.caller / output
        return result

    def test_durable_issue_retry_conflict_and_higher_revision_without_key_custody(self):
        result = operator.issue(self.options)
        self.assertEqual("staged_link_disclosed_to_caller_file", result["status"])
        output = self.options.link_output.read_text().strip()
        self.assertEqual(0o600, stat.S_IMODE(self.options.link_output.stat().st_mode))
        item = self.journal()["requests"][self.options.request_id]
        filename = item["locator"] + ".jwe"
        cipher = (self.publication / filename).read_bytes()
        payload = decode(cipher, output, self.signer.public_key())
        self.assertEqual(1, payload["revision"])
        self.assertEqual(profile_bytes(), unb64(payload["profile_b64"]))
        self.assertEqual(result["sha256"], hashlib.sha256(cipher).hexdigest())
        for file in self.state.iterdir():
            contents = file.read_bytes()
            self.assertNotIn(output.encode(), contents)
            self.assertNotIn(output.split("#k=")[1].encode(), contents)
            self.assertNotIn(profile_bytes(), contents)
        retry = copy.copy(self.options); retry.link_output = self.caller / "never-recovered.txt"
        self.assertEqual("issued_key_unavailable", operator.issue(retry)["status"])
        self.assertFalse(retry.link_output.exists())
        self.assertEqual(cipher, (self.publication / filename).read_bytes())
        self.profile_file.write_bytes(profile_bytes("CHANGED PUBLIC SYNTHETIC NAME"))
        with self.assertRaisesRegex(operator.IssueError, "request_conflict"):
            operator.issue(self.options)
        second = self.next_options()
        self.assertEqual(2, operator.issue(second)["revision"])
        self.assertNotEqual(output, second.link_output.read_text().strip())
        self.assertTrue(all(entry["disclosure_attempted"] for entry in self.journal()["requests"].values()))

    def test_reserved_crash_burns_revision_without_regeneration(self):
        with patch.object(operator, "build_envelope", side_effect=RuntimeError("synthetic crash")):
            with self.assertRaises(RuntimeError): operator.issue(self.options)
        item = self.journal()["requests"][self.options.request_id]
        self.assertEqual("reserved", item["phase"])
        self.assertFalse(self.options.link_output.exists())
        with self.assertRaisesRegex(operator.IssueError, "reserved_key_unavailable"):
            operator.issue(self.options)
        self.assertEqual(2, operator.issue(self.next_options())["revision"])
        self.assertEqual(item["locator"], self.journal()["requests"][self.options.request_id]["locator"])

    def test_committed_crash_republishes_same_bytes_but_never_recovers_key(self):
        with patch.object(operator, "publish", side_effect=RuntimeError("synthetic publication interruption")):
            with self.assertRaises(RuntimeError): operator.issue(self.options)
        item = self.journal()["requests"][self.options.request_id]
        original = (self.state / (item["locator"] + ".jwe")).read_bytes()
        self.assertEqual("committed", item["phase"])
        self.assertEqual("issued_key_unavailable", operator.issue(self.options)["status"])
        self.assertEqual(original, (self.publication / (item["locator"] + ".jwe")).read_bytes())
        self.assertFalse(self.options.link_output.exists())

    def test_failed_disclosure_is_durable_attempt_not_rollback_or_retry(self):
        actual = operator.new_file
        def fail_output(fd, name, data, mode=0o600):
            if name == "link.txt": raise PermissionError("synthetic protected-output failure")
            return actual(fd, name, data, mode)
        with patch.object(operator, "new_file", side_effect=fail_output):
            with self.assertRaises(PermissionError): operator.issue(self.options)
        item = self.journal()["requests"][self.options.request_id]
        self.assertTrue(item["disclosure_attempted"]); self.assertTrue(item["published"])
        self.assertEqual("issued_key_unavailable", operator.issue(self.options)["status"])
        self.assertFalse(self.options.link_output.exists())

    def test_flock_serializes_competing_requests_for_same_next_revision(self):
        operator.issue(self.options)
        requests = [self.next_options(output="concurrent-a.txt"), self.next_options(output="concurrent-b.txt")]
        def attempt(options):
            try: return operator.issue(options)["status"]
            except operator.IssueError as error: return str(error)
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(attempt, requests))
        self.assertCountEqual(["staged_link_disclosed_to_caller_file", "revision_conflict"], results)
        self.assertEqual([1, 2], sorted(item["revision"] for item in self.journal()["requests"].values()))

    def test_unsafe_files_output_roots_and_publication_replacement_fail_closed(self):
        self.key_file.chmod(0o644)
        with self.assertRaisesRegex(operator.IssueError, "unsafe_permissions"): operator.issue(self.options)
        self.key_file.chmod(0o600)
        link = self.root / "symlink-key.pem"; link.symlink_to(self.key_file)
        unsafe = copy.copy(self.options); unsafe.signing_key = link
        with self.assertRaises(OSError): operator.issue(unsafe)
        os.link(self.key_file, self.root / "hardlink-key.pem")
        with self.assertRaisesRegex(operator.IssueError, "unsafe_file"): operator.issue(self.options)
        (self.root / "hardlink-key.pem").unlink()
        unsafe = copy.copy(self.options); unsafe.link_output = self.state / "forbidden-link"
        with self.assertRaisesRegex(operator.IssueError, "unsafe_output"): operator.issue(unsafe)
        for root in (self.state, self.publication):
            unsafe = copy.copy(self.options); unsafe.signing_key = root / "forbidden-private-key.pem"
            with self.assertRaisesRegex(operator.IssueError, "unsafe_private_input"): operator.issue(unsafe)
            unsafe = copy.copy(self.options); unsafe.profile = root / "forbidden-private-profile.json"
            with self.assertRaisesRegex(operator.IssueError, "unsafe_private_input"): operator.issue(unsafe)
        filename = "a" * 64 + ".jwe"
        original = b"unrelated immutable ciphertext"
        (self.publication / filename).write_bytes(original); (self.publication / filename).chmod(0o644)
        with operator.directory(self.publication) as fd:
            with self.assertRaisesRegex(operator.IssueError, "publication_conflict"):
                operator.publish(fd, filename, b"different ciphertext", hashlib.sha256(b"different ciphertext").hexdigest())
        self.assertEqual(original, (self.publication / filename).read_bytes())
        self.assertFalse((self.state / "journal.json").exists())


if __name__ == "__main__":
    unittest.main()
