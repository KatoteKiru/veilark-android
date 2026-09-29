# Current Work Log

## 2026-09-29

- Confirmed live macOS OTA is 1.0.11/10011 while post-release source contains GEO, helper recovery and Keychain fixes.
- Confirmed latest macOS preview CI jobs never started due GitHub account billing; no self-hosted runner registered.
- Prepared source version 1.0.12/build 10012 and corrected release notes. No release tag, Mac DMG, OTA manifest or VPN service changed in this step.
- Forced all macOS JVM tests to rerun (12 tasks, passed); verified the current Ed25519-signed 10011 manifest precedes candidate build 10012.
- Pushed `e993024` to the private source branch; intentionally did not create a release tag or modify the live OTA channel.
- Created a durable, secret-free release handoff.
- Owner explicitly approved making the shared Android/macOS repository public; visibility changed and native macOS Actions became runnable. This supersedes the earlier billing block.
- Diagnosed native GEO test failure as macOS `/var` versus `/private/var` path alias and canonicalized the published generation (`a5e870e`).
- Added narrowly scoped setuid-helper sing-box config policy, Swift adversarial tests and Mac CI coverage; renamed the multi-file Swift entry source to `main.swift` (`d0fd27a`, `7025d86`).
- Native CI `36560617114` and `36560617122` passed. Tagged `macos-v1.0.12`; OTA release `36561167600` passed including deployment/redownload. Live 1.0.12 manifest and GitHub prerelease have identical DMG SHA-256/size. Physical Mac acceptance remains open.
