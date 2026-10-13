# Core L3 routing contract — Beta9

Status2026-10-13: the previous shared Libbox/SOCKS/smoltcp plan is cancelled by the product owner. Its inactive Android config builder, tests and documentation are removed. Veilark Core must remain an independent L3 implementation under veilark-vlk-charter.md and ADR-0003. No sing-box, SOCKS loopback or borrowed userspace TCP/IP stack runs within a Core session.

Required path: Android TUN → own Rust L3/PacketPolicy → Core Original carrier → own server kernel interface → scoped kernel forwarding/NAT → Internet. Existing reviewed standard cryptographic/carrier libraries remain permitted by the charter. Legacy engines are separate options and do not become Core components.

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
Require actual kernel NAT/TUN native HTTPS, UDP/TCP DNS, Private DNS/captive behavior, direction/TTL/MTU, bounded stop/reconnect and protected-inventory checks. Then verify Internet and immediate cancellation on the physical phone. CI/private or public Linux native receipts are separate evidence; authenticated READY is not Internet. Beta8 remains failed by the user's report. Beta9 is not yet built or published.
