# Handoff Snapshot

## Current State

- Last updated: 2026-09-30 (MSK)
- Workspace root: `C:\AI-Agent\veilark-macos`; shared Android/macOS repository `KatoteKiru/veilark-android` is PUBLIC by the owner's explicit approval.
- Objective: publish the owner's approved macOS 1.0.14 UI update through the existing signed preview OTA channel, without changing VPN/session/helper behavior.
- Published: `macos-v1.0.14` / build10014 / ARM64, source `c695d15`; signed preview OTA workflow `36710379427` succeeded. Live manifest and GitHub release independently report matching version/size: 131887575 bytes, SHA256 `1c06bdaaa31af2c00d3ae55e07931b47176a574a8f1a0b6bb4d069967ce25e8f`.
- Native CI `36709802260` (macOS14/26, real glass mode2) and package CI `36709802270` passed. Release checks included Ed25519 signature lineage, native disposable-app replacement, timestamped previous-manifest backup, atomic publication, production redownload/hash and hdiutil verification. Fresh full-window screenshot: `C:/AI-Agent/reports/mac-native-glass-20260930/release14/reports/veilark-window.png`.
- Changes: native glass navigation, consistent SF Symbols, bounded 140–160ms transitions honoring Reduce Motion. Main body remains Compose. Network/session/routing/engines and helper v7 are unchanged from1.0.13. A compatible installed helper v7 does not become stale just because the app version changes.
- Re-signing verification normalizes only proven signature-induced LINKEDIT VM reserve on temporary unsigned copies; original packaged signature is checked separately.
- Preview lacks Apple Developer ID/notarization. No physical Mac is attached; real user upgrade/data preservation, VPN, VoiceOver and active-session close/reopen remain unverified, not inferred from CI.
- Immediate next action: collect physical RU/EN, light/dark, keyboard/VoiceOver, populated profiles and connected-session acceptance. Preserve the existing release key; investigate repeated helper prompts with actual installed version/files/permissions before changing privilege architecture.
- Active code: `macos/native/chrome.m`, `MacNativeChrome.java`, `NativeSidebar.kt`, `Main.kt`, `theme/Theme.kt`, `.github/workflows/macos-native-chrome.yml`; design contract `macos/docs/NATIVE_GLASS_DESIGN.md`.

## Recovery Summary

- Do not equate green CI and a signed OTA manifest with physical-device VPN acceptance.
- Preserve the established Ed25519 manifest key and versioned artifact lineage; older clients without an updater may still require one manual DMG installation.
- `macos/docs/RELEASE_GATES.md` defines release gates and physical Mac checks.
