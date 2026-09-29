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
| 2026-09-29 | Forced JVM tests after bounded shutdown hook | passed | `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain test --no-daemon --rerun-tasks --max-workers=2`; 12 tasks executed. Does not validate OS-level Quit on a Mac. |
| 2026-09-29 | Non-deploy macOS package CI `36558096490` | failed before steps | GitHub reports account payments/spending limit; 0 job steps. Neither Swift helper nor DMG was compiled. |
| 2026-09-29 | macOS CI `36559585392` / `36560120141` | failed | Exact JUnit failure was `/var` vs `/private/var` GEO bundle path identity; corrected in `a5e870e`. |
| 2026-09-29 | macOS preview CI `36560617114` and package verification `36560617122` | passed | Native Swift typecheck, helper policy tests, JVM tests, verified engines, DMG packaging and integrity on Apple Silicon CI. |
| 2026-09-29 | macOS OTA release run `36561167600` | passed | Signed manifest, native replacement test, previous live-manifest validation, atomic deploy, production redownload/SHA-256 and `hdiutil verify`, GitHub prerelease. |
| 2026-09-29 | Independent live manifest and GitHub release comparison | passed | 1.0.12 / 10012 / ARM64, SHA-256 `40e6a0229bedbb3c2064ed2be88b90c6b34dd2166792455199a7878521d7df41`, size 131502256. |
| 2026-09-29 | Physical Mac 1.0.11→1.0.12, launch and VPN traffic | not run | No physical Mac connected; user acceptance remains required. |
