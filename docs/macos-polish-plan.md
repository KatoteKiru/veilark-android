# Veilark macOS polish plan

## Goal

Bring the existing macOS client to a coherent Veilark quality bar without changing its product identity or claiming platform guarantees that have not been proven on macOS hardware.

## Phase 1 — Visual and interaction polish

### 1. App shell

- Keep a compact macOS-style sidebar and a stable content region; the page itself must not scroll together with navigation.
- Make the current section, tunnel state, selected engine, and selected profile visible without competing headings.
- Use one primary connection action. Secondary actions use one consistent icon family, tooltips, keyboard focus, and disabled states.
- Support narrow and wide desktop windows without clipped labels or oversized empty regions.

### 2. Overview

- Replace the oversized generic panel with a compact connection control and a factual status summary.
- Show health, selected endpoint, routing mode, and actionable warnings in one scan path.
- Distinguish disconnected, connecting, connected, recovering, failed, and unavailable states.

### 3. Profiles

- Use a proper master-detail layout: subscription/profile list on the left, servers and source details on the right.
- Keep Add, Refresh, Ping, and Delete in a compact toolbar with accessible labels and tooltips.
- Provide explicit empty, loading, refresh-error, storage-unavailable, and destructive-confirmation states.
- Never remove or replace another subscription implicitly.

### 4. Routing

- Present Full tunnel, Russia direct, and Manual as mutually exclusive, concise choices.
- Expose manual domain/CIDR editing only when Manual is selected.
- State engine-specific limitations next to the affected control, not as a global disclaimer.
- Preserve DNS-hijack and loop-prevention rules when changing user routing rules.

### 5. Diagnostics and settings

- Use structured technical events: time, severity, component, code, and redacted message.
- Provide filter, copy, export, and clear actions without exposing credentials.
- Group helper, engines, updates, and app information into scannable settings rows.
- Display release notes and verification state before opening an OTA installer.

### 6. Visual system and accessibility

- Preserve Veilark blue and lock mark while reducing decorative color and excessive rounding.
- Use a restrained surface hierarchy, a single spacing scale, consistent icon sizes, and tabular numerals for measurements.
- Verify RU/EN expansion, keyboard traversal, visible focus, tooltips, semantic labels, contrast, and reduced-motion behavior.
- Motion is limited to meaningful state transitions; no background animation or battery-expensive effects.

## Phase 2 — Program correctness and security

### 1. Tunnel lifecycle

- Serialize connect/disconnect/reconnect and make duplicate actions idempotent.
- Reconcile the UI with the real engine process and invalidate stale `CONNECTED` state.
- Treat process start, TUN readiness, DNS, and egress as separate diagnostic stages.
- Disconnect cleanly on Quit and recover predictably after engine exit or network change.

### 2. Profiles and subscriptions

- Persist selected engine, subscription, server, and routing mode across restart.
- Support multiple independent sources, refresh-in-place, deletion with deterministic fallback, and latency checks.
- Preserve working catalogs and routes during migrations; never resurrect a source deleted during refresh.
- Accept supported native and provider subscription formats without logging bearer data.

### 3. Routing and leak controls

- Keep the sing-box rule order deterministic: DNS handling, private networks, explicit bypass/VPN rules, verified RU rule sets, final VPN route.
- Preserve or explicitly block IPv6 rather than leaving an unverified partial tunnel.
- Do not claim Android-style per-app routing for macOS without a supported Network Extension design.
- Treat TrustTunnel limitations as protocol facts, not UI omissions.

### 4. Privilege boundary

- Remove shell interpolation and fail closed on untrusted paths, ownership, permissions, or symlinks.
- The release architecture target is a signed, authenticated Apple service/Network Extension rather than a general setuid helper.
- A public release remains blocked until the privileged component is signed, caller-bound, notarized, and verified on real macOS.

### 5. Engines, OTA, and supply chain

- Pin stable upstream versions and expected archive hashes before extraction.
- Verify archive digest, Mach-O architecture, packaged binaries, and release metadata in CI.
- Fail packaging when required assets are absent or inconsistent.
- Verify OTA manifest signature, artifact SHA-256, HTTPS redirects, version ordering, and release notes before opening the installer.

## Acceptance gates

### Windows/JVM-verifiable

- Unit and parser/catalog/update tests pass.
- Kotlin compilation and packaging validation tasks pass with platform-only tasks explicitly excluded.
- No accidental secret material, debug output, or unrelated worktree changes are included.
- Representative UI renders show no clipping, broken wrapping, whole-page scrolling, or unexplained empty regions.

### macOS CI-verifiable

- macOS arm64 build and tests pass.
- DMG contains the expected app, engines, helper/extension, metadata, and hashes.
- Unsigned development artifacts are clearly separated from production release artifacts.

### Physical Mac release gate

- Fresh install and in-place update.
- Code signing, hardened runtime, notarization, stapling, and Gatekeeper verification.
- Repeated connect/disconnect, engine crash, sleep/wake, Wi-Fi/hotspot handover, DNS/IPv4/IPv6 leak checks, and real egress validation for sing-box and TrustTunnel.
- VoiceOver and keyboard walkthrough at narrow and wide window sizes.
