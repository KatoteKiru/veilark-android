# Veilark Android

Veilark is a native, modular Android VPN client. The application owns the user
experience, routing policy, diagnostics, secret storage, lifecycle, and update
verification. Protocol implementations are isolated behind `TunnelEngine`.

## Product invariants

- Never open the Android TUN interface until the selected engine passes a probe.
- Never silently downgrade certificate verification or cryptographic policy.
- Never write credentials, private keys, subscription URLs, or complete IP
  addresses to logs.
- Every connection stage has a bounded timeout and a stable diagnostic code.
- Routing and DNS policy are deterministic and testable without a live server.
- Release updates must be signed by both the immutable Android signing key and
  an offline Ed25519 update-manifest key.

## Engine strategy

| Family | Planned implementation | License impact |
|---|---|---|
| TrustTunnel | Official Apache-2.0 client core with a patched Android adapter | Permissive |
| Trojan, Hysteria 2, VLESS, Shadowsocks, TUIC | Isolated sing-box-based engine | GPLv3 distribution obligations |
| WireGuard | Android userspace/native engine | Engine-specific notices |

The GPL engine is kept behind a process/module boundary. Distribution will
include corresponding source and license notices. This boundary improves
upgrade safety; it does not remove GPL obligations.

## Update channels

1. Google Play / managed store channel.
2. Self-hosted signed channel for environments where store access is unreliable.

The self-hosted channel publishes a minimal signed manifest containing version,
SHA-256, APK URL, size, and release notes. Android still requires user
confirmation for normal sideloaded updates. Staged rollout and a minimum
supported version remain release-control work rather than current behavior.

## Initial compatibility

- Minimum Android: 10 (API 29)
- Initial target: Android 16 (API 36)
- Android 17 compatibility: mandatory physical-device test
- Primary ABI: arm64-v8a
- Reference device: Pixel 8 Pro

## Current development build

`0.8.0-rc14` contains two independently selectable engines:

- sing-box 1.13.21 with bounded import for VLESS, Trojan, Hysteria 2, VMess,
  Shadowsocks, TUIC, and AnyTLS; unsupported or graph-dependent profiles must
  be reported instead of being described as universally compatible;
- the official TrustTunnel 1.1.5 Android core with HTTP/2, HTTP/3, anti-DPI,
  post-quantum groups, network recovery, and a full-tunnel kill switch;
- arm64-v8a and armeabi-v7a native libraries;
- HTTPS subscriptions, JSON profiles, and `tt://` deep-link import;
- simultaneous encrypted storage of the primary and TrustTunnel profiles with
  in-app engine switching;
- an encrypted multi-profile TrustTunnel catalog with endpoint selection and
  H2/H3 status; reachability checks are explicit user actions rather than
  automatic post-connect traffic;
- a public `oss` flavor with no bundled profiles or private update channel;
- a separate `private` flavor that can generate managed TrustTunnel profiles
  from ignored local inputs without placing them in source control;
- per-application `all`, `only`, and `bypass` routing for both engines through
  Android `VpnService.Builder`; explicit user-defined VPN/direct domain or CIDR
  rules and the offline Russia-direct geo preset remain specific to sing-box;
- a source-tracked TrustTunnel Android adapter patch that preserves Android 14+
  `specialUse` foreground-service startup and early-failure service cleanup;
- an explicit 1280-byte TUN MTU, authenticated Cloudflare DoH, and DNS reverse
  mapping for reliable browser, YouTube, and manual domain routing;
- staged diagnostics and redacted technical logs without periodic external
  internet probes;
- signed, self-hosted OTA manifests and resumable in-app HTTP Range APK downloads
  with progress, signed release notes, SHA-256, package/version/signer verification,
  and the mandatory Android package-install confirmation flow;
- bounded automatic recovery of the sing-box command channel used for live
  latency measurements after network handovers;
- digest-based built-in TrustTunnel provisioning that avoids redundant
  Android Keystore writes on every application launch;
- Android-native per-application bypass applied independently of sing-box
  auto-route, with a deferred icon-bearing application picker;
- deduplicated physical-network callbacks and a six-hour no-update cache to
  reduce background CPU and radio wakeups;
- a one-time stable full-tunnel recovery that removes stale application/domain
  bypass and experimental TLS fragmentation from previously stored profiles;
- managed TrustTunnel profiles pinned to HTTP/2 with IPv4 endpoint routing;
  this avoids false-positive HTTP/3 connections that complete the QUIC
  handshake but later stall under UDP shaping or server buffer pressure;
- a bounded lazy profile picker plus clipboard-assisted subscription import;
- a live Android Quick Settings tile that toggles the selected engine, delegates
  the initial VPN permission grant to the activity, and opens Veilark on
  long-press;
- explicit sing-box and TrustTunnel mode controls that remain switchable after
  a failed connection, plus a bounded persistent technical event journal;
- a standard private ongoing sing-box notification with current state, profile,
  system chronometer, app open action, and idempotent disconnect action;
- manual pre-connect TCP reachability checks for sing-box endpoints and
  TrustTunnel servers, without idle polling or radio wakeups;
- in-place Android underlying-network handover for sing-box and stable
  recovery-state presentation for TrustTunnel during Wi-Fi/LTE transitions;
- an in-app CameraX `Preview` plus `ImageAnalysis` QR scanner with bundled ML Kit,
  back/front camera fallback, bounded frame processing, and redacted failure codes;
- a restrained graphite/slate adaptive, monochrome, and legacy Veilark launcher identity;
- a reduced arm64 transition OTA for legacy `0.4.1-dev` clients, with a
  separately published universal arm64/armv7 artifact.

The `veilarkCoreCanary` flavor keeps its distinct application id for future
engine experiments. In this release it deliberately uses the same verified
sing-box 1.13.21 artifact as production.

Profile payloads are encrypted with an Android Keystore AES-GCM key. The OSS
release uses a dedicated signing identity and application id. The private
release retains its established signer for update compatibility; the two
channels cannot update or overwrite one another.
