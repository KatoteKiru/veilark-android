## `ProfileSelection.select` in macos/src/main/kotlin/com/example/veilark/profile/ProfileSelection.kt (L54-L77)

**Purpose:** Applies chosen outbound tag to the imported sing-box config before routing-mode transformation (VeilarkSession.kt L820-L824).

**Inputs & Assumptions:** `config` is JSON containing `route`; `tag` is automatic or listed in `nodes` (L54-L59). Node list's correspondence with JSON outbounds is taken on faith here: nothing found in this function beyond the list check at L55-L57.

**Outputs & Effects:** Returns rewritten JSON; no network or disk effects (L58-L76).

**Block-by-Block:**

```kotlin
// L55-L60
require(tag == AUTOMATIC_TAG || nodes.any { it.tag == tag })
val root = JSONObject(config)
val route = root.getJSONObject("route")
route.put("final", tag)
```
- **What:** Checks selection against node list and sets final outbound (L55-L60).
- **Why here:** Subsequent route rewrite reads `route.final` (L79-L90).
- **Assumes:** `AUTOMATIC_TAG` is present as an outbound when selected; nothing found in this function (L55-L60).
- **Establishes:** `route.final` equals requested tag (L60).
- **Depended on by:** `applyRouting` reads that field (L89).

```kotlin
// L61-L75
if (server.optString("tag") == "secure-dns") server.put("detour", tag)
if (rule.has("outbound") && rule.optString("outbound") != "direct") rule.put("outbound", tag)
```
- **What:** Updates secure DNS detour and pre-existing non-direct route-rule outbounds (L61-L75).
- **Why here:** Keeps those references aligned with selected node before mode rules are rebuilt (L79-L150).
- **Assumes:** Other DNS server tags need no detour update; nothing found here (L61-L66).
- **Establishes:** Matching references use the chosen tag (L65, L71-L72).
- **Depended on by:** `applyRouting` and sing-box runtime (VeilarkSession.kt L823-L841).

**Cross-Function Dependencies:** Caller `prepareSingBox` (VeilarkSession.kt L820-L841). `applyRouting` consumes `route.final` (L87-L90). JSON library is external-source-available; malformed structure throws before helper start (L58-L59).

**Open Questions:** How catalog `nodes` is derived for arbitrary imported JSON; inspect subscription compile and catalog decoding.
