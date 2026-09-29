## `startEngine` in macos/helper/main.swift (L198-L285)

**Purpose:** Validates a user-provided config path, stages it as root, and launches the selected engine process with bounded logging (L198-L285).

**Inputs & Assumptions:** Engine name and config path from CLI dispatcher (L301-L307); invoking UID/home from OS (L198-L203); engine binary from fixed installed paths (L122-L130, L201). Config path policy enforces user runtime path, filename, regular owner-owned non-group/world-writable file, and size (L99-L113).

**Outputs & Effects:** Stops prior engine, writes privileged config/PID/log, launches engine, prints `connected` after process and marker checks (L214-L285). Failure terminates helper command (L203-L213, L245-L282).

**Block-by-Block:**

```swift
// L198-L218
let callerUid = getuid()
let engine = realpathOrFail(allowedEngine(name))
let configPath = realpathOrFail(config)
guard configPathAllowed(configPath, callerHome: callerHome, callerUid: callerUid) else { fail(...) }
if name == "sing-box" && !HelperConfigPolicy.acceptsSingBox(configData) { fail(...) }
becomeRoot()
stopEngine()
```
- **What:** Restricts engine/config and validates bytes before escalating and replacing an existing engine (L198-L218).
- **Why here:** Validation precedes root staging (L203-L214).
- **Assumes:** `HelperConfigPolicy.acceptsSingBox` captures supported config shape; inspect HelperConfigPolicy.swift.
- **Establishes:** Only accepted input reaches process launch (L203-L215).
- **Depended on by:** root config staging (L219-L227).

```swift
// L219-L250
try configData.write(to: URL(fileURLWithPath: privilegedConfig), options: .atomic)
try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: privilegedConfig)
let logger = Process(); logger.arguments = ["log-sink"]
let process = Process(); process.arguments = name == "sing-box" ? ["run", "-c", privilegedConfig] : ["-c", privilegedConfig]
try process.run()
```
- **What:** Stages config and launches log sink and engine (L219-L250).
- **Why here:** Engine receives a root-owned staged file, not the user path (L221-L242).
- **Assumes:** Launched engine interprets the config as expected; engine is external binary (L238-L246).
- **Establishes:** Child process started or helper fails (L245-L250).
- **Depended on by:** PID/startup checks (L253-L284).

```swift
// L253-L285
try String(pid).write(toFile: pidFile, atomically: true, encoding: .utf8)
usleep(1_200_000)
if kill(pid, 0) != 0 { failFromEngineLog(...) }
if failures.contains(where: { logText.contains($0) }) { stopEngine(); failFromEngineLog(...) }
print("connected")
```
- **What:** Persists PID, waits, tests liveness and known startup markers, then reports connected (L253-L285).
- **Why here:** Gives immediate startup errors time to surface (L266-L283).
- **Assumes:** An alive process without listed markers is an initialized tunnel; no traffic delivery check here (L266-L284).
- **Establishes:** `connected` is a process/startup assertion (L284), later consumed by Kotlin helper (PrivilegedHelper.kt L115-L140).
- **Depended on by:** `VeilarkSession.connectLocked` status path (VeilarkSession.kt L437-L463).

**Cross-Function Dependencies:** `configPathAllowed` L99-L113; `HelperConfigPolicy.acceptsSingBox` L211-L213; `stopEngine` L157-L196; `failFromEngineLog` L72-L91; `status` L287-L299. Kotlin `PrivilegedHelper.start` creates user config and invokes CLI (PrivilegedHelper.kt L97-L140).

**Open Questions:** What engine stdout contains for the affected user's media requests; diagnostics only exports fixed markers (L58-L70), so this function's success does not answer that.
