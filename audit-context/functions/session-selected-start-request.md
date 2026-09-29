## `selectedStartRequest` in macos/src/main/kotlin/com/example/veilark/session/VeilarkSession.kt (L803-L818)

**Purpose:** Captures current engine, routing mode, manual entries, and selected profile before `connectLocked` prepares configuration on IO (L436-L439, L802-L815).

**Inputs & Assumptions:** `defaultInterface` comes from the physical-route fingerprint (L402-L407). Mutable session fields are read at L804-L807; the selected catalog entry must exist or this function throws at L810/L814. `connect` holds `exclusiveOperation` at L402-L407. Whether another caller can invoke `connectLocked` without that lock is an open question.

**Outputs & Effects:** Returns a `StartRequest` containing engine kind and deferred config producer (L800, L808-L817). No file or network effect here.

**Block-by-Block:**

```kotlin
// L804-L811
val kind = engine
val mode = routingMode
val direct = manualDirectEntries
val vpn = manualVpnEntries
val entry = selectedSingBoxEntry() ?: error(RuntimeMessages.chooseSingBox)
StartRequest(kind) { prepareSingBox(entry, mode, direct, vpn, defaultInterface) }
```
- **What:** Freezes values for the sing-box branch; selection lookup is by stored ID (L760-L761).
- **Why here:** Config preparation is deferred to `runInterruptible(Dispatchers.IO)` (L436-L440).
- **Assumes:** Entry config and selected tag are valid; `prepareSingBox` and `ProfileSelection.select` enforce later checks (L820-L841; ProfileSelection.kt L54-L76).
- **Establishes:** The deferred producer uses the captured entry and route settings, not later field reads (L804-L811).
- **Depended on by:** `connectLocked` passes produced config to helper start (L436-L440).

```kotlin
// L813-L815
val config = selectedTrustEntry()?.config ?: error(RuntimeMessages.chooseTrust)
StartRequest(kind) { prepareTrustTunnel(config, mode, direct, vpn) }
```
- **What:** Captures TrustTunnel config, then defers mode-specific preparation (L844-L861).
- **Why here:** Same preparation boundary as sing-box (L436-L440).
- **Assumes:** Saved config can be prepared; callee checks/rewrites it (L844-L861).
- **Establishes:** Branch and profile snapshot for the later helper call (L813-L815).
- **Depended on by:** `connectLocked` (L436-L440).

**Cross-Function Dependencies:** `selectedSingBoxEntry` / `selectedTrustEntry` (internal): ID lookup only (L760-L764). `prepareSingBox` / `prepareTrustTunnel` (internal): produce final engine input (L820-L861). Caller `connectLocked` expects `StartRequest.prepare` to produce accepted config (L436-L440). Shared state: `engine`, `routingMode`, manual entries, selected IDs (L804-L815).

**Open Questions:** Whether every call path into `connectLocked` is serialized by `operationMutex`; inspect L563-L580 and surrounding callers.
