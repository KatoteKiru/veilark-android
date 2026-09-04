# Changelog

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
