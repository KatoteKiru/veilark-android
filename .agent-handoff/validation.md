# Validation History

| Date | Check | Result | Notes |
| --- | --- | --- | --- |
| 2026-09-29 | Live HTTPS macOS OTA manifest | passed | Still version 1.0.11 / build 10011, ARM64. |
| 2026-09-29 | Forced JVM test (`test --rerun-tasks`) | passed | 12 tasks executed; does not compile Swift helper/updater or DMG. |
| 2026-09-29 | Signed live-manifest preflight | passed | Ed25519 signature verified; live build 10011 is older than prepared 10012. |
| 2026-09-29 | GitHub Actions run `36482518741` | failed before steps | Billing/spending-limit annotation. |
| 2026-09-29 | Self-hosted runner API | passed | Total registered runners: 0. |
| 2026-09-29 | Physical Mac launch, update, VPN traffic | not run | No Mac attached. |
| 2026-09-29 | Native DMG/package/update | not run | Windows cannot run Apple packaging/verification. |
