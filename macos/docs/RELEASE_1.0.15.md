# macOS 1.0.15 / 10015

Published on the existing signed ARM64 preview OTA channel, source `765d5be`.
Release workflow 36756133471 succeeded, including native replacement, old-manifest
backup, signature lineage, atomic deploy, full redownload and disk-image verification.
Native macOS 14/26 notification-source CI 36754987315 also passed.

DMG: `veilark-macos-1.0.15-arm64.dmg`, 131912558 bytes.
SHA-256: `cf4fca5bd75da00a9205a49521f2161763df0be8ec2aaff6ab14274e6caa1cfe`.

Update checks now continue every six hours while the application is running,
including in the menu bar. One native notification per new release, subject to
system permission; click opens Settings. VPN, routing, cores and helper unchanged.
No automatic download or tunnel interruption. Existing OTA key retained.

Preview remains without Apple Developer ID/notarization. Physical notice delivery,
user upgrade, VPN traffic and sleep/wake were not tested on an attached Mac.
