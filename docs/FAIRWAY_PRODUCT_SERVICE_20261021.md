# Fairway actual product-service checkpoint — 2026-10-21

## Proven physical-network rotation with immutable outcome — 2026-10-21

Androidsourcee2b13d805cd184d1da5d9d3b75b1af8d783412d0 repairs planned Running network rotation accounting. A per-attempt successful epoch-triggered Stop is required, plus Running, observedterminal4, no callback failure, successful cleanup and immutable nativeoutcome2. Prior genuine failure count is PRESERVED, never reset; planned rotation uses250ms delay. Missing/unknown outcome, pre-Running cancellation, native24/26, join/release/FD failure remain conservative. Old legacy engine policy unchanged.

Optional outcome is read strictly after nativeJoin and beforeRelease, then cached on the exact ticket only afterRelease0. Native7c2d77/SO3a0f092a43af9d5025e4c5f52789b63fb851691b0a1a2ac7f9633179164cfc8d (4993792B/49exports) independently verified with252current/archive inputs. Additive JNI is enabled ONLY for explicitly named labterminal-outcome pin. Normal builds and old47candidates keep ARGON_TERMINAL_OUTCOME_ENABLED=false and never call it. A missing/failed metadata read cannot prevent resource disposal. Earlier17d/33b/c970 and routebackup remain available.

Thirty targeted tests PASS (19lifecycle,4Argonretry,7legacyregressions), zero failures/errors/skips; normalprivateReleaseKotlin compilePASS. Includes Join→outcome→Release order, failed/unknown/missing outcome, stale ticket, preserved errors across12mockrotations, and nativefailure afterStop. Bounded independent review found no additional blocker in this scope. Initial Gradle wrong-task and missing-import failures retained; corrected run android-terminal-outcome-tests-fixed.log PASS. This is not a full application/security audit.

Actual caseaa10fd0e830a PASS: testOnly actual manager/VpnService on ownedAPI35 AVD, physicalWiFi→cellular→WiFi,3nativebegins/2confirmedrotations/0failurebackoffs. Each change creates a new immutable native attempt and TUN after cleanup; it does not hot-rebind a session. Afterward full8662/defaultTUN independent ordinary UID achieved Yandex200/body32768 and Ozon307 with platformTLS verification and asset-matchedRU; same encrypted localTEST-NET UDPecho1/1200/1252. Stop/resourcesreleasedtrue, Coremapped/libboxabsent. Confirmed rotation can only be logged if the real postJoin native accessor returned2 and disposal succeeded.

APK SHA b9deaf3e85864d9874184616d7831bdf193e6d9023f6aed2322366e0c85f44f9 (119597441B), native3a0f, cliente2a51db. Installed/frozenAPK/native/asset hashes and200sourceinputs verified and frozen in workspace product-service-aa10fd0e830a-verification.json / -sources.zip. Runtime runner --native-candidate terminal-outcome --ru-https --network-rotations. Test-only progress collector is cancelled/joined before Stop; AVD WiFi restored then ownedAVD shut down, temporary profiles/peer cleaned. No idle polling added to product.

Scope does not establish physicaloperatorLTE, sixrealhandover/capacity, API29/30, activeenergy, blockedFIFO, publicforeign or establishedserver-reboot recovery. Initialauth501cde remains separate. Vector's isolated LinuxkernelTUN roundtrip6ebe988 is not NAT/WAN; GitHubLinuxCI blocked by account billing/spendlimit perVector, no settings changed. Establishedpeerloss still needs reviewed traffic-triggered authenticated feedback with0idlekeepalive and an explicit versioned contract; legacy401/send-success/silence are not health. No ad hoc control bytes or guessed ICMP target. No OTA/production change. Normal fullFairway gate staysfalse.


## Initial authentication outage and recovery blockers — 2026-10-21

