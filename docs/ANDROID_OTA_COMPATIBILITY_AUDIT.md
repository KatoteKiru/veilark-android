# Android OTA compatibility audit

Audit date: 2026-08-31. Scope: Android only. The audit used read-only HTTP,
GitHub release metadata, historical release evidence and local APK inspection.
No OTA files, manifests, tags or servers were changed.

## Result

The official private OTA chain from rc18/rc19 to rc21 is compatible at the
Android identity layer. The releases use the same package name and the same
signing certificate. The change from APK Signature Scheme v2 to v3 does not
change signer identity and verifies for the application's minimum SDK 29.

There is no compatible in-place update path between the OSS and private
channels. This is intentional and enforced by three independent boundaries:

- private package: `uk.senyasenyavski.veilark`;
- OSS package: `app.veilark.android`;
- different signing certificates, while the OSS flavor also compiles with
  `SELF_UPDATE_ENABLED=false`.

An OSS installation therefore cannot discover or install a private OTA update.
It must remain on OSS releases or be replaced explicitly as a separate app.

## Verified release matrix

| Release | Channel | versionCode | Package | Signer SHA-256 | OTA result |
| --- | --- | ---: | --- | --- | --- |
| 0.8.0-rc18 | private | 45 | `uk.senyasenyavski.veilark` | `4c6e3e...a15d3` | Compatible with rc21; historical file is no longer served |
| 0.8.0-rc19 | private | 46 | `uk.senyasenyavski.veilark` | `4c6e3e...a15d3` | Compatible with rc21; historical file is no longer served |
| 0.8.0-rc20-oss | OSS | 47 | `app.veilark.android` | `8fc1db...3763` | Separate channel; self-update disabled |
| 0.8.0-rc21 | private | 48 | `uk.senyasenyavski.veilark` | `4c6e3e...a15d3` | Current private OTA candidate/live artifact at audit time |

The rc21 APK hash, byte size and package/version match the manifest. The server
returns HTTP 200 for the manifest and APK and a correct HTTP 206 Content-Range
for resumed downloads. The current manifest contains both legacy and v2
Ed25519 signatures. Its legacy signature verifies with the public key embedded
in older clients, which keeps pre-v2 manifest readers compatible.

## Updater chain

1. `UpdateManager.check` exits immediately for OSS builds.
2. A private build downloads the pinned HTTPS manifest with redirects disabled
   and a 128 KiB limit.
3. It validates version, size, exact OTA host/port, SHA-256 format and Ed25519
   signature before exposing an update.
4. The APK downloader supports exact resumable ranges and verifies final byte
   count and SHA-256.
5. Before installation, it verifies package name, versionCode and signing
   lineage against the currently installed application.
6. Android's unknown-app-source permission is requested when needed. The APK is
   then shared through the non-exported FileProvider and opened with a read-only
   URI grant. Android Package Installer still requires user confirmation; this
   is not a silent updater.

## Root-cause boundary

For a device running the official private rc18 or rc19 APK, no package, signer,
version, manifest, URL, hash, range-download or install-Intent incompatibility
was reproduced by this audit. A failure on such a device needs the exact stage
and Android Package Installer result from that device; the repository and live
artifacts alone do not support claiming a different cause.

For a device running an OSS APK, the cause is exact and deterministic: the OSS
flavor never checks the private OTA channel, and Android rejects the private APK
as a different package/signing identity. This must not be "fixed" by weakening
package or signer checks.

## Hardening added by this audit

- Private release assembly now depends on a fail-closed validation task. It
  rejects a changed applicationId, empty/mismatched OTA URL-host-port tuple, or
  an invalid Ed25519 X.509 public key before packaging.
- Signer-lineage policy is isolated and regression-tested for the real channel
  invariants: stable private certificate across v2/v3 schemes, OSS rejection,
  declared key rotation, and exact multi-signer matching.

## Safe rollout and rollback

1. Keep rc21 available as the legacy private signing bridge. Do not replace it
   with the OSS APK and do not lower the manifest versionCode.
2. Recover the exact archived rc18 and rc19 APKs by their recorded hashes; never
   recreate them. Test rc18 -> next and rc19 -> next on physical API 29, a
   current Android release, and at least one OEM device. Exercise both the
   already-authorized and first-time unknown-source permission paths.
3. For a future production private signing key, create and verify an Android v3
   signing lineage from the existing private signer. Test the lineage before
   publication. A new unrelated certificate without lineage is not an update.
4. Publish only a versionCode greater than 48 after package, signer history,
   manifest signatures, full/resumed download, FileProvider and installer UI
   gates pass. Preserve the prior manifest and APK as an atomic rollback pair.
5. Rollback means restoring that prior signed manifest/APK pair. It cannot use a
   lower versionCode to downgrade an already updated Android installation.

Remaining acceptance gate: a physical-device upgrade test. No connected Android
device was available during this audit.
