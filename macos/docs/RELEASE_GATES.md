# macOS release gates

Veilark for macOS remains a development preview until every gate below is evidenced on a physical Mac. A green JVM build alone is not release evidence.

## Architecture

- Replace the transitional setuid helper and AppleScript installer with `NEPacketTunnelProvider`, or a signed `SMAppService` LaunchDaemon using authenticated XPC and a designated client requirement.
- Move route and DNS ownership into the system extension. Prove deterministic cleanup after crash, forced termination, logout, and reboot.
- Obtain the required Apple entitlement and use a stable Developer ID identity with hardened runtime.

## Network acceptance

- Test Apple Silicon and Intel on current supported macOS releases.
- Prove IPv4, IPv6, and DNS leak behavior for sing-box and TrustTunnel. The current IPv4-only compatibility mode must not be described as a kill switch without this proof.
- Exercise twenty connect/disconnect cycles, sleep/wake, Wi-Fi to hotspot/Ethernet transitions, engine crash, captive portal, subscription expiry, and unavailable DNS.
- Verify Russia-direct and manual rules against packaged SRS files while confirming that the proxy endpoint itself never routes into the tunnel recursively.

## Delivery and OTA

- Fetch engines and geo data only from pinned upstream revisions with committed SHA-256 values.
- Sign nested engines, helper/extension, app, and DMG in the correct inside-out order.
- Notarize, staple, and verify with `codesign`, `spctl`, and `stapler validate` in CI.
- Publish only a signed Ed25519 OTA manifest. The client must verify manifest signature, architecture, monotonically increasing build, DMG SHA-256, disk image integrity, and Gatekeeper before opening it.
- Install an older release and prove a real in-place update that preserves profiles, routing, and selected nodes.

## Native macOS UI

- Release DMGs are built with Xcode 26 / macOS 26 SDK; `check-build-version.sh` must show
  minos `12.0` for the chrome dylib, helper and updater, `LSMinimumSystemVersion` `12.0`,
  and SDK ≥ 26 for Veilark's own binaries. The launcher's SDK is recorded with each release.
- The SDK 26 chrome dylib must pass `NativeChromeMacTest` on macOS 14, 15 (fallback
  material, standard bezels) and 26 (`NSGlassEffectView`, glass bezels).
- On a physical macOS 26 Mac: unified toolbar with glass items, sidebar glass under the
  titlebar, native delete-confirmation sheet, and live toggling of Reduce Transparency,
  Increase Contrast and Reduce Motion without restarting. Repeat on macOS 12–15 for the
  fallback look.

## Product acceptance

- Verify RU and EN copy, keyboard navigation, focus, VoiceOver names, light/dark appearance, resize behavior, empty/error/loading states, and tray commands on a real Mac.
- Confirm subscription import, refresh, deletion, and multiple independent sources for sing-box and TrustTunnel.
- Redact credentials, bearer links, UUIDs, and endpoint secrets from diagnostics and crash reports.
