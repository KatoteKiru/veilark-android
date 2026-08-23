# Veilark for macOS

Personal VPN client for Apple Silicon. It is **not** an Android APK port and **not** a Network Extension / App Store VPN.

## What you get

- Compose Desktop UI (RU/EN)
- Import HTTPS subscriptions, clipboard, or files (same parsers as Android)
- sing-box TUN and TrustTunnel TUN (one engine at a time)
- AES-GCM catalogs, key in macOS Keychain
- Privileged helper: first connect asks for the macOS administrator password

## Build

JDK 17+, macOS arm64.

```bash
./scripts/fetch-engines.sh
./gradlew test
./gradlew run
./gradlew packageDmg
```

Unsigned build: Gatekeeper → Right-click → Open.

## Helper

`helper/veilark-helper.swift` may only start the bundled `sing-box` / `trusttunnel_client` with a config under Application Support. It is installed setuid root once.