Actual case `product-service-501cde527b2a` PASS, Androidsource5442308bbcff1d56a9108bb4b7f1cac2ea753cbf, nativec970 unchanged. Loopback-only finite Python UDP gate silently dropped first8seconds (5datagrams), retained same endpoint/key, then forwarded4/replied4. Actual service recorded2 CORE_BEGIN and1 reconnect backoff, reachedCORE_RUNNING, full8662/one defaultTUN, publicRU TLSHTTPS Yandex200/body32768+Ozon307, encrypted syntheticUDP1/1200/1252 and actualStop/resourcesreleased. Gate/client/native joined; profiles removed. Canonical verification/source archive in workspace reports/fairway-common-abi-20261020/product-service-501cde527b2a-*; installed/frozen/native/RUasset hashes and198sourceinputs verified. Test counters are ONLY fairwayLab; normal product unchanged. Runner new flag `--initial-auth-outage`; no extra production polling/keepalive.

Firstcase4fe81ff6397a rawaggregateFAIL preserved: actualservice/client/gate recovered but the new lab counters ignored localized Core title prefix and falsely returned0. Exact originalAPK retained by installed hash, failure note archived; counter regex fix committed separately5442308 after initial2eaa8e1. Passing scenario is a NEW case, not edited oldFAIL. First build46s/second13s; not repeated old green suites.

This proves initial authentication retry ONLY, not recovery of an established server session/reboot. Bounded read-only review found two remaining blockers and sent exact evidence toVector:

- AndroidCoreBetaVpnService383–384 explicitly stops native on physical epoch change, yet457 always increments the same5-error lifetime budget. Repeated ordinary network handovers can exhaust it. Do not reset budget onReady or a timer: existing regression tests intentionally prevent infinite native-failure loops. Planned rotation must preserve earlier real failure history.
- Native Completion.wait_terminal prioritizes Stop over ended, including an explicit regression test, so terminal4 can mask earlier worker failure. Afterjoin statusClosed5 is not a failure provenance. Android must not waive errors using epochchange+Stop4 alone. A retained immutable completion/failure outcome (Completion already has AtomicU32 failure) is needed before safely classifying planned handover. Vector owns the additive native/JNI contract and compatibility.
- Established Argon driver has no peer-response/liveness budget on active foreign traffic; successful UDPsend is not peerACK, bad-session packets are discarded. Silent server session loss can keepConnected without foreign delivery. Need traffic-triggered bounded recovery with ZERO idlekeepalive, not a positive reboot claim from initial-auth retry. No unsafe Android workaround applied.

Public foreign KernelL3, active energy/load, forcedFIFO release, original header/encrypted import and remaining recovery gates stayOPEN. No OTA/productionrestart/surface change. User phone remains unavailable; owned AVD shut down after receipt freeze. Previousbackup/candidates/published81/local82 unchanged.


## TCP output-progress candidate on Android — 2026-10-21

Adopted sourceec6bb4a235bb1e40c63dd8434d128495e4e61dbb/SOc9707f6ed07640bb42b0bf119e993851a110a93334d04f7061a047219d024694 (4988384B) as explicit lab-only `-PveilarkFairwayLabNative=tcp-output`. Defaultwindow17d and earlierudp-stall33b remain pinned/selectable with separate staging. Normal release gate remains false. Independently verified all252 current/native archive inputs and SO; bounded review of TCP independent output deadline/Actor stop+join/FIFO preservation found no additional blocker in that scope. This is not a full security audit. Logical slot can still await FIFO drain.

Actual case `product-service-7f721c8d9c1f` PASS with Androidsourcecf688856539d378e5b4666d1e9760bb5ea7a4fe0; APKebddb9760896fbaa5dc49968a8711660eb3c6bd8ff0e2556b6775ed0f8deff5f (119560633B). Compared to prior33b decoded APK, ONLY Core SO changed: Android DEX/resources/manifest identical. Native47-export ABI/wire unchanged per source/artifact checks. Build PASS13s after supplying missing ANDROID_HOME; initial environment-only failure retained in android-product-tcp-output-build.log, passing log has -sdk suffix. No broad old green tests rerun.

