# Releasing Veilark OSS

## One-time setup

Create a dedicated Android release key outside the repository. Back it up in a
separate encrypted location. A lost key cannot update existing installations.
Configure these GitHub Actions secrets:

- `VEILARK_OSS_KEYSTORE_B64` — base64 of the JKS/PKCS12 file;
- `VEILARK_OSS_STORE_PASSWORD`;
- `VEILARK_OSS_KEY_ALIAS`;
- `VEILARK_OSS_KEY_PASSWORD`.

The OSS key must never be reused for the private distribution.

## Release gate

1. Update `versionCode`, `versionName` and `CHANGELOG.md`.
2. Run `testOssDebugUnitTest`, `lintOssRelease` and `assembleOssRelease`.
3. On an ARM device, test fresh install and upgrade, both engines, subscription
   refresh, QR import, app routing, Wi-Fi/LTE handover and a 30-minute soak.
4. Inspect the merged manifest and APK for private strings and embedded
   profiles.
5. Verify package ID, version, signer, an Android-compatible APK signature
   scheme and SHA-256.
6. Push the reviewed commit, configure the four repository secrets, and run the
   `OSS release` workflow with a `v*` tag. The workflow builds the artifact
   again and creates the tag and release atomically.
7. Download the GitHub asset independently and verify its hash and signer.

The CI release workflow intentionally fails if signing secrets are absent. It
publishes only the `ossRelease` APK.
