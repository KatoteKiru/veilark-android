# Android private rc38 — 2026-09-30

Published `0.8.0-rc38`, versionCode `65`, package
`uk.senyasenyavski.veilark`. Source commit: `7239b3c`.

## Changes

- More compact connection card and a rounded state-dependent logo surface.
- Contextual connection-button icon and a short pressed-shape transition.
- Stronger selected engine state with narrow-screen/large-font adaptation.
- Consistent section headings and native selection indicators for servers and routes.
- Matching foreground colours for connection/error surfaces and wrapping route descriptions.

Network cores, profiles and routing behaviour are unchanged.

## Verification

- Private release assembly, private unit tests and `lintPrivateRelease` passed.
- [GitHub Android verification](https://github.com/KatoteKiru/veilark-android/actions/runs/36679815237)
  passed for both `oss` and `ota-contract` jobs.
- Package/version and the established signing certificate passed the publisher's
  checks. APK Signature Scheme v3 verified; zip alignment passed; debuggable=false.
- Access-material scan passed. The previous live rc37 manifest/APK were copied
  and hash-checked in `/root/backups/veilark-android-ota-before-rc38-20260930T064433Z/`.
- APK was uploaded before the manifest. The publisher downloaded the complete
  public APK and verified its SHA-256 and size before reporting success.
- Independent final check: live manifest 65/rc38, APK HTTP 200, correct Android
  APK Content-Type and matching Content-Length.

APK: https://nl2.senyasenyavski.uk:2096/veilark/veilark-0.8.0-rc38.apk

SHA-256: `98A453A74CC098CF693D20BB28231AA741A3F5C4CE4EF0EC68D988B6438442B9`

Size: `141960675` bytes.

The owner requested this iteration without using their phone. Device rendering,
in-app installation, traffic and Wi-Fi/LTE handover were not exercised for rc38.
The rc36→rc37 in-place upgrade was tested separately on Pixel 8 Pro on 2026-09-29.
