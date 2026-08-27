# Changelog

All notable changes to the public distribution are documented here.

## 0.8.0-rc15-oss

- added an offline `Russia direct` mode for sing-box: Russian domains and IP
  ranges bypass the VPN while all other traffic stays in the tunnel;
- packaged rule sets are integrity-checked and installed atomically without
  background downloads;
- preserved manual domain/CIDR rules and per-application split tunnelling as
  independent routing controls.

## 0.8.0-rc14-oss

- updated the production sing-box core from 1.13.14 to stable 1.13.19;
- updated the Android TrustTunnel adapter and native core from 1.0.49 to the
  stable 1.1.4 source tag;
- retained Veilark per-application split tunnelling and Android foreground
  service lifecycle fixes on the new TrustTunnel adapter;
- rebuilt and verified both native engines for `arm64-v8a` and
  `armeabi-v7a`.

## 0.8.0-rc13-oss

- added complete English and Russian UI selected by Android system locale;
- added an in-app About screen with source link, attribution, full license
  texts, and third-party notices;
- added a bilingual privacy policy and an in-app link to it, including accurate
  ML Kit metrics and manual diagnostics disclosures;
- localized TrustTunnel connection errors, notifications, Quick Settings tile,
  QR scanner, routing, subscription, and update surfaces;
- added full English and Russian project documentation.

## 0.8.0-rc12-oss

- first source release with a dedicated `oss` product flavor;
- no bundled profiles, private subscription or self-hosted update channel;
- sing-box 1.13.14 and TrustTunnel 1.0.49 engine adapters;
- multi-subscription import, refresh, selection and deletion;
- link, clipboard, file and CameraX QR import;
- per-application routing for both engines;
- technical journal, latency checks and Quick Settings tile.