Real manager/service/defaultTUN/full8662/independent client UID: Yandex5.255.255.77 HTTP200/TLS1.3/body32768B, Ozon185.73.193.68 HTTP307/TLS1.3/body164B, both asset-matchedRU and source10.0.0.1. Same session exact encrypted synthetic UDP echoes1/1200/1252; real Stop/resourcesreleased. Idle10010ms/5ms processCPU/UIDTX0RX0, translatedARM64 lab only. Library map Corepresent/libboxabsent. Installed/frozen APKs/native/RUasset hashes and197source inputs archived/verified via workspace `scripts/freeze-fairway-product-case-20261021.py --case product-service-7f721c8d9c1f`. Canonical receipt/source archive under reports/fairway-common-abi-20261020/product-service-7f721c8d9c1f-*.

This validates normal Android traffic after adoption, NOT forced Android blocked-FIFO expiry, public foreign forwarding, browser VPN detection, sustained energy/throughput, reboot/reconnect or physical/LTE. Original header/import and recovery gates remain. Previousbackup751/33b/window and published81/local82 untouched. Temporary peer/profiles cleaned; owned AVD shut down after test. No OTA.


## Public RU HTTPS plus encrypted branch — 2026-10-21

Case `product-service-fde37412586a` PASS: unchanged Android751da57 runtime/testOnly APK2ec and native6641368/SO33b; actual manager/service, default OS-TUN for independent ordinary application UID, full8662 native policy, clean Stop/resources release. Yandex77.88.44.55 returned HTTP200/TLS1.3 and32768 body wire bytes in1718ms; Ozon185.73.194.82 returned HTTP307/TLS1.3 and164 body bytes in1641ms. Default platform certificate and HTTPS hostname verification retained. Both destination addresses independently matched the pinned RU asset; local source10.0.0.1. No browser anti-VPN/classification claim: the Ozon redirect was not followed.

In the SAME session, connected ordinary UDP socket received exact random echoes of1,1200,1252 bytes through authenticated encrypted Argon to localhost peer's TEST-NET198.51.100.1:45321.1252+IPv4/UDP28 reaches1280MTU. This proves isolated encrypted-branch interoperability alongside public RU TCP; the peer opens NO forwarding sockets, so public FOREIGN egress remains UNTESTED. Neither timings nor body size certify sustained throughput/battery. Earlier441fe4b616ad RU-only pass retained separately.

Installed/frozen APK hashes, extracted native, packaged RU asset and196 source input hashes verified after execution. New test client SHAe2a51db6781bcd2faf22abdc44cde29b99cf7f9e91391cd3f579dbf1a5372567. Exact archive and canonical receipt in workspace `reports/fairway-common-abi-20261020/product-service-fde37412586a-{sources.zip,verification.json}`; raw host/HTTPS/network/routes retained. Runner `scripts/run-fairway-product-service-20261020.py --native-candidate udp-stall --ru-https` builds no product: modified client is built with `scripts/build-fairway-tun-client-20261019.py`. Source archive includes these scripts/client and pinned Android inputs. No production code changes in this run. Normal product full-policy gate remains false.

Bounded read-only sibling review found no added MSS/checksum/FIN blocker, but confirmed known TCP pending-queue16 suppresses Endpoint timer processing under permanent shared FIFO blockage. Vector owns the fix and was messaged with exact lines; Core checkout was left untouched. Local WSL has no installed distribution, so no KernelL3 foreign forwarding test was claimed. No production restart/surface changes, no OTA. Temporary profiles/peer cleaned by runner; owned AVD shut down afterward.


This is an isolated acceptance package, not an OTA release. Normal private/release and OSS builds keep FAIRWAY_LAB_ENABLED=false. Published81/local82 remain immutable.

