## `SubscriptionParser.migrateSingBoxForMac` in macos/src/main/kotlin/com/example/veilark/profile/SubscriptionParser.kt (L990-L1071)

**Purpose:** Adapts an already selected/routed sing-box JSON config to macOS DNS, TUN and physical-interface behavior before helper launch (VeilarkSession.kt L841, L436-L440).

**Inputs & Assumptions:** JSON string (L991); optional physical interface, discarded if blank or `utun` (L992). When absent, interface binding/default-interface fields are not set by this function (L1001, L1044, L1049-L1054).

**Outputs & Effects:** Returns transformed JSON string (L1070); no disk/network effect.

**Block-by-Block:**

```kotlin
// L992-L1003
val iface = bindInterface?.takeIf { it.isNotBlank() && !it.startsWith("utun") }
if (server.optString("tag") != "bootstrap-dns") return@repeat
if (server.optString("type") == "local") server.put("type", "udp")
if (server.optString("server").isBlank()) server.put("server", "1.1.1.1")
server.put("detour", "direct")
if (iface != null) server.put("bind_interface", iface)
```
- **What:** Converts tagged bootstrap DNS to direct UDP with fallback server/port and optional NIC binding (L992-L1003).
- **Why here:** DNS bootstrapping must be expressed before the engine reads config (VeilarkSession.kt L436-L440).
- **Assumes:** `direct` outbound exists; no enforcement in this function (L1000, L1039-L1046).
- **Establishes:** Matching bootstrap server has `direct` detour and port 53 if omitted (L997-L1001).
- **Depended on by:** runtime DNS resolution.

```kotlin
// L1004-L1037
if (inbound.optString("type") != "tun") return@repeat
if (existingAddresses.length() == 0) existingAddresses.put("172.19.0.1/30")
inbound.put("stack", "system")
inbound.put("strict_route", false)
excluded += listOf("1.1.1.1/32", "10.0.0.0/8", "172.16.0.0/12", ...)
inbound.put("route_exclude_address", JSONArray(excluded.toList()))
```
- **What:** Preserves nonempty TUN addresses or adds IPv4 default, chooses system stack, and merges route exclusions (L1004-L1037).
- **Why here:** This is the macOS-specific TUN adaptation before launch (L1004-L1007).
- **Assumes:** Engine/host supports any preserved IPv6 address; comment leaves it for runtime check (L1017-L1020).
- **Establishes:** Each TUN inbound has an address, `system` stack, and listed exclusions (L1017-L1036).
- **Depended on by:** engine's TUN startup.

```kotlin
// L1039-L1069
if (outbound.optString("type") == "direct") { outbound.put("connect_timeout", "5s"); if (iface != null) outbound.put("bind_interface", iface) }
if (iface != null) { route.put("default_interface", iface); route.put("auto_detect_interface", false) }
if (!hasSniff) rules.put(JSONObject().put("action", "sniff"))
if (!hasHijack) rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
```
- **What:** Binds direct outbound and default route to physical NIC when known; ensures sniff/DNS-hijack actions (L1039-L1069).
- **Why here:** The comment identifies TUN's takeover of default route as the reason to snapshot NIC (L1048-L1053).
- **Assumes:** Supplied NIC remains valid during runtime; nothing found here beyond the snapshot (L1049-L1054).
- **Establishes:** Existing route rules are retained after newly needed essentials (L1055-L1069).
- **Depended on by:** engine runtime.

**Cross-Function Dependencies:** Caller `prepareSingBox` (VeilarkSession.kt L820-L841); `MacDefaultRouteFingerprintProvider.current` supplies candidate NIC (DefaultRouteFingerprint.kt L21-L37); later helper policy validates config (helper/main.swift L198-L213).

**Open Questions:** How the engine behaves for an absent physical-interface snapshot or stale NIC; needs macOS runtime evidence. Whether an imported config has an untagged DNS server outside this transformation.
