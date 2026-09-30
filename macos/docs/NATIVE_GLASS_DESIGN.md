# macOS native chrome — 2026-09-30

## Direction

Preserve Veilark's monochrome identity and existing VPN/session behavior. Use Apple's
real material only for navigation, not a blur shader or translucent cards everywhere.
The main task remains choosing a profile and connecting; routing, diagnostics and
updates keep their existing functionality.

## Implementation boundary

- `native/chrome.m`: AppKit navigation overlay, native buttons, SF Symbols, system
  font/colors, canonical monochrome Veilark mark supplied as an in-memory PNG.
- macOS 26: runtime-resolved public `NSGlassEffectView`, no private API. Older macOS:
  `NSVisualEffectView` sidebar. Reduce Transparency: opaque system background.
- Accessibility-display changes rebuild the panel. Appearance/accent changes refresh
  layer-backed colors in the effective appearance. No continuous animations or timer
  loops are added; install retries are bounded, and removal unregisters observers.
- JNI accepts public labels and section indices only. No credentials, profile paths,
  subscription URLs, privileged operations or engine settings cross this boundary.
- If loading/attachment fails, the Compose navigation stays available. The primary
  content remains Compose; this is not a claim that the entire client became SwiftUI.
- `Theme.kt`: compact desktop typography; connection panel has restrained neutral
  fill, a single primary action and the existing cancel/stop semantics.

## Acceptance

1. JVM tests: callbacks reject unknown indices; zero/missing attachment is harmless;
   status labels retain actual connection states.
2. macOS 14 and 26 CI: compile JNI/AppKit, attach/update/remove against a real AWT
   window, late update after removal, absent target window, native screenshots.
3. Packaged DMG inventory must contain a matching-architecture chrome dylib.
4. Full application screenshot must show the sidebar over the Compose layer without
   covering content. Check light/dark, RU/EN, small window, focus and Cmd1–Cmd5.
5. Physical Mac: VoiceOver, accessibility toggles, window close/reopen, connected VPN
   and OTA acceptance are distinct from CI and remain unverified without a device.

## Verification closeout

Source `b623821`: native macOS 14/26 CI `36687256301` and DMG CI `36687256258`
completed successfully. JVM tests and the four signature-normalization tests passed.
Independent review accepted the shown EN/light/empty Overview and narrow packaging
comparison. Original packaged code signature is checked; temporary unsigned copies
normalize only the proven signature-induced `__LINKEDIT.vmsize` reserve before strict
comparison. Distribution artifacts are never normalized or modified by that check.
No physical VPN/OTA acceptance or production promotion is implied by these results.

## Sources

- [Apple NSGlassEffectView](https://developer.apple.com/documentation/appkit/nsglasseffectview)
- [Apple adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [OpenAI SwiftUI Liquid Glass skill](https://github.com/openai/plugins/tree/main/plugins/build-ios-apps/skills/swiftui-liquid-glass)

The SwiftUI skill informed material scope, fallback and performance; the implementation
uses AppKit because the existing application is JVM/Compose. No networking rewrite,
core update, signing-key change or production OTA publication belongs to this candidate.
