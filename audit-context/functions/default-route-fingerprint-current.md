## `MacDefaultRouteFingerprintProvider.current` in macos/src/main/kotlin/com/example/veilark/session/DefaultRouteFingerprint.kt (L21-L37)

**Purpose:** Snapshots physical default route interface for macOS config migration and network handover (L6-L13, VeilarkSession.kt L402-L407, L563-L580).

**Inputs & Assumptions:** Current macOS Dynamic Store and route command output (L28-L37). External `scutil` and `route` processes are black boxes at this boundary (L29-L36).

**Outputs & Effects:** Returns non-`utun` fingerprint or null (L22, L39-L62); invokes read-only OS commands (L29-L36).

**Block-by-Block:**

```kotlin
// L21-L37
override fun current(): DefaultRouteFingerprint? = currentFromScutil() ?: currentFromRoute()
val result = BoundedProcess.run(listOf("scutil"), 3_000,
  "open\nshow State:/Network/Global/IPv4\nquit\n")
val result = BoundedProcess.run(listOf("route", "-n", "get", "default"), 3_000)
```
- **What:** Prefers Dynamic Store; falls back to route command on null or process failure (L22-L37).
- **Why here:** Comment says Dynamic Store retains physical primary interface after TUN owns default route (L24-L27).
- **Assumes:** Dynamic Store's `PrimaryInterface` is usable physical NIC when not `utun`; parser only filters blank/`utun` (L39-L45).
- **Establishes:** Null rather than an invalid `utun` fingerprint on parse failure (L39-L62).
- **Depended on by:** `SubscriptionParser.migrateSingBoxForMac` binding logic (SubscriptionParser.kt L990-L1054).

```kotlin
// L39-L62
return fields["PrimaryInterface"]?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
  ?.let { DefaultRouteFingerprint(it, fields["Router"]) }
return interfaceName?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
  ?.let { DefaultRouteFingerprint(it, gateway) }
```
- **What:** Extracts interface/gateway while excluding `utun` values (L39-L62).
- **Why here:** Data class constructor also rejects blank/`utun` names (L11-L13).
- **Assumes:** Field spelling and output format match regex/key parsing (L40-L42, L51-L57, L64).
- **Establishes:** Returned fingerprint has a nonblank, non-`utun` interface name (L11-L13, L43-L45, L59-L61).
- **Depended on by:** connection and handover comparison (VeilarkSession.kt L402-L407, L563-L580).

**Cross-Function Dependencies:** `BoundedProcess.run` (internal); `parseScutil`, `parse` (internal). Caller `currentDefaultRouteFingerprint` delegates to provider on IO (VeilarkSession.kt L969-L971).

**Open Questions:** What the one affected user's `scutil` and `route` outputs are before/after tunnel startup; no host evidence in source.
