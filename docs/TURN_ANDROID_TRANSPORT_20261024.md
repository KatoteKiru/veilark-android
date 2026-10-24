# Authorized TURN: Android transport boundary

Implemented in `CoreTurnGrant`, `CoreTurnCarrier`, and the captured-network
`CoreFairwayAndroidNetworkBinding`. This is source for the separate relay mode in
the accepted `V2_WHITELIST_REVIEW.md`, not a change to direct L3/Fairway or OTA83.

Provisioning supplies an authorized UDP `turn:` endpoint and short-lived
credentials as byte arrays. The supported subset is canonical IPv4/ASCII DNS,
UDP only, bounded printable ASCII credentials and a bounded monotonic lifetime.
Validation proves syntax/lifetime, not authorization, a provider contract or access.
No provider API, guest token acquisition, disk serialization or logging is added.
The grant owns defensive copies and wipes them on close; temporary callback
copies are wiped even when the callback throws. The provisioning owner must also
dispose of its original buffers.

`prepare` checks attempt/network/grant state, resolves A records through the
captured non-VPN Android Network with cancellation and a five-second timeout,
then opens, binds and protects UDP on that same Network. No process-default DNS
fallback, implicit transport fallback or timer is installed. A socket is not
created while DNS is pending. Cancellation/stale state after opening closes it.

`Prepared.beginBorrowed` composes with the existing synchronous native ownership
transaction: native must duplicate the borrowed FD and credentials before return,
and roll back all copies if its begin fails. The callback receives relay address,
port and remaining grant milliseconds. It may be invoked only once; begin/close
are serialized to prevent FD reuse during duplication. The caller closes Prepared
and owns grant disposal. Neither wrapper reports authenticated tunnel readiness.

Native integration must implement authenticated Allocate, CreatePermission,
ChannelBind, response integrity and nonce handling before DATA readiness; permission,
allocation and channel lifetimes are distinct from grant expiry. It must remove
the outer TURN framing exactly and preserve the inner encrypted Core wire, stop
on network-epoch change, and release allocation/FD/credentials on Stop. Required
TURN refresh traffic must not be mislabeled as zero-idle direct-L3 behavior.
No JNI method, provider endpoint, UI toggle or release is enabled yet.

Verification: four new CoreTurnGrant JVM cases passed; the Android Kotlin test
compilation also covered the captured Network/DnsResolver socket boundary.
Report: `C:/AI-Agent/reports/turn-android-development-20261024.log`.
This is not actual relay allocation, provider, phone, VPN-traffic or battery
acceptance. The later one-shot close/begin ownership guard passed the targeted
Android Kotlin compilation (8 seconds), recorded in
`C:/AI-Agent/reports/turn-android-ownership-compile-20261024.log`.
