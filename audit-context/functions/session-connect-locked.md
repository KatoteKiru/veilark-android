## `connectLocked` in macos/src/main/kotlin/com/example/veilark/session/VeilarkSession.kt (L410-L502)

**Purpose:** Orchestrates engine stop, configuration preparation/start, startup checks, health probe and session state transition (L431-L500).

**Inputs & Assumptions:** `reconnecting` and physical route fingerprint (L410-L413); selected engine/profile/routing state (L432-L439); helper interface and health checker (L433-L450). `connect` calls within `exclusiveOperation` (L402-L407).

**Outputs & Effects:** Mutates session status/logs/recovery fields, starts/stops privileged engine, runs network health check (L421-L501). Returns Unit; failure is generally reflected in state rather than rethrown, except cancellation (L469-L501).

**Block-by-Block:**

```kotlin
// L414-L430
if (stopRequested) { ... status = TunnelStatus.DISCONNECTED; return }
status = if (reconnecting) TunnelStatus.RECONNECTING else TunnelStatus.CONNECTING
```
- **What:** Handles prior stop and enters connecting/reconnecting state (L414-L430).
- **Why here:** Prevents preparation after an already requested stop (L414-L420).
- **Assumes:** Stop flag accurately represents user intent; checked again after stop/start delay (L435, L442).
- **Establishes:** Observable transitional state (L423-L430).
- **Depended on by:** subsequent start and failure paths.

```kotlin
// L431-L450
if (!enginePresent()) error(RuntimeMessages.engineMissing)
if (!helper.installed()) error(RuntimeMessages.installHelperFirst)
helper.stop().getOrThrow()
val request = selectedStartRequest(routeFingerprint?.interfaceName)
helper.start(request.kind, request.prepare()).getOrThrow()
if (helper.status() != "connected") error(RuntimeMessages.engineDidNotStart)
if (helper.tunFailed()) error(RuntimeMessages.routesFailed)
if (engine == TunnelEngineKind.SING_BOX && helper.outboundUnresolved()) error(...)
val health = healthChecker.check()
```
- **What:** Establishes helper/engine availability, stops prior engine, starts chosen config, checks process/log markers, then probes network (L431-L450).
- **Why here:** Config is made after stop and before helper start; health runs after reported startup (L434-L450).
- **Assumes:** `status == connected` and absence of known log markers indicate live engine, not end-to-end application traffic; helper status checks PID/path (helper/main.swift L287-L299).
- **Establishes:** Known startup failures are converted to exceptions for cleanup (L443-L449).
- **Depended on by:** state transition below.

```kotlin
// L451-L500
if (!health.reachable) log(RuntimeMessages.healthDegraded(...))
appliedDefaultRoute = routeFingerprint
status = TunnelStatus.CONNECTED
}.onFailure { failure ->
  val cleanup = withContext(NonCancellable + Dispatchers.IO) { helper.stop() }
  ...
  if (failure is CancellationException) throw failure
}
```
- **What:** Records health detail; even an unreachable advisory probe still yields connected status; on exception attempts cleanup and sets degraded/disconnected/failed state (L451-L501).
- **Why here:** Session status models engine startup separately from public endpoint health (L450-L467).
- **Assumes:** Health probe reachability is advisory, as `checkConnectionHealth` comment states (L587-L604).
- **Establishes:** Applied fingerprint and recovery reset on successful start (L461-L463); failed start triggers stop (L469-L500).
- **Depended on by:** UI status and handover reconciliation (L563-L580).

**Cross-Function Dependencies:** `selectedStartRequest` and prepare functions (L436-L439, L803-L861); `PrivilegedHelper.start/status/tunFailed/outboundUnresolved` (PrivilegedHelper.kt L97-L140); `NetworkHealthProbe.check` (NetworkHealthProbe.kt L21-L43). Callers `connect` L402-L407 and handover L563-L580.

**Open Questions:** Whether a failed app-specific media request produces any of the helper's recognized markers; this function does not perform application-specific traffic checks (L443-L460).
