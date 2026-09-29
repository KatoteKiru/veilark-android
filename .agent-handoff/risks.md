# Risks, Blockers, And Unknowns

## Current Blockers

- No physical Mac is connected for installed-version upgrade, real VPN traffic, menu-bar, profile preservation, system Quit and no-window launch acceptance.

## Current Risks

- Preview DMG lacks Apple Developer ID signing/notarization. Ed25519 protects the Veilark OTA manifest but does not confer Apple Gatekeeper distribution trust.
- Some early builds may have no updater or a different manifest lineage and need one manual DMG install. Exact affected build is UNKNOWN.
- The setuid helper now rejects unsupported sing-box top-level sections, log output paths, experimental cache-file settings and extra listeners before starting the root engine. This is a narrow mitigation, not a full audit of every supported engine configuration or a replacement for least-privilege architecture.
- macOS no-window launch may fail before `Main.main`; cause remains UNKNOWN without a redacted startup/crash log from the affected Mac.
- JVM shutdown hook and GEO route behavior have not been exercised on a physical Mac under active tunnel and network-change scenarios.

## Unknowns / Confirmations Needed

- UNKNOWN: affected Mac version/architecture, installed Veilark version, and redacted startup/crash evidence.
- UNKNOWN: whether the user's installed legacy version accepts this OTA directly; CI only exercised the packaged replacement flow.
