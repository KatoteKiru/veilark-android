# Fairway actual product-service checkpoint — 2026-10-21

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
