# Third-party notices

Veilark includes third-party components. This file summarizes the components
that implement its network engines; Gradle dependency metadata remains the
authoritative inventory for ordinary Android libraries.

## sing-box / libbox

- project: <https://github.com/SagerNet/sing-box>
- production tag: `v1.13.14`
- exact commit: `25a600db24f7680ad9806ce5427bd0ab8afe1114`
- license: GPL-3.0-or-later
- installed artifact: `app/libs/libbox.aar`
- build recipe and hashes: `scripts/build-libbox-multiabi.ps1` and
  `vendor/sing-box/PRODUCTION.json`

Upstream's license notice additionally states that a derivative may not use the
sing-box application name or imply association without prior consent. Veilark
uses its own name and does not claim such an association. A newer 1.13.19
artifact is retained as an opt-in engineering canary and is not used by OSS or
private production builds.

## TrustTunnelClient

- project: <https://github.com/TrustTunnel/TrustTunnelClient>
- production tag: `v1.0.49`
- exact commit: `be6596652d9722c3109164f505be8e7975a2daa5`
- license: Apache License 2.0
- installed artifact: `app/libs/trusttunnel-client.aar`
- patch, rebuild method and hashes: `vendor/trusttunnel-android/`

The tracked patch changes the Android adapter to apply per-application rules
through `VpnService.Builder` and preserves two Android lifecycle fixes. The
native libraries in the installed AAR were retained byte-for-byte from the
verified baseline; this limitation is documented in the provenance file.

## Other dependencies

The application uses AndroidX, Jetpack Compose, CameraX, ML Kit barcode
scanning, SnakeYAML, Kotlin coroutines, RxAndroid, ktoml, SLF4J and
logback-android. Versions are pinned in `gradle/libs.versions.toml` and
`app/build.gradle.kts`; downloaded artifacts are checked by
`gradle/verification-metadata.xml`.
