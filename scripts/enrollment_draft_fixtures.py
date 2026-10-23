"""Public synthetic candidate bytes; NOT a production exporter or importer.

All keys/nonces here are disclosed and must NEVER enter an app trust map.
Requires the already installed cryptography package. No network or real profiles.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path

from cryptography.exceptions import InvalidSignature, InvalidTag
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def compact(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def encoded_length(size):
    return (size * 8 + 5) // 6


def generate():
    # Public deterministic fixture inputs. No service/user identity or live key.
    seed = bytes(range(32))
    cek = bytes(range(32, 64))
    iv = bytes(range(12))
    locator = b64(bytes(range(64, 96)))
    signer = Ed25519PrivateKey.from_private_bytes(seed)
    public = signer.public_key()
    header = compact({"alg": "Ed25519", "kid": "fixture-only", "typ": "veilark-profile+jws;v=2"})
    outer = compact({"alg": "dir", "enc": "A256GCM", "typ": "veilark-enrollment+jwe;v=2", "cty": "veilark-profile+jws;v=2"})
    # Same schema shape as CoreArgonProfileTest; RFC5737 address cannot be a VPN.
    profile = compact({
        "schema": "veilark-argon-profile-v1", "engine": "argon-v1", "beta": True,
        "name": "PUBLIC FIXTURE - NEVER CONNECT",
        "endpoint": {"ipv4": "192.0.2.1", "port": 443},
        "identity": {"client_static_private_base64": base64.b64encode(bytes([1]) * 32).decode(),
                     "server_static_public_base64": base64.b64encode(bytes([2]) * 32).decode()},
        "network": {"mtu": 1280, "dns_ipv4": ["1.1.1.1", "77.88.8.8"]},
    })
    payload = compact({
        "v": 2, "purpose": "veilark.profile.enrollment.v2", "iss": "fixture-only",
        "lid": locator, "profile_id": "fixture-profile", "revision": 1,
        "iat": 1792713600, "nbf": 1792713600, "exp": 1792800000,
        "profile_b64": b64(profile),
    })
    signing_input = (b64(header) + "." + b64(payload)).encode("ascii")
    signature = signer.sign(signing_input)
    public.verify(signature, signing_input)
    jws = signing_input + b"." + b64(signature).encode("ascii")
    aad = b64(outer).encode("ascii")
    encrypted = AESGCM(cek).encrypt(iv, jws, aad)
    assert AESGCM(cek).decrypt(iv, encrypted, aad) == jws
    envelope = b".".join([aad, b"", b64(iv).encode(), b64(encrypted[:-16]).encode(), b64(encrypted[-16:]).encode()])

    def wrapped(cipher_and_tag):
        return b".".join([aad, b"", b64(iv).encode(), b64(cipher_and_tag[:-16]).encode(), b64(cipher_and_tag[-16:]).encode()]).decode()

    altered_tag = encrypted[:-1] + bytes([encrypted[-1] ^ 1])
    try:
        AESGCM(cek).decrypt(iv, altered_tag, aad)
        raise AssertionError("altered tag accepted")
    except InvalidTag:
        pass
    # A link holder knows CEK and can forge valid AEAD. Signature is still needed.
    false_signature = bytes([signature[0] ^ 1]) + signature[1:]
    forged_jws = signing_input + b"." + b64(false_signature).encode()
    forged_encrypted = AESGCM(cek).encrypt(iv, forged_jws, aad)
    # Reusing the fixture IV here deliberately constructs an attack example only.
    assert AESGCM(cek).decrypt(iv, forged_encrypted, aad) == forged_jws
    try:
        public.verify(false_signature, signing_input)
        raise AssertionError("forged issuer signature accepted")
    except InvalidSignature:
        pass

    uri = f"veilark://enroll/v2?t={locator}#k={b64(cek)}"
    max_inner = encoded_length(512) + 1 + encoded_length(24576) + 1 + encoded_length(64)
    max_outer = len(aad) + 4 + encoded_length(12) + encoded_length(34000) + encoded_length(16)
    assert max_inner <= 34000 and max_outer <= 65536 and len(uri) <= 160
    return {
        "status": "CANDIDATE_NOT_FROZEN_NOT_PRODUCTION",
        "warning": "Every key is public synthetic test material; NEVER TRUST OR DEPLOY.",
        "scope": "Python primitive roundtrip and mutations only; Kotlin/backend interoperability pending.",
        "public_test_inputs": {"ed25519_seed_hex": seed.hex(), "ed25519_public_hex": public.public_bytes_raw().hex(),
                               "cek_hex": cek.hex(), "iv_hex": iv.hex(), "clock_epoch": 1792717200},
        "valid": {"uri": uri, "profile_utf8": profile.decode(), "protected_header_utf8": header.decode(),
                  "payload_utf8": payload.decode(), "signing_input_ascii": signing_input.decode(),
                  "signed_content_sha256": hashlib.sha256(signing_input).hexdigest(),
                  "signature_b64url": b64(signature), "jws_ascii": jws.decode(),
                  "jwe_protected_header_utf8": outer.decode(), "aad_ascii": aad.decode(),
                  "jwe_ascii": envelope.decode(), "expected": "crypto_valid_semantic_consumer_pending"},
        "negative": [
            {"case": "modified_tag", "jwe_ascii": wrapped(altered_tag), "expected": "aead_reject"},
            {"case": "known_cek_forged_signature", "jwe_ascii": wrapped(forged_encrypted),
             "expected": "aead_accept_issuer_signature_reject"},
            {"case": "swapped_locator", "jwe_ascii": envelope.decode(), "locator": b64(bytes([99]) * 32),
             "expected": "crypto_accept_signed_locator_mismatch"},
            {"case": "expired", "jwe_ascii": envelope.decode(), "clock_epoch": 1792800000,
             "expected": "crypto_accept_expiry_reject"},
        ],
        "size_proof": {"uri_ascii_bytes": len(uri), "jwe_ascii_bytes": len(envelope),
                       "max_inner_from_decoded_limits": max_inner, "max_outer_from_inner_limit": max_outer},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    options = parser.parse_args()
    result = generate()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    options.output.write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(options.output), "status": result["status"], "sizes": result["size_proof"]}))
