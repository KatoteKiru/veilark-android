# Core shared routing integration contract

Status2026-10-12: NOT IMPLEMENTED. User requires Core to honor the same GEO strategies as other engines. Current rawTUN FullIpv4 path bypasses sing-box policy and cannot express domain GEO/DNS. Fix zero-traffic and stop blockers first; do not turn IP exclusions into a claimed full solution.

## Ownership
Native/Core chat owns authenticated packet channel, userspace TCP/IP flow adapter, counters/cancellation and loopback SOCKS5 TCP+UDP-associate runtime. Android/apps chat owns existing sing-box TUN/DNS/rule-set policy, engine lease, protected/direct socket APIs, profile-selected strategy persistence and UI. No native secrets/keys/links in sing-box public config or logs.

## Interface requirements
- Native admits encrypted enrollment from existing secret store and opens only an ephemeral127.0.0.1 SOCKS port with per-session authentication; no public listener. TCP CONNECT and UDP ASSOCIATE both required; IPv4 scope/IPv6 blocking explicit until separately supported.
- Android retains one coordinated engine lease; sing-box owns AndroidTUN and existing ProfileSelection category/DNS policy, Core SOCKS outbound is its tunnel destination. Preserve exact RU preset files/default; Direct/VPN precedence and saved category validation identical.
- Native carrier is protected before connect and bootstrap bypasses local VPN without weakening arbitrary traffic policy. Route does not recurse through its own listener/carrier; loopback interface and resolver source bounded. Do not log local auth or enrollment.
- Stop/revoke cancels proxy flows, channel, workers and closes TUN in bounded time; released only after actual completion. Retain ownership during delayed cleanup; no overlapping replacement/fake stopped state.
- Downloads/catalog browsing are independent of active routing; applying strategy validates all assets and native capabilities before replacing active connection.

## Required acceptance
Shared fixtures must prove RU-direct unchanged, custom geosite+geoip Direct/VPN precedence, DNS matching, TCP HTTPS and UDP DNS through tunnel, selected Direct egress, offline cached selection, unsupported matcher failclosed, reconnect/drop/idle thentraffic, and stop while idle/blockedIO. APK/unit/LinuxTUN evidence and actual phone acceptance are separate. Existing native statusREADY is authenticated admission only, not proven traffic.
