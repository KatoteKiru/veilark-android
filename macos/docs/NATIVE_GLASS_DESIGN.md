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

## Window chrome and build (2026-09-30, Liquid Glass extension)

- **Build:** release and CI DMGs compile the AppKit bridge, helper and updater with
  Xcode 26 / macOS 26 SDK on `macos-26` runners, with an explicit `-target
  <arch>-apple-macos12.0`. `LSMinimumSystemVersion` is `12.0` (Compose
  `minimumSystemVersion`). `scripts/check-build-version.sh` prints `LC_BUILD_VERSION`
  (minos/sdk) for the jpackage launcher and asserts minos `12.0` (and SDK ≥ 26 where
  required) for Veilark's own binaries; it runs after every `packageDmg`.
- **Fallback proof:** `macos-native-chrome.yml` builds the bridge once with the 26 SDK and
  runs the same dylib on macOS 14, 15 (must report the `NSVisualEffectView` material and
  standard toolbar bezels) and 26 (must report `NSGlassEffectView` and glass bezels).
  `macos.yml` still builds on the older default SDK, proving the source compiles without
  the macOS 26 declarations.
- **Window:** Kotlin sets AWT's `apple.awt.fullWindowContent` and
  `apple.awt.transparentTitleBar` (so AWT's style bits agree), native code adds a unified
  `NSToolbar` (`NSWindowToolbarStyleUnified`) and hides the title. The glass sidebar runs
  under the transparent titlebar with the traffic lights inside it; its contents start
  below `contentLayoutGuide`. The sidebar radius (18 pt at an 8 pt inset) is concentric
  with the macOS 26 toolbar-window corner. Compose content is padded by the measured
  top inset (`nativeState`), never by a guessed constant.
- **Toolbar items:** connection status (click → Overview) and Connect/Disconnect/Cancel
  (same semantics and enablement as the menu-bar item). On macOS 26 both use
  `NSBezelStyleGlass`, compiled only with the 26 SDK and guarded by
  `@available(macOS 26.0, *)`; earlier systems get the standard toolbar bezel.
- **Target window:** chosen by the `NSWindow` pointer that Compose reports
  (`ComposeWindow.windowHandle`, compared only against `NSApp.windows`, never
  dereferenced), else by title plus exact AWT frame, else by a title only when exactly
  one visible window has it. The 420×220 startup window can no longer receive the chrome.
- **Accessibility:** one `NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification`
  observer pushes a mask (Reduce Motion 1, Reduce Transparency 2, Increase Contrast 4) to
  Kotlin; `VisualPreferences` reads the mask first and falls back to `defaults read` only
  when the bridge is unavailable. Increase Contrast makes the Compose sidebar opaque.
- **Confirmations:** deleting a subscription uses a native `NSAlert` sheet (warning style,
  destructive button, Return = Cancel). If the sheet cannot be shown (no chrome, window
  hidden, another sheet attached), the Compose `AlertDialog` is shown instead.
- **Threading:** every JNI entry hops to the main queue. Synchronous reads wait at most
  0.5–2 s; a block not yet started by the deadline is cancelled, so a blocked AppKit
  thread cannot deadlock the JVM or later create an orphaned view. Callbacks into Java
  never block and clear pending Java exceptions.

## Acceptance

1. JVM tests: callbacks reject unknown indices; zero/missing attachment is harmless;
   status labels retain actual connection states.
2. macOS 14, 15 and 26 CI with the same macOS 26 SDK dylib: attach/update/remove against
   a real AWT window beside a same-titled decoy window, frame-based target selection,
   unified toolbar and non-zero top inset, material/toolbar mode per OS, display-option
   mask, late update and confirmation after removal (answered "not shown"), absent
   target window, native screenshots.
3. Packaged DMG inventory must contain a matching-architecture chrome dylib.
4. Full application screenshot must show the sidebar over the Compose layer without
   covering content. Check light/dark, RU/EN, small window, focus and Cmd1–Cmd5.
5. Physical Mac: VoiceOver, accessibility toggles, window close/reopen, connected VPN
   and OTA acceptance are distinct from CI and remain unverified without a device.
6. Launcher SDK (closed in `72cdde7`): the jpackage launcher comes from the JDK (SDK
   14.2). `stamp-launcher-sdk.sh` runs after `createDistributable` (Compose feeds that
   image to `packageDmg`) and uses `vtool -set-build-version macos 12.0 <xcrun SDK>` on
   every slice. It then re-signs the bundle with its previous identity: ad-hoc stays
   ad-hoc, and a real identity must come from `VEILARK_MACOS_SIGNING_IDENTITY`. Metadata
   is preserved, and `codesign --verify --deep --strict` must pass. AppKit's
   linked-on-or-after checks therefore see SDK 26. The AWT code in the JDK was still
   compiled against 14.2, so new AppKit behaviours need physical-Mac checks.

## Verification closeout

Source `b623821`: native macOS 14/26 CI `36687256301` and DMG CI `36687256258`
completed successfully. JVM tests and the four signature-normalization tests passed.
Independent review accepted the shown EN/light/empty Overview and narrow packaging
comparison. Original packaged code signature is checked; temporary unsigned copies
normalize only the proven signature-induced `__LINKEDIT.vmsize` reserve before strict
comparison. Distribution artifacts are never normalized or modified by that check.
No physical VPN/OTA acceptance or production promotion is implied by these results.

## Liquid Glass extension evidence (PR #7, source `feddc4e`)

- Native chrome run `36774897828`: bridge built on macOS 26 runner with SDK 26.5,
  `minos 12.0`. Same dylib on macOS 26.6.2: `material=2, toolbar=2, matchedBy=2,
  topInset=52, 960x640` (decoy 420x220 window ignored). macOS 14 and 15: fallback
  material and standard bezels asserted.
- Distributable on macOS 26: `LSMinimumSystemVersion 12.0`; chrome dylib, helper and
  updater `minos=12.0 sdk=26.5`; jpackage launcher `minos=11.0 sdk=14.2` (see gap 6).
- Package verification `36774897729` (macos-26, DMG) and preview `36774897842`
  (macos-14, older SDK compile) passed. The updater's new post-mount detach assertions
  run only in the tag-triggered OTA workflow and are not yet exercised.

Run `36777731739` (macos-26, DMG): packaged launcher and all Veilark binaries report
`minos=12.0 sdk=26.5`, and the bundle signature is valid (deep, strict). The running
packaged app reported `programSdk=26.5 material=2 toolbar=2 matchedBy=1 topInset=52`
(read from the main executable's `LC_BUILD_VERSION` via public dyld APIs). With the
older SDK on macos-14 (run `36777731711`), the launcher reports `sdk=14.5` and the
check is report-only.

## Sources

- [Apple NSGlassEffectView](https://developer.apple.com/documentation/appkit/nsglasseffectview)
- [Apple adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)
- [OpenAI SwiftUI Liquid Glass skill](https://github.com/openai/plugins/tree/main/plugins/build-ios-apps/skills/swiftui-liquid-glass)

The SwiftUI skill informed material scope, fallback and performance; the implementation
uses AppKit because the existing application is JVM/Compose. No networking rewrite,
core update, signing-key change or production OTA publication belongs to this candidate.
