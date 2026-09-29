## `ProfileSelection.applyRouting` in macos/src/main/kotlin/com/example/veilark/profile/ProfileSelection.kt (L79-L151)

**Purpose:** Replaces sing-box route and DNS rules for all, manual, or RU-direct mode (L86-L150).

**Inputs & Assumptions:** `config` must have `route.final` (L87-L89); mode is constrained to three constants (L86); manual entries are parsed by `routingEntries` (L103-L104); RU-direct receives nonblank geo paths (L115-L125). DNS object may be absent (`optJSONObject`, L96).

**Outputs & Effects:** Returns JSON string with rebuilt rules (L149-L150); no filesystem or network access here.

**Block-by-Block:**

```kotlin
// L86-L97
require(mode in setOf(ROUTING_ALL, ROUTING_MANUAL, ROUTING_RU_DIRECT))
val route = root.getJSONObject("route")
val finalOutbound = route.getString("final")
val rules = essentialRules()
route.remove("rule_set")
dns?.remove("rules")
```
- **What:** Validates mode, starts from sniff/DNS-hijack essentials, and clears previous geo/DNS rules (L86-L97, L153-L155).
- **Why here:** Prevents rules from a prior mode persisting into a new mode (L91-L95).
- **Assumes:** A config without `dns` can proceed; no DNS object is synthesized here (L96-L97).
- **Establishes:** Mode-specific rules start from a clean route/DNS rule baseline (L90-L97).
- **Depended on by:** All branches below (L98-L149).

```kotlin
// L98-L113
ROUTING_ALL -> dns?.takeIf { it.optJSONArray("servers") != null }?.put("final", "secure-dns")
ROUTING_MANUAL -> { ... addRule(rules, vpn, finalOutbound); addRule(rules, direct, "direct") }
```
- **What:** Full mode selects secure DNS if servers exist; manual mode requires at least one parsed entry and inserts VPN/direct route rules (L98-L113).
- **Why here:** `finalOutbound` came from node selection (L89; `select` L60).
- **Assumes:** `addRule` can encode the parsed entries; inspect L207-L223.
- **Establishes:** Manual rules are ordered VPN then direct after essential rules (L90, L111-L112).
- **Depended on by:** sing-box config output (L149-L150).

```kotlin
// L114-L149
route.put("rule_set", JSONArray().put(localRuleSet(...)).put(localRuleSet(...)))
rules.put(JSONObject().put("rule_set", ...).put("outbound", "direct"))
dns?.put("final", "secure-dns")
dns?.put("rules", JSONArray().put(JSONObject().put("rule_set", ...).put("server", "bootstrap-dns")))
route.put("rules", rules)
```
- **What:** RU-direct references local geo rule sets, directs matching traffic and RU DNS to bootstrap while retaining secure DNS as final (L114-L149).
- **Why here:** Route and DNS policy are rebuilt together (L92-L95, L133-L145).
- **Assumes:** Rule-set files at supplied paths are valid; this function checks only nonblank paths (L115-L125).
- **Establishes:** Route and DNS rule-set references are declared together (L121-L145).
- **Depended on by:** macOS migration and engine runtime (VeilarkSession.kt L823-L841).

**Cross-Function Dependencies:** `essentialRules` L153-L155; `localRuleSet` L157-L162; `routingEntries` L224-L258; `addRule` L207-L223. Caller `prepareSingBox` passes geo paths only for RU-direct (VeilarkSession.kt L828-L840).

**Open Questions:** Whether imported DNS objects without `servers` can satisfy the runtime's DNS behavior; nothing established here (L96-L100).
