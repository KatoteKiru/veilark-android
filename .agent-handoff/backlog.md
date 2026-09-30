# Task Backlog

- [x] Native AppKit material navigation with accessibility/appearance observers, safe Compose fallback and narrow JNI boundary.
- [x] Native macOS 14/26 lifecycle CI; strict NSGlassEffectView proof and actual full-window Overview screenshot.
- [x] Final DMG re-signing-aware verification (`36687256258`); promotion remains a separate release gate.
- [ ] Physical native chrome checks: RU/EN, light/dark, VoiceOver, accessibility changes, resize, populated profiles and active-session close/reopen.

- [x] Resolve shared repository visibility by explicit owner approval and verify public macOS CI can run.
- [x] Fix native macOS GEO test failure caused by `/var` vs `/private/var` path identity.
- [x] Reject the known privileged sing-box config write/listener footguns and test the Swift policy.
- [x] Publish 1.0.12 preview OTA via the existing signed, atomically deployed release workflow; verify live manifest and GitHub artifact identity.
- [ ] On a physical Mac, verify in-app 1.0.11→1.0.12 upgrade, profile preservation, app relaunch, menu-bar icon, sing-box/TrustTunnel connectivity and RU-direct traffic.
- [ ] Exercise active-tunnel Cmd+Q, logout/shutdown, Wi-Fi changes, helper cleanup and DNS/route restoration on macOS.
- [ ] Investigate intermittent no-window launch using redacted `~/Library/Logs/Veilark/startup.log` or crash report; do not guess the root cause.
- [ ] Independently review the remaining setuid helper/TrustTunnel configuration attack surface and consider a narrower privilege model without breaking supported profiles.
- [ ] Decide whether to obtain Apple Developer ID/notarization; until then label builds as preview and document Gatekeeper limitations.
