# Veilark for Android

[English](README.md) | [Русский](README.ru.md)

Veilark is an independent Android client for VPN profiles supplied by the user.
It combines two network engines: `sing-box` for compatible proxy protocols and
`TrustTunnel` for `tt://` profiles. This repository contains the app, adapters,
tests, and reproducible build instructions. It does not provide servers, VPN
access, accounts, or subscriptions.

## Features

- Android 10+ (`minSdk 29`), `arm64-v8a` and `armeabi-v7a`;
- English and Russian UI selected from the Android system or per-app language;
- separate sing-box 1.13.19 and TrustTunnel 1.1.4 modes;
- multiple subscriptions and profiles with refresh, selection, and deletion;
- import from a link, clipboard, file, or QR code;
- VLESS, Trojan, Hysteria 2, VMess, Shadowsocks, TUIC, and AnyTLS links;
- a compatible subset of sing-box/Xray JSON and Clash/Mihomo YAML;
- TrustTunnel `tt://` links and lists of those links;
- per-application split tunnelling for both engines;
- offline Russia-direct geographic routing for sing-box;
- manual domain and CIDR rules for sing-box;
- Quick Settings tile, technical log, and on-demand latency checks;
- encrypted profile storage backed by Android Keystore.

Import support is deliberately limited to formats Veilark can validate and
convert safely. Compatibility is not claimed for arbitrary sing-box graphs,
every Shadowsocks plugin, or every panel-specific subscription dialect.

## Public and private builds

| Variant | Application ID | Bundled profiles | Self-update |
|---|---|---:|---:|
| `oss` | `app.veilark.android` | no | no |
| `private` | configured locally | local files only | signed private channel |

Both variants use the same application code. Private addresses, subscriptions,
and update keys live only in an ignored `private.properties` file. The OSS build
also removes the APK install permission and update `FileProvider` from its final
manifest.

## Building the OSS app

JDK 17 and Android SDK API 36 are required.

```bash
./gradlew testOssDebugUnitTest lintOssRelease assembleOssDebug
```

The installable debug APK is written to
`app/build/outputs/apk/oss/debug/app-oss-debug.apk`. To produce a signed release,
provide these four environment variables:

```text
VEILARK_OSS_KEYSTORE
VEILARK_OSS_STORE_PASSWORD
VEILARK_OSS_KEY_ALIAS
VEILARK_OSS_KEY_PASSWORD
```

Then run `./gradlew assembleOssRelease`. Never commit the private key. The full
release procedure is in [`docs/RELEASING.md`](docs/RELEASING.md).
Store-side policy and localisation gates are tracked separately in
[`docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md`](docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md).

## Import verification

JVM tests cover individual URIs, base64 lists, sing-box/Xray JSON,
Clash/Mihomo YAML, and mixed lists containing `tt://`. The instrumentation test
uses the public localhost fixture from TrustTunnelClient and calls the real
native engine. It requires an ARM device:

```bash
./gradlew assembleOssDebugAndroidTest
./gradlew connectedOssDebugAndroidTest
```

A standard x86_64 Android Emulator is not suitable because the bundled native
libraries currently provide ARM ABIs only.

## Security and privacy

The OSS variant contains no VPN account, analytics configuration, advertising
SDK, private subscription, or private update channel. It connects to endpoints
imported by the user and to checks explicitly started by the user. The technical
log redacts sensitive values. QR scanning uses on-device Google ML Kit, whose SDK
may send performance and utilisation metrics as disclosed in the bilingual
[`PRIVACY.md`](PRIVACY.md).

Veilark is a client, not an anonymity guarantee. Privacy and availability still
depend on the imported server and its operator. Report vulnerabilities through
the private process in [`SECURITY.md`](SECURITY.md), never in a public issue.

## Support the project

Veilark is free and open source. If it is useful to you, you can help fund
continued development and device testing:

- [YooMoney](https://yoomoney.ru/fundraise/1JR7FR9V605.260823)
- USDT on TON: `UQA-PCRmPUXwmpNd7Zoys4rRHbz6pA8AZd7EuX54gEk_sBkS`

Use the TON network only for USDT. A transfer through another network may be
lost.

## Licensing and attribution

Veilark is distributed under GPL-3.0-or-later. The APK includes sing-box under
GPL-3.0-or-later and a modified Android adapter from TrustTunnelClient under
Apache-2.0. Exact versions, source commits, patches, build records, and hashes
are tracked under `vendor/` and in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). The full license texts and
third-party notices are also bundled in the app under **About Veilark**.

Veilark is an independent community project. It is not affiliated with or
endorsed by AdGuard, TrustTunnel, SagerNet, or sing-box.
