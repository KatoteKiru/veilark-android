# Handoff Snapshot

## Current State

- Last updated: 2026-09-29 (MSK)
- Workspace root: `C:\AI-Agent\veilark-macos`
- Current objective: deliver a tested macOS fix and OTA without breaking update trust continuity.
- Status: source changes pushed; next DMG/OTA **not published**.
- Live OTA: version `1.0.11`, build `10011`, ARM64, read from the HTTPS manifest on 2026-09-29.
- Source branch: `cursor/deep-link-import-macos`; prepared handoff/version commit `e993024` is pushed. `macos-v1.0.11` tags `75404f7`. Post-release fixes are GEO fallback `3945c4e`, helper recovery `612fbc8`, Keychain argv removal `4ee1e30`.
- Prepared source version: `1.0.12` / build `10012`; release tag has **not** been pushed and no DMG exists.
- Immediate next actions: obtain a working Mac build environment; run native package/update verification; only then publish DMG and signed manifest atomically. Forced JVM tests and signed live-manifest preflight passed.
- Active files: `macos/gradle.properties`, `.github/workflows/macos-ota-release.yml`, `macos/src/main/kotlin/com/example/veilark/{profile/GeoRoutingRepository.kt,session/VeilarkSession.kt,storage/EncryptedStore.kt}`.
- Blocker: GitHub-hosted macOS jobs do not start due account billing failure; repository reports zero self-hosted runners. Windows host has no Swift compiler or Mac DMG toolchain.
- The shared Android/macOS repository is still PRIVATE. A requested visibility change was rejected by the safety reviewer because it would expose historical private APKs, repository history and Actions logs; do not bypass this with a mirror or indirect publication. The owner was asked whether the historical artifacts may be disclosed. Public Windows CI success does not unblock this Mac build.
- Open question: exact failure of the user's intermittent Mac launch is UNKNOWN without a physical Mac and a redacted `~/Library/Logs/Veilark/startup.log` or macOS crash report.

## Recovery Summary

- Do not describe source commits as delivered to users. Do not point existing clients to a DMG that has not passed native Mac checks.
- Preview channel uses the established Ed25519 manifest key; changing it breaks older installed clients without a bridge release.
- `macos/docs/RELEASE_GATES.md` distinguishes JVM/CI from physical Mac acceptance and production signing.
