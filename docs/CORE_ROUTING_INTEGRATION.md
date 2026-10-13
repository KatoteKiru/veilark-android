# Core shared routing integration contract

Status2026-10-12: NOT IMPLEMENTED. User requires Core to honor the same GEO strategies as other engines. Current rawTUN FullIpv4 path bypasses sing-box policy and cannot express domain GEO/DNS. Fix zero-traffic and stop blockers first; do not turn IP exclusions into a claimed full solution.

Update2026-10-13: the exact shared task is app → own Core Original → own server → Internet, plus existing TCP/UDP, DNS/GEO, user GitHub GEO and simple server installation. No additional chain of nodes was specified. The independent private Linux native HTTPS/UDP+TCP DNS gate passed; physical Android remains failed after the latest user report. Beta7 fixes service cancellation/restart, and source ddd5cd7 fixes missing selected packages; neither certifies the route. Vector owns actual Core stack/server/native API, Atlas owns Android policy/lease/OTA. The bounded smoltcp stack is a component, not an available native SOCKS capability.

## Ownership
Native/Core chat owns authenticated packet channel, userspace TCP/IP flow adapter, counters/cancellation and loopback SOCKS5 TCP+UDP-associate runtime. Android/apps chat owns existing sing-box TUN/DNS/rule-set policy, engine lease, protected/direct socket APIs, profile-selected strategy persistence and UI. No native secrets/keys/links in sing-box public config or logs.

## Interface requirements
- Native admits encrypted enrollment from existing secret store and opens only an ephemeral127.0.0.1 SOCKS port with per-session authentication; no public listener. TCP CONNECT and UDP ASSOCIATE both required; IPv4 scope/IPv6 blocking explicit until separately supported.
- Android retains one coordinated engine lease; sing-box owns AndroidTUN and existing ProfileSelection category/DNS policy, Core SOCKS outbound is its tunnel destination. Preserve exact RU preset files/default; Direct/VPN precedence and saved category validation identical.
- Native carrier is protected before connect and bootstrap bypasses local VPN without weakening arbitrary traffic policy. Route does not recurse through its own listener/carrier; loopback interface and resolver source bounded. Do not log local auth or enrollment.
- Stop/revoke cancels proxy flows, channel, workers and closes TUN in bounded time; released only after actual completion. Retain ownership during delayed cleanup; no overlapping replacement/fake stopped state.
- Downloads/catalog browsing are independent of active routing; applying strategy validates all assets and native capabilities before replacing active connection.

Before service wiring, freeze the additive native/JNI API and its ownership rules. The proposed seam borrows an already protected nonblocking connected carrier FD and an Android-bound IPv4 loopback listener FD; native duplicates these and independent authentication into its session owner. Borrowed descriptors close after successful ownership transfer or failed startup. Native never accepts an Android TUN FD in shared-flow mode. Libbox alone owns that TUN. Stop must also release the Libbox worker/listener/associations before admitting a new engine lease; the raw Core session fence cannot stand in for these additional resources. Capability remains unavailable until real native TCP/UDP runtime and actual Libbox schema gates pass. Do not activate the prepared config based on parser/stack tests.

## Required acceptance
Shared fixtures must prove RU-direct unchanged, custom geosite+geoip Direct/VPN precedence, DNS matching, TCP HTTPS and UDP DNS through tunnel, selected Direct egress, offline cached selection, unsupported matcher failclosed, reconnect/drop/idle thentraffic, and stop while idle/blockedIO. APK/unit/LinuxTUN evidence and actual phone acceptance are separate. Existing native statusREADY is authenticated admission only, not proven traffic.
