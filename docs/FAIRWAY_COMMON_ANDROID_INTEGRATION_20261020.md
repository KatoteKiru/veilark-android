# Common Fairway Android service integration — 2026-10-20



Source implemented behind `CoreFairwayIntegration.ENABLED=false`. This document does not approve activation, an owner-ready build or publication. Published81 and local82 remain immutable. Native511/c72 and old isolated lab artifacts are preserved.



For Argon RU mode only, CoreRoutingPlan prepares the complete hash-pinned8662 CIDR policy (45223232 IPv4 addresses), one0/0 system route, no exclusions or lossy subset. Native PacketPolicy receives defensive copies. Existing per-app policy and IPv6 fail-closed builder remain. Legacy Core and all non-RU modes preserve their path. The old partial route is still the active product behavior while the gate is false.



NetworkOwner observes one app-default non-VPN Network. Each attempt captures a ready immutable lease; the outer UDP carrier and direct sockets bind/protect against that lease. NetworkOwner.applyUnderlying also declares that exact captured physical Network on the TUN Builder; it exposes no raw Network to request factories. This declaration alone is not proof of route delivery. A pre-authentication event watcher actively nativeStops on loss/change, including while authentication or the broker is parked. Reconnect requires a new begin/configure and new epoch; there is no hot migration, polling or idle keepalive timer here.



After authentication and before TUN attach, the service configures NativeHost with the complete policy and starts the typed TCP/UDP broker. Native install_fairway creates HostPort during configuration, so the observer can legally park before attach. Legacy RU DNS attachment is mutually exclusive. Existing retained-TUN replacement behavior remains.



CoreArgonLifecycle owns at most two LAZY callback jobs registered before start (network watcher and broker). Every cleanup path, including attach/await helper failure, performs nativeStop, cancellation and join of callback jobs, native waiter drain, nativeJoin, then nativeRelease. Failed native Stop retains ownership. Callback blocks signal Stop only and must never call stopAndRelease themselves. This prevents callbacks/FD adoption outliving a released handle.



Validation:57 targeted JVM tests, zero failures/errors/skips; ossDebug and privateRelease Kotlin compile PASS. New tests cover full real asset, boundaries/gaps/defensive copies/cache/concurrency, single-route full-policy preparation, callback drain before Join/Release, failed-running cleanup and stale-ticket rejection. Independent bounded source review found a shared-helper legacy activation regression; corrected to Argon-only opt-in, rereviewed with no further blocker found in that scope. Logs and machine receipt: reports/fairway-common-abi-20261020/android-integration-check.log and android-service-integration-verification.json (workspace).



Still mandatory: new native foreign-fragment/reclaim fixes, usable TCP throughput, matched private product artifact and actual combined OS-TUN/fullRU/foreign/per-app/IPv6/Stop/reconnect/steady energy acceptance. Source/JVM checks do not replace these. Header/wire correction and authenticated encrypted import remain explicit original-plan requirements. No new UI controls or claims added.



Actual API35 lab diagnosed NETWORK_CHANGE_AVAILABLE stopping native before client packets. `registerDefaultNetworkCallback` can report virtual VPNs and is therefore unsuitable as a physical-only observer. API31+ now uses passive `registerBestMatchingNetworkCallback` with INTERNET+NOT_VPN before availability events. Real physical availability/loss/block/link invalidation and immutable epoch behavior stay enabled. This does not request a radio connection or synthesize validation. Source: https://developer.android.com/reference/android/net/ConnectivityManager#registerBestMatchingNetworkCallback(android.net.NetworkRequest,%20android.net.ConnectivityManager.NetworkCallback,%20android.os.Handler)



API29–30 full common routing is now explicitly rejected before asset/socket/native admission until a separately accepted passive physical selector exists; existing default-gate-off product and legacy modes are unchanged. API29–30 support remains a mandatory open requirement, not a silent partial-route fallback. Targeted tests now58, zero failures/errors/skips, both Kotlin compiles PASS after this change. Real causal OS-TUN retest still required.


Actual scoped acceptance: common-tun-040309041afb passed on owned API35 x86_64 with ARM64 NDK translation using frozen511/c72, the API31 physical observer and real common host/socket factory. Independent unbound/unprotected client UDP and TCP DNS both selected10.0.0.1, received61B valid responses (TCP2 answers), native admittedUDP1/TCP1 and joined cleanup completed.22 host checks. This is a single/32 fixture, not fullRU/Ozon, foreign Internet, newcd37316, product service/new lifecycle, energy, physical/LTE, recovery-duration or OTA acceptance. All earlier failed receipts remain immutable.

Lab startup corrections were confined to the private lab: software drawing for LabActivity after cold EGL stalls, exact installed-APK SHA reuse and45s cold ART/NDK readiness. Native authentication remains6s; client source/DNS/admission/Stop checks stay mandatory. Success source archive44 inputs, hashes and report: reports/fairway-common-abi-20261020/android-common-success-040309041afb-verification.json (workspace). AVD shut down after evidence freeze. Product gate staysfalse.
