# Core L3 routing contract — Argon transition

Status2026-10-14: the owner supersedes the TLS proposal with authoritative VEILARK_SPEC.md: own UDP datagram L3,32-byte application framing, no TLS/foreign VPN core. Beta9/code79 remains published and unchanged. Its initial physical Wi-Fi forwarding was followed by PreAuth11 disconnect; sustained and cellular acceptance remain open. Beta10/code80 must implement the new protocol before publication. Previously added inactive TLS-specific signed-profile/parser/admission components are removed from this branch; their Git history is preserved, not activated or reinterpreted as UDP.

Required Beta10 path: Android TUN → own Rust L3/PacketPolicy → authenticated Argon UDP datagrams → own server kernel interface → scoped kernel forwarding/NAT → Internet. Beta9's Original TCP carrier is the retained baseline, not the new transport. Reviewed standard cryptographic libraries remain permitted by the charter. Legacy engines are separate options and do not become Core components.

## Ownership
Vector owns native L3/PacketPolicy, original channel, server kernel networking and deployment. Atlas owns Android VpnService/TUN, per-app filters, protected carrier, bounded session ownership, profile strategy UI and sole Android OTA. Server cutover preserves protected services, host routes and existing client identities. No catch-all NAT rule or guessed eth0 interface.

## Interface requirements
- Core alone owns Android TUN. Native receives the explicitly owned/borrowed TUN and protected carrier according to the existing lifecycle contract; per-attempt scope and native/TUN/job/service release fences remain.
- Preserve assigned client10.203.56.1/32 unless a separately validated migration is required. Coordinate external profile DNS1.1.1.1/77.88.8.8 and remove the obsolete10.0.2.3 stub. Do not expose enrollment secrets or silently mutate arbitrary custom DNS.
- Server kernel routing must support actual UDP/TCP DNS, TLS/HTTPS and relevant Private DNS/captive checks. Verify853 behavior and EarlyEof causality; neither is assumed from a log line alone.
- Russia-direct exclusions use CIDR-complement Android routes or own Rust PacketPolicy. Domain GEO requires a bounded own DNS observer/cache with explicit TTL, eviction, conflict, encrypted-DNS and policy-failure semantics; do not claim IP exclusions implement all domain GEO.
- Keep the ready Russia-direct strategy and legacy engine bytes intact. Saved application selections remain restricted; an empty effective Only list fails closed.
- Download/search and user GitHub GEO selection remain independent of active routing; unsupported Core rule kinds must be identified before replacing a working connection.

Freeze server kernel cutover, rollback and profile DNS compatibility before APK publication. Existing6aad native has no integrated smoltcp/SOCKS runtime; that does not establish phone forwarding. New release must bind exact provider/server/config bytes and actual forwarding evidence.

## Required acceptance
Require actual kernel NAT/TUN native HTTPS, UDP/TCP DNS, Private DNS/captive behavior, direction/TTL/MTU, bounded stop/reconnect and protected-inventory checks. Then verify Internet and immediate cancellation on the physical phone. CI/private or public Linux native receipts remain separate evidence; authenticated READY is not Internet. Beta9's initial Wi-Fi success does not close its subsequent disconnect or cellular failure.

## Beta10 Argon implementation boundary

Android scheduled rotation requires its own timer decision while native is Ready, followed by terminal status3/reason13 captured before successful native release. Any native failure, unsolicited completion or unsuccessful release retains the bounded retry policy. Ready duration alone cannot reset the failure budget; generic timeout12 is not a proven planned lifetime exit.

Vector owns new UDP wire/handshake/server in the private Argon crate. Atlas owns Android protection/TUN/routing and the isolated allocation-free IPv4 SYN MSS ceiling helper. Existing JNI expects the old connected TCP carrier; UDP needs a matched new native ABI/profile contract, not a silent descriptor substitution. TUN uses read/write and UDP alone may use sendmmsg/recvmmsg. Current TUN does not contain a confirmed recvmmsg/ENOTSOCK callsite. Bounded EPOLLET batches retain readiness until EAGAIN, and partial sendmmsg results retain the unsent ciphertext/nonces.

Current released Core supports All/manual IPv4 routes and per-app filters. Official RIPE allocation snapshot20261013 collapses exactly to8662RU prefixes and21710 positive complement routes; exact API33 mixed include/exclude optimization is8337 entries. The requested1550/2100 figures require measured approximation;1%transit-AS exposure is not equivalent to1%IPv4 addresses. No extra Direct range or route cap increase is activated. A protected query to77.88.8.8 does not guarantee a Russian CDN address; encrypted DNS stays outside UDP53 inspection. Preserve these distinctions when enabling the new Russia-direct strategy.

The new specification contains unresolved standard/size conflicts in NoiseIK/STUN and fallback framing. Implement independently testable packet mechanics without inventing cryptography or claiming classification/invisibility. The MSS helper reduces only existing values above1220, validates checksums before mutation and is not a universal PMTUD fix. No OTA80, public endpoint or server/profile/account migration is performed by this transition.
