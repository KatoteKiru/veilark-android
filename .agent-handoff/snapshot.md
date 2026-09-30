# Handoff Snapshot

## Current State

- Last updated: 2026-09-30 (MSK)
- Workspace root: `C:\AI-Agent\veilark-macos`; shared Android/macOS repository `KatoteKiru/veilark-android` is PUBLIC by the owner's explicit approval.
- Objective: implement native macOS Liquid Glass navigation without changing VPN/session/OTA behavior.
- Active candidate: branch `codex/macos-native-glass`, source `b623821`. Final native CI `36687256301` and DMG verification `36687256258` both completed successfully. Runtime mode 2 confirms real Liquid Glass on macOS 26; macOS 14 fallback also passed. Full EN/light/empty Overview screenshot and scoped independent review passed. Re-signing verification normalizes only the proven signature-induced LINKEDIT VM reserve on temporary unsigned copies, then compares all remaining bytes; original packaged signature is verified separately. No new OTA/tag was published.
- Last recorded published release is 1.0.13/build 10013 (2026-09-29 project map); live manifest was not re-read during this UI task. Historical 1.0.12 evidence below is not current release state.
- Historical release evidence (superseded): preview `1.0.12` / build `10012` / ARM64, tag `macos-v1.0.12` at `7025d86`; verified DMG SHA-256 `40e6a0229bedbb3c2064ed2be88b90c6b34dd2166792455199a7878521d7df41`, 131502256 bytes.
- Native Apple Silicon CI `36560617114` and package verification `36560617122` passed. Release run `36561167600` passed tests, DMG packaging, Ed25519 manifest self-check, native in-app replacement, signed old-manifest check, artifact-before-manifest deployment, production redownload/SHA-256 and `hdiutil verify`.
- This is a preview DMG without Apple Developer ID signing/notarization. No physical Mac is attached, so installed 1.0.11→1.0.12 OTA, actual VPN traffic, menu-bar icon, data preservation and the reported intermittent no-window launch are not accepted.
- Immediate next action: collect physical RU/EN, light/dark, keyboard/VoiceOver, populated profiles and connected-session acceptance before promoting the candidate. Preserve existing release key and do not claim VPN/OTA acceptance from a screenshot.
- Active code: `macos/native/chrome.m`, `MacNativeChrome.java`, `NativeSidebar.kt`, `Main.kt`, `theme/Theme.kt`, `.github/workflows/macos-native-chrome.yml`; design contract `macos/docs/NATIVE_GLASS_DESIGN.md`.

## Recovery Summary

- Do not equate green CI and a signed OTA manifest with physical-device VPN acceptance.
- Preserve the established Ed25519 manifest key and versioned artifact lineage; older clients without an updater may still require one manual DMG installation.
- `macos/docs/RELEASE_GATES.md` defines release gates and physical Mac checks.
