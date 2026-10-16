# Downloadable GEO categories

The editor downloads the real public MetaCubeX GEO catalog, searches geosite
services and geoip countries, and stores selected tokens in the user's scenario.
The catalog is an explicit refresh action; applying a scenario downloads only its
selected files. Startup uses verified local files without a network dependency.

`GeoCategoryCatalog(filesDir)` exposes `load`, suspend `refresh`, suspend
`prepare(directEntries, vpnEntries)`, `localPaths`, and `trustDirectExclusions`.
Tokens are `geosite:telegram` and `geoip:de`. Custom profiles preserve these tokens
through save/load. Saving a draft does not apply it to the active tunnel.

The source is the official `MetaCubeX/meta-rules-dat` `sing` branch. An explicit
commit reference is resolved first. Subdirectory trees bind each JSON file to its
Git blob hash and size. Downloads use the immutable commit URL, bounded reads,
HTTPS, no redirects, validated JSON rule-set envelope, and atomic fsynced storage.
This verifies consistency with the HTTPS publisher tree; it is not a publisher
signature or an independent authenticity claim. Catalog updates leave prepared
file metadata untouched until all newly selected files validate successfully.
Missing or corrupt selected data fails closed instead of clearing the strategy.

sing-box applies actual local source rule sets, keeps VPN rules before direct
rules, and resolves direct geosite domains through the existing bootstrap DNS.
TrustTunnel supports direct categories made entirely from exact domains, suffix
domains, and IP CIDRs. Unsupported regex, keyword or logical matchers reject the
category with a clear error. TrustTunnel exclusions cannot safely express VPN
category overrides, or arbitrary VPN overrides across a broad direct category;
these combinations explicitly require sing-box. Exact domain and domain suffix
semantics stay distinct during TrustTunnel compilation.

Russia-direct remains the existing ready preset on both engines. Its pinned
JSON/SRS assets and handling are unchanged. Core beta has no GEO routing support;
this catalog never pretends to apply a strategy to Core.

Validation delegated to the root's serial Gradle run: `GeoCategoryCatalogTest`
and `CustomGeoProfileTest`; no concurrent build started by this worker. Read-only
publisher verification on 2026-10-12 enumerated 1917 geosite categories and fetched
the exact resolved-commit `telegram.json` (564 bytes), whose Git blob hash matched
the directory tree. Android UI and device routing acceptance remain separate.
