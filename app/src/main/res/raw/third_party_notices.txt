# Third-party notices

Veilark includes third-party components. This file summarizes the components
that implement its network engines; Gradle dependency metadata remains the
authoritative inventory for ordinary Android libraries.

## sing-box / libbox

- project: <https://github.com/SagerNet/sing-box>
- production tag: `v1.13.19`
- exact commit: `b5ebaa1fc0f2b94256180b95468e73ef53caa27d`
- license: GPL-3.0-or-later
- installed artifact: `app/libs/libbox.aar`
- build recipe and hashes: `scripts/build-libbox-multiabi.ps1` and
  `vendor/sing-box/PRODUCTION.json`

Upstream's license notice additionally states that a derivative may not use the
sing-box application name or imply association without prior consent. Veilark
uses its own name and does not claim such an association.

## TrustTunnelClient

- project: <https://github.com/TrustTunnel/TrustTunnelClient>
- production tag: `v1.1.4`
- exact commit: `7da863b1b947d22a3131d94dcc7c80b0240b6e97`
- license: Apache License 2.0
- installed artifact: `app/libs/trusttunnel-client.aar`
- patch, rebuild method and hashes: `vendor/trusttunnel-android/`

The tracked patch changes the Android adapter to apply per-application rules
through `VpnService.Builder` and preserves two Android lifecycle fixes. Both
native ABIs are rebuilt from the pinned upstream source and dependency recipes;
the exact build record and hashes are documented in the provenance file.

## Other dependencies

The application uses AndroidX, Jetpack Compose, CameraX, ML Kit barcode
scanning, SnakeYAML, Kotlin coroutines, RxAndroid, ktoml, SLF4J and
logback-android. Versions are pinned in `gradle/libs.versions.toml` and
`app/build.gradle.kts`; downloaded artifacts are checked by
`gradle/verification-metadata.xml`.
