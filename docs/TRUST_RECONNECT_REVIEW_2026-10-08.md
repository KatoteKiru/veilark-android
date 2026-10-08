# TrustTunnel client reconnect review — 2026-10-08

Baseline: Android main 77da22d. This review does not establish the cause of the owner's evening outage and does not constitute physical Android traffic acceptance.

## Corrected callback logic

`TrustTunnelManager` previously accepted a native `CONNECTING` callback after an established connection, setting the visible state to Connecting. `TrustSessionFence` still retained `connected=true`, so the subsequent `CONNECTED` callback for that same admitted session was rejected as a duplicate. The accepted callback sequence `CONNECTED → CONNECTING → CONNECTED` could therefore leave the UI stuck in Connecting after the core recovered.

`acceptConnecting(sessionId)` now resets the duplicate-connected gate only for the current session that is not stopping. It retains session ownership; a new session remains blocked until the native close callback. Stale and stopping callbacks cannot reopen the gate. Consecutive `CONNECTED` duplicates remain rejected. Pending geo routing updates keep their existing current-session fence.

Existing RECOVERING/WAITING states deliberately remain visually Connected after an established connection; this change preserves that policy. Visible Connected is not proof of working traffic. The owner outage's actual callback trace was not available, so attribution to this defect is unknown.

## Transport observations

- Android's `TrustTunnelProfile.optimizeEndpoint` forces HTTP2, anti-DPI enabled, and no endpoint IPv6; Windows uses the official wizard-generated endpoint. Thus the native HTTP2 SOCKS probe corresponds to Android's chosen upstream transport, but bypasses Android TUN, protect/bind, and physical-network handover paths.
- HTTP3 fallback is not currently selectable through imported Android profiles. No transport change was made without direct working HTTP3 evidence and compatibility checks.
- Windows source review found no app-owned outbound interface binding. Endpoint socket/interface selection belongs to the native core. A native SOCKS TCP error before authentication cannot be attributed to Windows TUN setup from this evidence.

## Validation

- Targeted TrustSessionFenceTest: 5 tests passed.
- Full `:app:testOssDebugUnitTest --offline`: 188 tests, 0 failures, 0 errors, 3 gated skips; build successful.
- Kotlin app and unit-test sources compiled during the targeted run.
- `git diff --check` passed.
- No OTA, production service, server configuration, running owner VPN, or physical Android device changed.

Next release acceptance: collect affected device/version/network/location and redacted native connection-state trace; prove real traffic and Wi-Fi/mobile handover on an ARM device. Native transport/server investigation remains separate.