## Implementation

- `privateFairwayLab` is separately enabled with `-PveilarkFairwayLab=true`, debuggable, testOnly, applicationId suffix `.fairwaylab`, self-update disabled. Release variants are disabled in that invocation. Old native bundling and legacy canary cannot be combined with this fixture.
- Staging verifies native66a4df7/SO17d375c0b2e3b36457900227c92cee7ceb4b3c9d9ffeb1f0000c1aa9a22bf73e (4981248B) every execution. A separate staging directory rejects extra files; old8077 staging is untouched. It does not accept arbitrary unreviewed libraries.
- Actual manager preflight now uses the same complete Fairway policy as the service for ARGON_V1. The prior preflight still used the old route plan, so an unsupported configuration could pass before handover and fail in the service. Legacy engines retain their existing path; API31 preflight remains enforced.
- API29/30 callback groundwork is implemented: passive physical inventory plus independently verified default hints on one Handler, at most two callback registrations, invalidation before partial rollback and retryable failed unregister custody. Raw availability is not physical-default authority. The pure selector does not rank transports or preserve unverified first-arrival choices. No real API29/30 execution has accepted this wrapper, so the guard is not lifted.
- Libbox is no longer eagerly initialized by Application.onCreate. Application registers only its context; sing-box validation/start initializes its independent runtime once, with cached failure and recursive-entry fencing. Existing locale/setup/options/error reporting remain. Core does not call this setup. This is not removal of the separate sing-box engine.

## Actual runtime evidence

`product-service-d0e9cb49302a` passed through the actual application manager/coordinator/CoreBetaVpnService, real secure profile import, native authentication, complete Fairway configure, sole OS-TUN, typed protected sockets, lifecycle callback drain and Stop/release.

The service itself recorded routing_mode=ru_direct, route_count=1, excluded_count=0, partial_ru=false, direct_ru_ipv4=45223232. Independent ordinary UDP/TCP clients used the VPN source10.0.0.1 and received real DNS replies. The process mapped Veilark Core and did not map libbox.so. Host result confirmed product_full_ru_policy_applied=true and product_resources_released=true. Raw receipts are in workspace reports/fairway-common-abi-20261020/product-service-d0e9cb49302a.*.

The OS route is default IPv4 for only the separate client UID. The traffic fixture is still DNS77.88.8.8, with a localhost synthetic handshake peer. This does not prove Ozon/browser or all RU destinations, public foreign forwarding, physical Android/LTE, sustained throughput/energy, server recovery or release readiness.

## Retained failures and causal changes

- 0c5d0d7a70ce: emulator absent; no application execution.
- 30d6f08a915c: real SIGILL in eagerly loaded ARM64 libbox under NDK translation, before Core; native crash log and original APKd2d1086d48b7441f5516c87a5895315bffc1b3387b1a4af455fa606921efb1c1 frozen. Fixed initialization ownership, not a Core/native failure reclassification.
- 01aad3bba233: application survived startup; strict package preflight rejected hidden non-launcher lab client. Only the separate lab manifest gained an explicit query for that client; production package checks were retained.
- d8256a2ca46e: actual full-policy service and both client paths passed, but host cleanup timed out because NOT_EXPORTED rejected the ADB shell stop broadcast. Lab-only receiver now uses a validated per-run action; no product receiver was exported. This case remains FAIL; it is not native Stop acceptance.

## Validation and remaining acceptance

82 targeted existing/new routing/lifecycle/network/preflight/ABI JVM tests passed with zero failures/errors/skips; normal private release Kotlin compile passed. Separately, four NativeInitializationGate tests passed for deferred initialization, concurrent callers, sticky failure and recursive admission; both normal/private release and fairwayLab compile passed. Source review confirmed all direct config-check callers use the initialized runtime. This is not a fresh whole-suite86 run or physical legacy-engine acceptance.

