# Third-party notices

Veilark includes third-party components. This file summarizes the components
that implement its network engines; Gradle dependency metadata remains the
authoritative inventory for ordinary Android libraries.

## sing-box / libbox

- project: <https://github.com/SagerNet/sing-box>
- production tag: `v1.13.21`
- exact commit: `628cb31ffa79cffffd34c2f9cde6cae044e4fc12`
- license: GPL-3.0-or-later
- installed artifact: `app/libs/libbox.aar`
- build recipe and hashes: `scripts/build-libbox-multiabi.ps1` and
  `vendor/sing-box/PRODUCTION.json`

Upstream's license notice additionally states that a derivative may not use the
sing-box application name or imply association without prior consent. Veilark
uses its own name and does not claim such an association.

## TrustTunnelClient

- project: <https://github.com/TrustTunnel/TrustTunnelClient>
- upstream tag: `v1.1.5`
- exact commit: `7da863b1b947d22a3131d94dcc7c80b0240b6e97`
- license: Apache License 2.0
- installed artifact: `app/libs/trusttunnel-client.aar`
- patch, rebuild method and hashes: `vendor/trusttunnel-android/`

The tracked patch changes the Android adapter to apply per-application rules
through `VpnService.Builder` and preserves two Android lifecycle fixes. Both
native ABIs are rebuilt from the pinned upstream source and dependency recipes;
the exact build record and hashes are documented in the provenance file.

## sing-geoip RU rule set

- project: <https://github.com/SagerNet/sing-geoip>
- exact source commit and hashes: `vendor/geoip-ru/UPSTREAM.json`
- license: GPL-3.0-or-later
- packaged derived asset: `app/src/main/assets/rules/geoip-ru.json`

The packaged JSON is deterministically derived from the pinned SRS with the
pinned sing-box tool. Its source hash, derived hash and IPv4/IPv6 counts are
recorded so a rule update cannot silently change the routing dataset.

## Optional downloadable GEO category catalog

- project: <https://github.com/MetaCubeX/meta-rules-dat>
- publisher license: GPL-3.0 (repository `LICENSE`)
- upstream directory: `sing` branch, `geo/geosite/*.json` and `geo/geoip/*.json`
- catalog resolves the exact commit before enumeration; downloaded JSON is
  bound to its Git blob hash and byte size from the immutable tree.
- optional categories are downloaded only on explicit selection and are stored
  separately from the unchanged, pinned Russia-direct packaged assets.
- no HAPP formats or datasets are used.

## Android libraries

### Tink Android

- project: <https://github.com/tink-crypto/tink-java>
- version: `1.23.0` (`com.google.crypto.tink:tink-android`)
- license: Apache License 2.0
- use: pure-Java Ed25519 verification for signed OTA manifests on Android API29+
- artifact and dependency hashes: `gradle/verification-metadata.xml`

The verifier preserves the existing OTA public key and signed payload format.

The application uses AndroidX, Jetpack Compose, CameraX, ZXing core barcode
scanning, SnakeYAML, Kotlin coroutines, RxAndroid, ktoml, SLF4J and
logback-android. Versions are pinned in `gradle/libs.versions.toml` and
`app/build.gradle.kts`; downloaded artifacts are checked by
`gradle/verification-metadata.xml`.
