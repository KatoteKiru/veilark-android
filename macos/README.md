# Veilark for macOS

Development preview of the Veilark desktop client for Apple Silicon and Intel Macs. This branch is not yet approved for public distribution: the current CLI TUN helper must be replaced by an Apple Network Extension or an authenticated signed XPC service before a production release.

## What you get

- macOS-oriented Compose Desktop UI with Overview, Profiles, Routing, Diagnostics, and Settings
- Import HTTPS subscriptions, clipboard, or files (same parsers as Android)
- Refresh and delete remote subscriptions without replacing unrelated profiles
- sing-box TUN and TrustTunnel TUN (one engine at a time)
- sing-box routing modes: full tunnel, Russia direct through pinned local SRS data, and manual domain/CIDR rules
- AES-GCM catalogs, key in macOS Keychain
- Process supervision and HTTPS/DNS health verification before the UI reports a successful connection
- Signed in-app OTA: Ed25519 manifest, SHA-256 DMG verification, native app replacement, rollback, and restart

## Build

Requirements: JDK 17 and macOS. The engine fetcher selects arm64 or amd64 automatically.

```bash
./scripts/fetch-engines.sh
./gradlew test
./gradlew run
./gradlew packageDmg
```

`packageDmg` fails closed when the helper, either engine, or the geo rule sets are missing. A distributable build must also pass Developer ID signing, hardened runtime, notarization, and stapling; do not ask users to bypass Gatekeeper.

The preview channel is configured from Gradle properties and is signed with a dedicated macOS Ed25519 release key. The app downloads into its private cache, verifies the signed manifest and DMG again in a separate native updater, replaces `Veilark.app` atomically, rolls back on failure, and restarts. Replacing an app under `/Applications` may display the standard macOS administrator prompt.

The preview channel does not bypass Apple security: it is visibly marked as a preview until Developer ID signing and notarization are available. Production builds must set `macosOtaRequireGatekeeper=true`; the client and native updater then both require Gatekeeper acceptance and the same Apple Team ID. See `docs/OTA.md` for the release and key-rotation procedure.

## Helper

The transitional helper only starts the two root-owned bundled engines. It restricts callers to the `admin` group, validates user-owned configuration paths and modes, stages a root-owned copy, validates the managed PID before signalling it, and keeps logs owner-only. These mitigations do not make setuid a production architecture; see `docs/RELEASE_GATES.md`.
