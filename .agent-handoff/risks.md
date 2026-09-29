# Risks, Blockers, And Unknowns

## Current Blockers

- GitHub Actions macOS job `36482518741` failed before any step: billing/spending-limit annotation. No registered self-hosted runner; no local Mac toolchain. No safe DMG/OTA publication path on this Windows host.
- Requested PRIVATE-to-PUBLIC change for the shared Android/macOS repository was rejected by the safety reviewer due potential exposure of old private APKs, history and Actions logs. Keep it private unless a direct, explicitly approved path is allowed after further review; do not work around the rejection.
- No physical Mac connected for launch, menu bar, installed 1.0.11→next update, data preservation, or VPN-traffic acceptance.

## Current Risks

- Preview DMGs lack Apple Developer ID signing/notarization. Ed25519 verifies Veilark OTA integrity but does not replace Gatekeeper distribution trust.
- The starter version installed by a friend may have a different signing/updater lineage; exact version and on-disk bundle identity are UNKNOWN. Builds without an updater require one manual install.
- Intermittent no-window launch could fail before `Main.main`; source-only unit tests cannot diagnose that without macOS logs.
- The new bounded JVM shutdown hook compiles and passes JVM tests on Windows, but macOS Cmd+Q, forced termination, helper cleanup and IPv6/DNS behavior have not been tested on a Mac. The setuid helper still runs user-supplied engine configuration as root after path/ownership checks; do not claim the Gemini MAC-002/MAC-003 findings are closed.

## Unknowns / Confirmations Needed

- UNKNOWN: precise macOS version/architecture, installed Veilark version, and redacted startup/crash log from affected user.
- UNKNOWN: when GitHub billing is restored or an isolated Mac runner becomes available.
