# Handoff Snapshot

## Current State

- Last updated: 2026-10-01 (MSK)
- Workspace root: `C:\AI-Agent\veilark-macos`; shared Android/macOS repository `KatoteKiru/veilark-android` is PUBLIC by the owner's explicit approval.
- Objective: publish the owner's updated Claude application branches without overwriting prior version identities.
- Published: `macos-v1.0.16` / build10016 / ARM64, source `d77144f` from Claude `c713ea3`; signed preview OTA workflow `36789574156` succeeded. Live manifest and GitHub asset match: 131692646 bytes, SHA256 `e0a8db9dd424b4990d15da780c996f194ba426b5f9e2f756a2e49da46c506ca1`. See `macos/docs/RELEASE_1.0.16.md`.
- Native CI `36709802260` (macOS14/26, real glass mode2) and package CI `36709802270` passed. Release checks included Ed25519 signature lineage, native disposable-app replacement, timestamped previous-manifest backup, atomic publication, production redownload/hash and hdiutil verification. Fresh full-window screenshot: `C:/AI-Agent/reports/mac-native-glass-20260930/release14/reports/veilark-window.png`.
- Changes: SDK-26-stamped launcher, native unified VPN toolbar, Liquid Glass system chrome with older-system fallback, native delete sheet, live accessibility preferences, and updater image-detach cleanup. Main body remains Compose. Engines/routes/OTA keys and helper privileges retained.
- Re-signing verification normalizes only proven signature-induced LINKEDIT VM reserve on temporary unsigned copies; original packaged signature is checked separately.
- Preview lacks Apple Developer ID/notarization. No physical Mac is attached; real user upgrade/data preservation, VPN, VoiceOver and active-session close/reopen remain unverified, not inferred from CI.
- Immediate next action: collect physical RU/EN, light/dark, keyboard/VoiceOver, populated profiles and connected-session acceptance. Preserve the existing release key; investigate repeated helper prompts with actual installed version/files/permissions before changing privilege architecture.
- Active code: `macos/native/chrome.m`, `MacNativeChrome.java`, `NativeSidebar.kt`, `Main.kt`, `theme/Theme.kt`, `.github/workflows/macos-native-chrome.yml`; design contract `macos/docs/NATIVE_GLASS_DESIGN.md`.

## Recovery Summary

- Do not equate green CI and a signed OTA manifest with physical-device VPN acceptance.
- Preserve the established Ed25519 manifest key and versioned artifact lineage; older clients without an updater may still require one manual DMG installation.
- `macos/docs/RELEASE_GATES.md` defines release gates and physical Mac checks.
