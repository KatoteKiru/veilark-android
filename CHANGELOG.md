# Changelog

All notable changes to the public distribution are documented here.

## 0.8.0-rc24

- restored selectable Russia-direct routing for TrustTunnel using the pinned
  offline IPv4/IPv6 network set; a failed exclusion update now keeps a working
  full tunnel instead of leaving a false connected state;
- excluded the Veilark process from its own sing-box TUN in every application
  routing mode, preventing a self-routing loop with geo and app filters;
- fenced asynchronous TrustTunnel route updates by session so a late result
  cannot modify a newer connection or restart a stopped tunnel;
- retained existing subscriptions, profile selection and OTA identity.

## 0.8.0-rc23

- made saved sing-box profile switching reapply and validate the currently
  selected application, geo and DPI routing settings instead of retaining a
  stale runtime configuration;
- hardened CameraX and ML Kit teardown so camera bind or recreation failures
  reach a recoverable error state instead of crashing the QR import screen;
- kept independent remote subscriptions intact when one source is refreshed;
- removed the unsupported TrustTunnel Russia-direct control from the current
  release UI while preserving Android per-application split tunnelling.

## 0.8.0-rc22

- restored stable TrustTunnel traffic by disabling the unverified runtime
  Russia-direct exclusion update; TrustTunnel now falls back to a full tunnel
  while preserving Android per-application routing;
- moved QR camera permission and CameraX ownership into one activity and
  hardened camera, analyzer and ML Kit failure handling;
- added confirmed Veilark import links for HTTPS subscriptions and `tt://`
  profiles, including HTTPS sources on non-default ports;
- kept sing-box routing and imported subscription data unchanged during the
  compatibility update.

## 0.8.0-rc19

- hardened TrustTunnel start/stop lifecycle so rejected foreground-service
  starts, routing failures and timeouts always reach a terminal state;
- fenced TrustTunnel callbacks by session so late or duplicate native events
  cannot fail a newer connection;
- released TrustTunnel network callbacks and its service executor after a
  completed session, including reliable start-stop-start registration;
- bounded and cancelled manual endpoint checks to prevent stale background
  probes after a new request or engine stop;
- moved sing-box command-channel connects and URL tests to bounded IO work and
  ignored late latency/log callbacks after stop;
- moved sing-box native shutdown off the Android service main thread and kept
  teardown idempotent.

## 0.8.0-rc16-oss

- added TrustTunnel `Russia direct` routing using the pinned offline IPv4/IPv6
  network set;
- applied TrustTunnel routing dynamically at every connection start without
  duplicating the network set in saved profiles;
- kept TrustTunnel application rules and sing-box-only domain routing separate.

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
