# macOS OTA channel

Veilark's macOS updater is a two-process, fail-closed update path. The running JVM client validates the HTTPS manifest, Ed25519 signature, build number, architecture, DMG SHA-256, disk-image integrity, and—when enabled—Gatekeeper. A bundled native Swift updater then independently verifies the signed manifest and DMG hash, waits for Veilark to stop, replaces the application bundle through a same-volume staging path, restores the previous bundle if replacement fails, and relaunches Veilark.

## Channel separation

- The macOS channel uses its own Ed25519 key. Android, Windows, and macOS signing authority must not be shared.
- Only the public key is stored in source. The private key exists only in protected operator storage and the `VEILARK_MACOS_OTA_SIGNING_KEY_B64` GitHub secret.
- Deployment uses the restricted `veilark-ota` SFTP account. It is chrooted to the public web root and cannot access VPN configuration or execute a shell.
- The manifest is renamed into place only after the versioned DMG upload completes. Clients therefore never observe a manifest that references a partial artifact.

## Preview release

1. Update `macosVersion` and the monotonically increasing `macosBuild` in `gradle.properties`.
2. Write short, user-facing release notes in `.github/workflows/macos-ota-release.yml`.
3. Push the exact tag `macos-v<version>`.
4. The Apple Silicon runner tests the JVM client, fetches pinned engines, builds and mounts the DMG, creates the signed manifest, performs a disposable 1.0.1-to-current native replacement test, uploads the DMG and manifest atomically, redownloads both, checks their bytes, and creates a GitHub prerelease.

The first migration from a build that predates `veilark-updater` still requires one manual DMG installation. Every later build can update and restart from inside Veilark.

## Production promotion

Preview OTA is protected by the Veilark Ed25519 key but is not a substitute for Apple's distribution chain. Before setting `macosOtaRequireGatekeeper=true`, add Developer ID Application credentials, sign nested binaries and the application inside-out with hardened runtime, notarize and staple the application and DMG, and pass physical-Mac install/update tests. Production mode makes both stages reject a DMG or replacement app that Gatekeeper does not accept and requires the replacement to use the same Apple Team ID as the installed application.

## Recovery and key rotation

- Never replace the manifest after a failed or incomplete DMG upload.
- A bad release is superseded with a higher build number; build rollback is intentionally rejected.
- To rotate the Ed25519 key, first publish a bridge release signed by the old key that embeds the next public key. Only after that release is widely installed may the server switch to manifests signed by the new key.
- Losing the private key without a bridge release ends OTA continuity for existing installations and requires a manual reinstall.
