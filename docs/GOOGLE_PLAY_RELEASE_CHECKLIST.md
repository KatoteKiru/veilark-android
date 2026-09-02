# Google Play release checklist

This checklist covers the public package `app.veilark.android`. It records
store-side work that cannot be completed from the source repository alone.

## Policy and legal

- Publish `PRIVACY.md` at a stable public URL and enter that URL in Play Console.
- Complete the Play Console `VpnService` declaration. VPN is the app's core
  function; the store description must say so plainly.
- Explain that Veilark sends user-selected traffic through a user-supplied VPN
  endpoint and does not monetise, inspect, or sell that traffic.
- Complete Data safety from the final dependency and runtime audit. The QR
  decoder is local ZXing core, but Camera permission and every remaining SDK
  must still be declared from the final release artifact.
- Keep the in-app About screen, license texts, third-party notices, independent
  project disclaimer, and privacy link in every production build.
- Re-check the current Google Play policies before each submission. This file is
  an engineering checklist, not legal advice.

Official references:

- [Google Play VpnService policy](https://support.google.com/googleplay/android-developer/answer/12564964)
- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [ZXing project and Apache-2.0 license](https://github.com/zxing/zxing)

## English and Russian listing

- Create `en-US` and `ru-RU` store listings with a natural title, short
  description, full description, and release notes.
- Use screenshots captured under each actual locale. Do not reuse Russian
  screenshots for the English listing.
- Mention supported formats as a compatible subset, not universal compatibility.
- Do not imply affiliation with AdGuard, TrustTunnel, SagerNet, or sing-box.
- Use the same public support and privacy links in both listings.

## Release evidence

- Build from the tagged clean revision and keep the CI run URL.
- Verify package ID, `versionCode`, `versionName`, signer certificate, APK
  signature schemes, and SHA-256 after downloading the published artifact.
- Test fresh install and upgrade on physical ARM devices.
- Exercise English and Russian UI, QR permission denial/approval, subscription
  import/refresh/delete, both engines, per-app routing, Quick Settings tile,
  Wi-Fi/cellular handover, notification controls, and a 30-minute battery soak.
- Run Play pre-launch reports and review Android vitals before expanding rollout.
- Use staged rollout and retain the previous working release for rollback.
