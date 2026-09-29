# Handoff Snapshot

## Current State

- Last updated: 2026-09-29 (MSK)
- Workspace root: `C:\AI-Agent\veilark-macos`; shared Android/macOS repository `KatoteKiru/veilark-android` is PUBLIC by the owner's explicit approval.
- Objective: maintain a stable macOS client and verify delivery of the 1.0.12 OTA on an installed physical Mac.
- Status: preview OTA `1.0.12` / build `10012` / ARM64 is live. Source tag `macos-v1.0.12` points to `7025d86`. Live HTTPS manifest and GitHub prerelease both report 1.0.12; DMG SHA-256 and size agree (`40e6a0229bedbb3c2064ed2be88b90c6b34dd2166792455199a7878521d7df41`, 131502256 bytes).
- Native Apple Silicon CI `36560617114` and package verification `36560617122` passed. Release run `36561167600` passed tests, DMG packaging, Ed25519 manifest self-check, native in-app replacement, signed old-manifest check, artifact-before-manifest deployment, production redownload/SHA-256 and `hdiutil verify`.
- This is a preview DMG without Apple Developer ID signing/notarization. No physical Mac is attached, so installed 1.0.11→1.0.12 OTA, actual VPN traffic, menu-bar icon, data preservation and the reported intermittent no-window launch are not accepted.
- Immediate next action: on the affected Mac, test in-app 1.0.11→1.0.12 update, launch, preserved profiles, sing-box and TrustTunnel traffic, RU-direct routes, Wi-Fi changes, Cmd+Q cleanup; collect a redacted `~/Library/Logs/Veilark/startup.log` if a no-window launch recurs.
- Active code: `macos/helper/{main.swift,HelperConfigPolicy.swift}`, `macos/src/main/kotlin/com/example/veilark/profile/GeoRoutingRepository.kt`, `.github/workflows/{macos-ci.yml,macos-ota-release.yml}`.

## Recovery Summary

- Do not equate green CI and a signed OTA manifest with physical-device VPN acceptance.
- Preserve the established Ed25519 manifest key and versioned artifact lineage; older clients without an updater may still require one manual DMG installation.
- `macos/docs/RELEASE_GATES.md` defines release gates and physical Mac checks.
