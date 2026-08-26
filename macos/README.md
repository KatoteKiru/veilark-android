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
- Optional signed OTA channel: Ed25519 manifest, SHA-256 DMG verification, `hdiutil`, and Gatekeeper verification

## Build

Requirements: JDK 17 and macOS. The engine fetcher selects arm64 or amd64 automatically.

```bash
./scripts/fetch-engines.sh
./gradlew test
./gradlew run
./gradlew packageDmg
```

`packageDmg` fails closed when the helper, either engine, or the geo rule sets are missing. A distributable build must also pass Developer ID signing, hardened runtime, notarization, and stapling; do not ask users to bypass Gatekeeper.

Private OTA builds read `macosOtaManifestUrl` and `otaPublicKey` from `../private.properties`, Gradle properties, or equivalent uppercase environment variables. OSS builds leave the channel disabled rather than trusting an unsigned feed.

## Helper

The transitional helper only starts the two root-owned bundled engines. It restricts callers to the `admin` group, validates user-owned configuration paths and modes, stages a root-owned copy, validates the managed PID before signalling it, and keeps logs owner-only. These mitigations do not make setuid a production architecture; see `docs/RELEASE_GATES.md`.
