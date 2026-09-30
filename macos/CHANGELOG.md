# Changelog

## Unreleased

- Build the macOS app with Xcode 26 / macOS 26 SDK; the AppKit bridge, helper and updater
  now declare an explicit macOS 12 deployment target, and `LSMinimumSystemVersion` is 12.0.
  CI prints `LC_BUILD_VERSION` for the launcher and every Veilark binary.
- Native window chrome: unified toolbar with connection status and Connect/Disconnect,
  Liquid Glass button bezels on macOS 26, glass sidebar extending under a transparent
  titlebar. macOS 12–15 keep the vibrancy sidebar and standard toolbar buttons.
- The native chrome attaches to the main window by its native handle or exact frame, never
  to the startup window that shares its title.
- Reduce Motion, Reduce Transparency and Increase Contrast now follow System Settings
  live through AppKit; `defaults read` remains only as a fallback.
- Deleting a subscription asks with a native macOS sheet (Compose dialog as fallback).
- The in-app updater detaches the update disk image on every failure path and on
  termination signals, not only on success.
- VPN engines, routing, subscriptions, helper privileges, OTA keys and manifest format are
  unchanged. Physical-Mac visual acceptance is still required.

## 1.0.13 preview

- Update the bundled TrustTunnel client from stable 1.1.5 to stable 1.1.7.
- Keep the existing routing and GEO configuration unchanged.
- The report of incoming Telegram media failing on one Mac still requires a real-device comparison; this update is not claimed as a verified fix for it.

## 1.0.9 preview

- Use the shared monochrome Veilark artwork instead of generic shield symbols.
- Supply a vector-rendered 16–1024 px ICNS for Dock and Finder; menu-bar artwork follows the system theme.
- Refine the neutral light/dark palette and soften sidebar selection transitions.
- Respect Reduce Motion and Reduce Transparency without background animation loops.
- Keep VPN engines, routing, subscriptions, helper and OTA verification unchanged.
- Physical Mac visual and tunnel acceptance remain separate from CI checks.

## 1.0.8 preview

- update the pinned stable sing-box runtime from 1.13.19 to 1.13.21 without
  changing routing or subscription formats;
- update the pinned TrustTunnel runtime from 1.0.49 to stable 1.1.5;

## 1.0.7 preview

- keep tunnel connection independent from a slow or unavailable GEO refresh;
- honour a stop request that arrives while a connection is still starting;
- prefer the managed Veilark GEO mirror and retain upstream as a fallback;
- add the validated Veilark web account entry point and unified monochrome app mark.

## 1.0.6 candidate

- Check the signed OTA channel once at application startup and surface the result in Settings; installation remains an explicit user action.
- Give the candidate a monotonically newer `1.0.6` / `10006` identity so installed `1.0.5` builds can discover it after a future manifest publication.
- Compile the native updater with the same Gatekeeper policy as the JVM verifier and privileged helper.
- Add compatibility tests for published `1.0.2` through `1.0.5` metadata.

## 1.0.5 development preview

- Bump the privileged-helper protocol to version 6 so existing installations must install the hardened helper.
- Persist engine PIDs atomically and fail closed when a managed root engine cannot be stopped.
- Refuse application quit and OTA replacement when tunnel shutdown fails.
- Reduce background helper polling and rotate the native updater log.
- Bind the signed OTA build to `CFBundleVersion`, allow same-version higher-build updates, and reject deployment over an equal or newer signed live build.
- Describe the current TrustTunnel macOS profile accurately as IPv4-only; IPv6 support remains a physical-Mac release gate.
