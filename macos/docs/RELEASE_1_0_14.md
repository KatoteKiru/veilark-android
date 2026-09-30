# macOS 1.0.14 / 10014

## Scope

- Native AppKit Liquid Glass navigation on macOS 26, system vibrancy fallback on older macOS and an opaque Reduce Transparency mode.
- Consistent 15pt medium SF Symbols, canonical monochrome logo, compact desktop typography.
- Bounded selection and state-color transitions (140–160ms), no decorative idle loops; Reduce Motion is honored.
- Main task body remains Compose. Engines, routing, profiles, privileged helper v7 and the established OTA signing key are unchanged from 1.0.13.

## Helper installation

The helper is installed outside the application bundle under `/Library/PrivilegedHelperTools`.
Readiness checks require the helper, both installed engines and helper.version = 7.
It is not tied to the application's build number. A user already running helper v7
should not need to reinstall it for this UI release. Updating missing or incompatible
privileged components requires explicit macOS administrator authorization; the client
does not silently bypass it. Repeated prompts with v7 installed need the affected
machine's file/permission evidence, not an assumed cause.

## Evidence and limits

- Published through successful preview OTA workflow `36710379427` at tag `macos-v1.0.14` / c695d15. Live manifest and GitHub report build10014, 131887575 bytes and SHA256 `1c06bdaaa31af2c00d3ae55e07931b47176a574a8f1a0b6bb4d069967ce25e8f`.

- Local JVM tests passed after the motion changes; native integration is skipped on Windows.
- Initial native CI caught missing QuartzCore linkage; c695d15 fixes the linker input.
- Final native Mac CI `36709802260` and DMG package verification `36709802270` passed at c695d15. Fresh full-window screenshot and true-glass mode 2 on macOS 26.6.2 were inspected.
- Icon tests check vector-generated 22/44/66px menu-bar variants and monochrome alpha-mask behavior.
- Release workflow verifies the established Ed25519 lineage, real native replacement on a disposable app, DMG integrity and redownloaded production bytes. It retains a timestamped copy of the previous manifest before promotion; previous versioned DMGs are preserved.
- This remains the existing preview channel, not an Apple-notarized release. CI and screenshots do not establish physical-device VPN, battery, VoiceOver or every populated-profile scenario acceptance.
