# Changelog

## 1.0.5 development preview

- Bump the privileged-helper protocol to version 6 so existing installations must install the hardened helper.
- Persist engine PIDs atomically and fail closed when a managed root engine cannot be stopped.
- Refuse application quit and OTA replacement when tunnel shutdown fails.
- Reduce background helper polling and rotate the native updater log.
- Bind the signed OTA build to `CFBundleVersion`, allow same-version higher-build updates, and reject deployment over an equal or newer signed live build.
- Describe the current TrustTunnel macOS profile accurately as IPv4-only; IPv6 support remains a physical-Mac release gate.
