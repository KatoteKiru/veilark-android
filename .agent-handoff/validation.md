# Validation History

| Date | Check | Result | Notes |
| --- | --- | --- | --- |
| 2026-09-30 | 1.0.14 OTA workflow36710379427 | passed and published | Ed25519 lineage, native replacement, previous manifest backup, atomic deploy, production redownload/SHA and hdiutil. Live manifest and GitHub asset match version10014/size131887575; physical user acceptance remains open. |
| 2026-09-30 | 1.0.14 / c695d15 native UI CI36709802260 and DMG CI36709802270 | passed | Fixed missing QuartzCore linkage found by the first motion CI. Full fresh macOS 26 screenshot inspected; runtime mode 2. Helper, engines and routing unchanged from tag1.0.13. |
| 2026-09-30 | Final source b623821: native CI `36687256301`, DMG CI `36687256258` | passed | Real native UI on macOS 14/26; package integrity/signature/inventory and normalized unsigned native byte comparison. Not physical VPN/OTA acceptance. |
| 2026-09-30 | Code closeout | passed within scope | Diff whitespace check clean; production macOS sources contain no TODO/FIXME/debug println/GlobalScope/Timer additions; only wrapper JAR is tracked binary in macOS source inventory. Network/session/helper/OTA configuration unchanged. |
| 2026-09-30 | Signature normalization independent gate | accepted | Actual binary pair differs only in LINKEDIT reserve after signature removal; four tests include rejection of signed/malformed input and preservation of payload differences. |
| 2026-09-30 | JVM UI tests | passed | Includes JNI callback bounds, no-handler callback, zero-handle fallback and status labels; native integration skipped on Windows. |
| 2026-09-30 | Native macOS 14/26 CI `36683764827` and `36684435115` | passed | Real JNI attach/update/remove test, native fixture screenshots; c0a7017 also captures complete Veilark on macOS 26. |
| 2026-09-30 | DMG CI `36683764786`, `36684435028` | passed | Compiled dylib included in matching-architecture candidate DMG; full macOS 14 window captured. No OTA publication. |
| 2026-09-30 | Independent native-chrome review | fixed in source | Accessibility observer, effective-appearance colors, autorelease pools, then repeat-click selected state. No new P1 reported; physical acceptance not claimed. |
| 2026-09-30 | Native forced-glass CI `36684981729` | passed | Source 7f4031f; macOS 26.6.2 reports material mode 2, macOS 14 also passed. Full Overview capture inspected; scoped reviewer accepted EN/light/empty state and repeat-click fix. |
| 2026-09-30 | Package CI `36684981839`, `36685699562`, `36686375789` | failed, subsequently corrected | Actual binaries established retained LINKEDIT VM reserve after signature removal; b623821 narrowly normalizes only that field. Final CI36687256258 passed. |
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
