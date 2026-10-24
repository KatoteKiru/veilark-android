# Actual protected enrollment acceptance - 24 October 2026

One real Android API35 x86_64 instrumentation scenario passed on the owned AVD:
actual VIEW intent, fixed-origin HTTPS retrieval, private pinned issuer registry,
selected protected profile resolution, stopped VPN and unchanged engine selection,
delete with revision-floor tombstone, no retained-raw fallback, and restoration
of the same still-valid signed profile. No test trust override or VPN connection.
The synthetic endpoint is RFC5737 and provides no working service credentials.
This is not a fresh-install, phone, ARM64 release or production forwarding result.

Exact ciphertext SHA256:
`f9ef87e930f636a4f94258af79fe52a163837b8a5cbce0f113d625a7a6709f82`, 1750 bytes.
Frozen test-only target APK SHA256:
`7a9c4b2403ffb1eb9b0f0aa9e4dcc53520b98cae8880463983452a0a13e6e9f3`.
Frozen instrumentation APK SHA256:
`9dd5d05ab17145822ed83e82e6a61f4f9834ed83dc78a9f7fb286e84bcff6e57`.
Passing instrumentation log SHA256:
`dcbbb9d86db9709049d02481a7e146d54b0f5653953da1f6092eeb161d7123ff`.

Two earlier failures remain recorded. First, cold emulator startup hit an Android
ANR: the main thread was Runnable with about 1.8s CPU and 26s run-queue wait.
Second, ActivityScenario stopped observing at PRE_ON_CREATE because its library
matcher compares action/data against the Activity Intent, which production
deliberately consumes to remove a bearer. A direct plain launch was RESUMED.
The harness now observes the actual Activity lifecycle by type; it does not retain
or restore the secret Intent or weaken production consumption. The corrected
scenario passed. Closed storage, Intent, UDP and TUN tests were not repeated.

The last 2MiB of access/error logs were checked after acceptance: six constant-URI
protected-enrollment events, zero exact locator/key matches. Only the SHA-matched
synthetic public object was removed. Other subscriptions, private issuer custody
and journal were preserved. The owned emulator was stopped with its disk retained.

Workspace evidence: `reports/enrollment-bootstrap-20261023/https-acceptance.json`,
`privacy-and-cleanup.json`, raw passing log, preserved earlier failure logs and
`installed-candidate.json`. The private bearer is absent from these receipts.
Published OTA83 and the live legacy server remain unchanged.
