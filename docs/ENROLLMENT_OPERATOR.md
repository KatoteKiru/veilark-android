# Protected enrollment operator and Android integration

The new Android import path accepts the canonical 111-byte `veilark://enroll/v2`
envelope from paste, QR, file or VIEW intent. Explicit import selects its protected
Core source; it does not select/start a VPN engine. The VPN service reads the same
verified record as metadata preflight, without copying credentials into the raw
profile store. Existing raw profiles and old subscription links remain separate.
Deletion retains both the revision floor and source marker, preventing fallback to
a different retained raw identity. A revoked issuer blocks use, while local
deletion remains available through the existing card.

## Operator staging

`scripts/enrollment_v2_operator.py` is a Linux-root-only local staging tool.
It takes an existing Ed25519 PKCS8 PEM signing key and a strict Core Argon v1
profile, both root-owned regular 0600 files outside state/publication directories.
It does not generate keys, provision VPN credentials, deploy files, perform HTTP
requests or manage customer accounts.

Required arguments: `--state-dir`, `--publication-dir`, `--signing-key`,
`--profile`, `--issuer`, `--kid`, `--profile-id`, `--request-id`, `--revision`,
`--iat`, `--nbf`, `--exp`, `--link-output`. Times are integer Unix seconds;
`iat <= nbf < exp`, import window at most 30 days. IDs are bounded ASCII identifiers;
request ID is canonical UUIDv4. First revision is 1, later revisions exactly the
next reserved revision for that issuer/profile identity.

State must already exist as root-owned 0700; publication must be root-owned and
not writable by others. The caller-output parent is root-owned 0700 outside both
roots. The output must not exist. A single complete bearer link is written to that
new 0600 file; never paste its contents into logs, commits or issue comments.
The caller file is key custody. Stdout contains only status, revision and SHA.

The locked, fsynced journal reserves revision/locator before encryption and keeps
only receipt metadata and immutable ciphertext. A crash after reservation burns
that revision. A retry of a committed request stages the identical ciphertext
and reports `issued_key_unavailable`; it never reconstructs or discloses the link.
A durable disclosure-attempt marker precedes caller output. Lost/failed output
requires an explicit new request and higher revision. Neither reissue nor import
expiry revokes the underlying VPN credentials.

`staged_link_disclosed_to_caller_file` means local immutable staging and exact
local SHA readback, not HTTPS publication. Deployment needs create-new copying
to the existing delivery directory, exact HTTPS readback and a matched app issuer
public key. Never replace an existing `.jwe` or any legacy `.json` object.

## Current acceptance boundary

Android URI/selection checks and private-release Kotlin compilation pass.
On Windows, three operator codec/authorization tests pass; six real POSIX journal
and permission tests require Linux root and are explicitly skipped there.
The dedicated `Enrollment operator Linux storage` CI runs those tests in a fresh
Ubuntu worker using temporary synthetic profiles and keys, with no production
secrets, SSH, public routes or OTA publication.

Linux CI acceptance completed for commit `ce1d8c8`: all nine cases ran under real
Linux root, with no skips, in a disposable worker. Run and raw step output:
https://github.com/KatoteKiru/veilark-android/actions/runs/40007201346 .
This accepts local staging/permissions, not live publication or Android runtime.

The candidate registry now pins the dedicated public issuer key recorded in
`docs/keys/enrollment-20261023-01.json`. Its private key was generated under
pinned-host SSH and remains root-only on the existing delivery host; it was not
exported into the repository, APK, or local reports. Rotation requires a new
authenticated application release. This pin is not present in published OTA83.

A synthetic, non-connectable RFC5737 profile has been issued into private staging.
Actual HTTPS publication and VIEW/import/delete/restore instrumentation remain
pending explicit authorization for that test object and its emulator recipient.
The candidate/test APKs compile and are installed only on an owned API35 emulator.
No live HTTPS/UI acceptance, customer profile issuance, or release approval is
claimed. Published OTA83 and existing subscriptions remain unchanged.

The delivery host now applies a narrow Nginx rule for exact 64-lowercase-hex JWE
paths: file-only lookup and access events with a constant redacted URI, without
raw locator or Referer. Other downloads retain their existing behavior. Nginx
configuration validation and graceful worker reload passed; actual HTTP log
redaction remains to be verified during the authorized transport test. An exact
root-only configuration backup is retained on the host.
