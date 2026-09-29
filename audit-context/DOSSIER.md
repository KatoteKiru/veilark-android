# macOS tunnel context for Telegram media investigation

Scope: source-level orientation only. This document does not identify a cause or assert behavior on the affected Mac. Paths below are relative to repository root; `L` denotes source lines.

## Owner-reported reproduction (2026-09-29)

- One macOS user is affected; Telegram's own proxy is disabled.
- Sending files succeeds, but received media/files do not download.
- The failure reproduces with TrustTunnel in both full-tunnel and Russia-direct modes after a GEO update.
- Exact app build, selected TrustTunnel location, comparison with sing-box on the same Mac/network/file, and timestamped runtime logs are still unavailable. These observations do not identify a code defect by themselves.

## Flow and cross-function invariants

1. `VeilarkSession` restores the engine, selected profile IDs, and per-engine routing preferences from encrypted storage (`macos/src/main/kotlin/com/example/veilark/session/VeilarkSession.kt:L104-L160`). Offline routing changes are restricted to `all`, `manual`, or `ru_direct`; manual entries are validated against the selected engine before persistence (`VeilarkSession.kt:L618-L679`). Engine switching restores its own saved routing mode and manual entries (`VeilarkSession.kt:L766-L793`).
2. `connectLocked` stops the prior managed engine, snapshots the selected config, starts one engine through the helper, checks process status and known TUN-log failures, and runs a generic health probe (`VeilarkSession.kt:L431-L468`). A failed generic health probe is logged but does not prevent `CONNECTED` (`VeilarkSession.kt:L450-L463`).
3. The sing-box branch selects a node, applies the routing mode, and performs a macOS-specific migration using the physical default interface (`VeilarkSession.kt:L802-L842`). `select` sets the final outbound and secure-DNS detour to that node (`macos/src/main/kotlin/com/example/veilark/profile/ProfileSelection.kt:L54-L76`). `applyRouting` rebuilds route and DNS rules, retaining sniff and DNS hijack; RU mode adds local SRS rules and direct RU DNS (`ProfileSelection.kt:L79-L155`).
4. The generated sing-box base has IPv4-only DNS answers, an IPv4 TUN address, `auto_route=true`, `strict_route=false`, a 1280 MTU, bootstrap and secure DNS, and a final automatic outbound (`macos/src/main/kotlin/com/example/veilark/profile/SubscriptionParser.kt:L58-L115`, `L118-L158`). macOS migration binds bootstrap DNS and direct outbound to the physical interface when known; it preserves any supplied IPv6 TUN address and disables auto-interface detection when it has a physical interface (`SubscriptionParser.kt:L990-L1054`).
5. The TrustTunnel branch applies RU or manual exclusions, or normalizes the stored config (`VeilarkSession.kt:L844-L862`). Compilation sets a general-mode TUN, IPv4 default included route, local-network exclusions, MTU 1280, and system-DNS change (`macos/src/main/kotlin/com/example/veilark/protocol/TrustTunnelProfile.kt:L26-L48`). When `has_ipv6=false`, preparation replaces included routes with IPv4 default only (`TrustTunnelProfile.kt:L51-L64`). RU/manual paths use native `exclusions` and preserve listener exclusions (`TrustTunnelProfile.kt:L66-L128`).
6. The Kotlin boundary writes the selected config and invokes the privileged helper (`macos/src/main/kotlin/com/example/veilark/engine/PrivilegedHelper.kt:L97-L115`). The Swift helper stages a root-owned config, starts the chosen engine, records PID, and recognizes several startup route failures (`macos/helper/main.swift:L198-L284`). Its later `status` is process/PID based (`macos/helper/main.swift:L287-L299`).
7. Physical-route handover is based on `scutil` IPv4 primary interface with a `route get default` fallback, rejecting `utun` (`macos/src/main/kotlin/com/example/veilark/session/DefaultRouteFingerprint.kt:L20-L61`). A detected handover rebuilds the tunnel under the session mutex (`VeilarkSession.kt:L558-L585`).

## Assumptions and external dependencies

- A `CONNECTED` result establishes that the managed engine remains alive and no recognized startup TUN failure was reported; it does not establish that Telegram's media endpoints, transport, upload, or download work. The health probe resolves `example.com` and checks Cloudflare, Google, or Apple HTTPS endpoints (`VeilarkSession.kt:L443-L468`; `macos/src/main/kotlin/com/example/veilark/session/NetworkHealthProbe.kt:L21-L62`). A Telegram-media-specific check: **nothing found** in this flow.
- The configuration depends on selected profile content and the actual routing mode; sing-box manual rules may route named domains or CIDRs differently, while TrustTunnel manual mode builds native exclusions (`ProfileSelection.kt:L102-L113`; `TrustTunnelProfile.kt:L103-L128`). Which values the affected user selected: **nothing found** in repository evidence.
- Runtime routing depends on the physical interface snapshot, local GEO files in RU mode, the installed helper/engine versions, macOS route/DNS state, and engine behavior after startup (`VeilarkSession.kt:L432-L450`, `L828-L860`; `DefaultRouteFingerprint.kt:L20-L61`; `macos/helper/main.swift:L198-L284`). A capture from the affected Mac: **nothing found** in repository evidence.
- IPv6 capability differs by generated profile and engine. TrustTunnel's documented macOS compatibility mode is IPv4-only, and release gates require physical IPv4/IPv6/DNS-leak proof (`macos/README.md:L3-L5`; `macos/docs/RELEASE_GATES.md:L14`). This is context, not a conclusion about the observed Telegram failure.

## Open questions for the next, evidence-driven pass

1. Exact app build, macOS version, engine, node/profile fingerprint, mode, and whether Telegram text and small versus large media differ on the affected Mac.
2. Whether the same Mac succeeds with Veilark disconnected, and whether both sing-box and TrustTunnel reproduce with the same network and account. Avoid assuming a server-wide failure from one affected user.
3. A timestamped redacted app/helper diagnostic around a failed media request, plus contemporaneous IPv4/IPv6 routes, resolver state, physical interface, and actual active config fields (without credentials).
4. A controlled, privacy-preserving Telegram-media transaction trace distinguishing DNS, TCP/UDP connection, TLS, transfer start, and transfer stall. Generic connectivity checks cannot answer this (`NetworkHealthProbe.kt:L21-L62`).
