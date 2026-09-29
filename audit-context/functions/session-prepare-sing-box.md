## `prepareSingBox` in macos/src/main/kotlin/com/example/veilark/session/VeilarkSession.kt (L820-L842)

**Purpose:** Converts a selected sing-box catalog entry into the macOS runtime config used by `helper.start` (L436-L440).

**Inputs & Assumptions:** Entry holds JSON config, selected tag and node list (L820-L822); mode and manual entries are captured by `selectedStartRequest` (L803-L811); `defaultInterface` comes from route fingerprint (L402-L407). Catalog-entry provenance is outside this function.

**Outputs & Effects:** Returns config string after node selection, route/DNS rule generation, and macOS migration (L822-L841). In RU-direct mode reads geo-rule-set paths through repository (L828-L836).

**Block-by-Block:**

```kotlin
// L822-L827
val selected = ProfileSelection.select(entry.config, entry.selectedNodeTag, entry.nodes)
val routed = ProfileSelection.applyRouting(config = selected, mode = mode,
  directEntries = direct, vpnEntries = vpn,
```
- **What:** Selects a node then constructs mode-specific routing (L822-L827).
- **Why here:** `applyRouting` uses the selected `route.final` (ProfileSelection.kt L54-L60, L87-L90).
- **Assumes:** JSON has `route`; `select` and `applyRouting` use `getJSONObject` and throw otherwise (ProfileSelection.kt L58-L59, L87-L89).
- **Establishes:** Node tag and route/DNS policy are reflected in JSON (ProfileSelection.kt L60-L75, L98-L149).
- **Depended on by:** macOS migration uses the resulting JSON (L841).

```kotlin
// L828-L841
geoRuleSets = if (mode == ProfileSelection.ROUTING_RU_DIRECT) {
  val paths = geoRepository.currentOrBundled()
  require(paths.geoIpSrs.isFile && paths.geoSiteSrs.isFile) { RuntimeMessages.geoFilesMissing }
  ProfileSelection.GeoRuleSets(paths.geoIpSrs.absolutePath, paths.geoSiteSrs.absolutePath)
} else null
return SubscriptionParser.migrateSingBoxForMac(routed, defaultInterface)
```
- **What:** Supplies local rule-set files only in RU-direct mode, then applies macOS TUN/DNS/interface transforms (L828-L841).
- **Why here:** `applyRouting` requires paths in RU-direct mode (ProfileSelection.kt L114-L125).
- **Assumes:** File existence suffices for handoff here; repository validation must establish content validity (GeoRoutingRepository.kt L48-L50, L120-L129).
- **Establishes:** Missing files throw before engine launch (L830-L832).
- **Depended on by:** `helper.start` receives the returned config (L436-L440).

**Cross-Function Dependencies:** `ProfileSelection.select`, `applyRouting`, `SubscriptionParser.migrateSingBoxForMac` (internal, analyzed separately). `geoRepository.currentOrBundled` (internal): returns active or bundled paths (GeoRoutingRepository.kt L48-L50). Caller `selectedStartRequest` (L809-L811).

**Open Questions:** Whether a particular user's imported config contains fields not represented by the generated base config; inspect sanitized config shape or a local fixture, not credentials.