Build command: `gradlew.bat :app:assemblePrivateFairwayLab -PveilarkFairwayLab=true -PveilarkAbis=arm64-v8a`. Runner is workspace scripts/run-fairway-product-service-20261020.py. It refuses a different AVD, requires testOnly/package/hash guards, creates only temporary localhost profiles, checks actual TUN client source, and retains FAIL receipts. Optional `--observe-idle` measures a 10s lab-process CPU/UID packet sample; it does not certify phone battery life.

Remaining mandatory work includes actual RU/browser and public foreign paths, per-app/IPv6/DNS cases, reconnect/handover/server restart, sustained resource/load/energy acceptance, real API29/30 wrapper validation, and original wire/header plus authenticated encrypted import requirements. No publication follows automatically from this scoped checkpoint.

Latest measured case `product-service-4d76967384f1` also passed: 10002ms idle,3ms processCPU, UIDTX0/RX0, then both genuine client DNS paths and real manager Stop/resources release. Core was mapped and libbox absent. A bounded post-run checker verified actual installed APK/extracted native SO, frozen files/packaged policy and raw route/host/client/idle receipts. Canonical workspace receipt: product-service-4d76967384f1-verification.json. This short ARM-translated lab sample does not clear active throughput/whole-app battery or physical acceptance.


## UDP-stall native adoption and fresh APK packaging

Received Vector source664136801da5155046a883423eccc83b9748bd0b/docs4e273e7, SO33bae0e8a8007605f0e4d814c8d09c61724d876014f4d3c11adfdcafae2704d0 (4984032B). Atlas independently verified250 current/archive native inputs and the artifact hash, and performed bounded read-only review of fixed UDP send/publication deadlines, socket disposal before Closed and runtime failed-grace custody. No extra blocker found in this scope; this is not a full audit. Native tests were21 common scenarios across runs (20PASS plus corrected test-clock fixture targeted1PASS), real localhost port-rebind and ARM64/link checks; do not describe that as a fresh full21 suite.

The new candidate is explicit `-PveilarkFairwayLabNative=udp-stall`; default `window` retains reviewed17d. Staging paths and byte/hash pins are separate, and alternate candidates are lab-only. No arbitrary binary path is accepted. Android DEX/resources/manifest entries are byte-identical to the prior17d acceptance package; only the selected Core library differs.

Actual case product-service-5a18b8c6d9ee passed with33b: full-policy real service/default TUN/TCP+UDP/Stop, no libbox mapping. Its10002ms idle sample measured7msCPU and UIDTX0/RX0. It does not prove actual Android stalled-I/O expiry or full logical-slot capacity. A blocked shared terminal FIFO can still retain the logical worker slot even after the30s socket release; the limitation remains OPEN.

Incremental APK replacement left archive holes:122032241B physical APK versus119388530B total indexed compressed payload. The indexed payload was unchanged by a fresh container; final APK became119558989B, removing2473252B without changing decoded entries. Inspection did NOT confirm retention of the old compressed Core payload; the prior region was predominantly zero-filled. Do not claim leaked credentials from this observation.

Standard Gradle dependencies now prepare a fresh private-release unsigned APK or fairwayLab APK container before package tasks, keeping compilation/resource caches and protected frozen reports. The lab path was exercised; private release task ordering was checked with --dry-run only, without building/publishing a release.

Final fresh package case product-service-620552b1ae80 passed actual service/full-policy/default-route/TCP+UDP/Stop and no-libbox mapping. Final testOnly APK2ec789f3fd360c039ac0d19178d4c6496c5394c6fc6e7a6c6b68f3fec9ba8fdb (119558989B), extracted and packaged SO33b hashes verified against the installed app and frozen copy. Its verification separately links the prior33b idle sample; idle was not remeasured in this final-container run. Canonical workspace receipt is product-service-620552b1ae80-verification.json. No OTA, wire/JNI/server mutation, API29 gate lift or broad release acceptance follows.
