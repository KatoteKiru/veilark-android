# Android private rc39 / 66

Published on the existing private OTA channel. Release APK source `ff5a57a`;
publisher backup improvement `2f03f35`. GitHub verification 36756252485 succeeded.
Private release assembly, all private debug unit tests and release lint passed.

APK: `veilark-0.8.0-rc39.apk`, 141948188 bytes.
SHA-256: `811D8C21FB5EAB0AF2170A665767476B2925354DC0D80E364F05E992A9F05C7E`.
Package: `uk.senyasenyavski.veilark`. Existing signer retained:
`4c6e3e834ed8d9ae2da0cf8cc177d283a83dabeeb7895655a04e212e1e0a15d3`.
V3 verification, zip alignment and artifact access-material scan passed.
Full public redownload matched the exact signed artifact before publisher success.

New: battery/network-constrained periodic signed-manifest checks in a lightweight
separate process, one update notification per release, tap to see notes and
confirm installation. No automatic download/install or VPN interruption.
VPN cores, routing and profile storage are unchanged.

Mandatory verified old manifest/APK snapshot:
`/var/backups/veilark/android/before-66-20260930T181541Z-70c5ca89`.
Restore its manifest atomically for discovery rollback; installed newer clients
do not automatically downgrade. Never reuse code 66 with different bytes.

The established APK signer is the historical Android debug certificate, although
the APK is a non-debuggable release. It was not silently replaced. No phone was
used; in-app installation and actual notification delivery remain unverified.
