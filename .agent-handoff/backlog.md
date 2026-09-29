# Task Backlog

- [x] Finish forced local macOS JVM test and signed-live-manifest preflight; record results.
- [x] Prepare source version 1.0.12/build 10012 and accurate user-facing release notes; keep tag unpublished while Mac build path is blocked.
- [ ] Restore GitHub Actions billing or provision an isolated Mac runner; never install a persistent runner on the main PC or VPN servers as a shortcut.
- [ ] Resolve the Android/macOS repository visibility decision without bypassing the safety-review rejection; public history includes old private APK releases and Actions logs. Until then use a properly authorized Mac build route.
- [ ] Run full Mac release workflow: pinned engines, DMG integrity, signed manifest, native replacement test, artifact-before-manifest deployment, redownload.
- [ ] On physical Mac, verify startup, menu-bar icon, profile preservation, VPN traffic and in-app OTA from the installed older version.
- [ ] On physical Mac, test system Cmd+Q and logout/shutdown with an active tunnel; confirm the helper stops, routes/DNS restore, and the hook does not hang application exit.
- [ ] Review and constrain the setuid helper's engine configuration inputs without breaking supported profiles; test adversarial log/cache file targets in native Swift.
- [ ] Investigate intermittent no-window launch using redacted `~/Library/Logs/Veilark/startup.log` or macOS crash report; do not guess root cause.
