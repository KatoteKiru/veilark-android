## `NetworkHealthProbe.check` in macos/src/main/kotlin/com/example/veilark/session/NetworkHealthProbe.kt (L21-L43)

**Purpose:** Provides advisory generic DNS/HTTPS reachability result after tunnel startup and on explicit health checks (VeilarkSession.kt L450-L460, L587-L604).

**Inputs & Assumptions:** Host DNS resolver and three public HTTPS endpoints (L23-L28, L58-L62). External DNS/HTTP behavior is black-box.

**Outputs & Effects:** Returns `NetworkHealth(reachable, detail)` (L29-L43); performs DNS lookup and concurrent HTTPS requests (L23-L28). No session-state mutation itself.

**Block-by-Block:**

```kotlin
// L22-L28
val dnsOk = runCatching { InetAddress.getByName("example.com") }.isSuccess
val successes = coroutineScope { ENDPOINTS.map { endpoint -> async { endpoint to probe(endpoint) } }.awaitAll() }.filter { it.second }
```
- **What:** Checks host name resolution and three web endpoints (L22-L28, L58-L62).
- **Why here:** Separates DNS result from HTTPS success (L29-L40).
- **Assumes:** These probes represent generic connectivity; no Telegram host or media endpoint is tested (L23-L28, L58-L62).
- **Establishes:** Booleans for classification (L23-L28).
- **Depended on by:** returned result below.

```kotlin
// L29-L42
successes.isNotEmpty() && dnsOk -> NetworkHealth(true, ...)
successes.isNotEmpty() -> NetworkHealth(true, ...)
else -> NetworkHealth(false, ...)
```
- **What:** Any HTTPS success yields reachable; DNS alone does not (L29-L42).
- **Why here:** Classification is advisory to session (VeilarkSession.kt L453-L463).
- **Assumes:** HTTP `200..399` counts as success, from `probe` L45-L56.
- **Establishes:** Reachability flag and localized detail (L29-L42).
- **Depended on by:** session logs and status detail (VeilarkSession.kt L450-L467).

**Cross-Function Dependencies:** `probe` (internal) opens URL, disables redirects, sets 5-second connect/read timeouts, returns true for 200–399 and false on exception (L45-L56). Caller `connectLocked` logs degraded health but still marks connected (VeilarkSession.kt L450-L467).

**Open Questions:** Whether the affected user's Telegram media domains resolve and transfer through the tunnel; these probes do not cover them (L58-L62).
