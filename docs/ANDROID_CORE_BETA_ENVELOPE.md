# Android Core beta delivery contract — 2026-10-12

Status: strict parser/encrypted storage, real Android service, three-engine UI,
quick-settings tile and lifecycle/JNI wiring implemented. Full private JVM suite
240 tests: 0 failures/errors, 3 existing skipped; release/debug builds and private
release lint pass (0 errors, 91 warnings). Runtime ARM64 artifact supplied/pinned;
server/network readiness and first physical user test remain open.
No production OTA has been published from this branch.

Android owner checkout: `veilark-android-audit-20261007`, branch
`codex/android-core-beta-geo-20261011`. Existing engines remain sing-box and
TrustTunnel. Core beta selection requires supported ARM64 runtime and an imported
secret profile; import never automatically selects or starts the engine.
Existing inert debug canary is not the beta VPN implementation.

## Agreed data-only subscription envelope

One UTF-8 JSON document, bounded to 16 KiB, exactly these fields:

- `schema`: literal `veilark-core-beta-profile-v1`.
- `name`: 1–80 printable characters, public display metadata only.
- `engine`: literal `original-v1`.
- `beta`: boolean `true`.
- `enrollment`: nested strict `veilark-original-enrollment-v1` object,
  exactly the 13 fields defined by the Core exporter. No extra DNS or route fields
  inside enrollment. It contains credentials and must be treated as secret.
- `network`: object with exactly `dns_ipv4` (1–3 distinct unicast literal IPv4
  resolver strings explicitly supplied by the operator) and `lifetime_seconds`
  (integer 10–3595). No implicit DNS selection or hostname resolution.

Core owner approved this envelope. CoreBetaProfile validates it independently,
rejecting unknown/duplicate decoded keys at every level and malformed UTF-8/JSON.
CoreBetaProfileStore provides encrypted atomic import without engine activation.
It is wired before existing subscription compilation for file, pasted JSON and
HTTPS import. Secret input is ephemeral, not SavedState; active Core replacement
requires complete stop. The Core chat owns the
portable enrollment importer and supplies source review/ABI/artifacts. Unknown
schema/engine/fields and malformed data must fail without replacing existing
profiles. Credentials use the existing encrypted private storage, never ordinary
preferences, diagnostics, saved GEO profiles, messages or version control.

## Agreed runtime requirements

Core proposes additive `nativeStartOriginalIpv4BetaV1(tunFd,socketFd,
enrollment32,carrier32,initiator32,responder32,sourceIpv4,lifetimeSeconds)`.
The native context is carrier || initiator || responder. Existing H2 and
original target-only interfaces remain intact. Android protects the carrier
socket before connect and hands a connected NONBLOCK fd to native. Native
duplicates descriptors; precise failure-path ownership still requires review.
Stop is nonblocking; wait for terminal status and release outside the UI thread
before starting another session. Session TTL reconnect uses fresh protected
sockets and bounded backoff; no silent fallback to another engine.

CoreBetaLifecycle provides bounded off-UI stop/terminal/release0 gating, including
terminal-but-BUSY retry and cancellation during start. Registered CoreBetaVpnService
retains masterTUN across fresh protected NONBLOCK carrier/session duplicates and
bounded TTL/error reconnect. Manager reports stopped only after native release and
service destruction. JNI descriptor: `(II[B[B[B[BII)J`, controls `(J)I`.

Canonical artifact: Core source11b9733, ARM64 native SHA256
`4ddbd704d4af2ce18f72cfbdd567f2fad7157b580709fcb0f9ee1761970dc017`,
9,216,456 bytes. Staged only under ignored `build/core-beta-runtime/arm64-v8a`.
Optional private build property `bundleVeilarkCoreBetaRuntime=true` enforces
exact size/hash and no extra staging files. Default/OSS builds do not include
this native library. No private native binary belongs in public Git history.

Implemented Android builder policy: MTU1280, assigned client /32, IPv4 default route,
explicit tunnel DNS, IPv6 contained (never silently allowed to bypass). Runtime
must actually deliver arbitrary allowed IPv4 TCP and UDP traffic. Core beta does
not apply GEO/per-app/DPI scenarios yet; UI explains this, preserving the ready
Russia-direct preset for both existing engines. OS server
forwarding/NAT, network acceptance and credential provisioning are Core-side
blockers, not satisfied by an APK build or a visible beta label.

## Release gates

Signed native artifacts per supported Android ABI, hash/provenance and exact JNI
declarations; lifecycle/FD ownership/cancellation tests; real Android TUN, DNS,
TCP/UDP and reconnect checks; server-side egress acceptance; working secret test
subscription delivered privately. Preserve existing package/signing/OTA pins.
Read live published counter immediately before release; candidate counter must
be higher. Never replace published code70 bytes. GEO scenarios can be prepared
and tested independently of these Core blockers.

## GEO scope correction

The user explicitly clarified that HAPP is only a usability analogy, not a GEO
source or import format. Own Veilark scenarios use name/direct/VPN rules, editable
domains/IP ranges, GitHub JSON inputs, preview, named saved scenarios and explicit
apply. Unsupported rules never silently drop. Save/preview must not affect traffic.
