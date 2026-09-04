# TrustTunnel Android adapter provenance

This directory contains the reproducibility inputs for Veilark's locally
patched TrustTunnel Android library. The complete upstream repository is not
vendored into Veilark.

## Source canary baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.1.5`
- Commit: `8886193f90eea5855a0a3c4a735ed0e44a5a4751`
- Patches: `patches/0001-android-per-app-routing.patch` and
  `patches/0002-android-lifecycle-hardening.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`
- Installed AAR SHA-256:
  `069819F12B9F5D94D79569212D002DF27E6FF77E0B9FAC3BC5640BD01979B7CC`

The patches preserve Veilark Android lifecycle requirements:

- Android 14+ starts the service with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`,
  matching Veilark's host manifest;
- an early connection failure stops the foreground service even if the native
  client never reached `Started`, and always reports `DISCONNECTED`;
- native general-mode exclusions can be updated after `CONNECTED`, avoiding a
  large Geo-IP policy during Android TUN creation.

The lifecycle patch additionally makes service admission observable, emits one
terminal `DISCONNECTED` event for every early stop, unregisters and clears the
physical-network collector between sessions, and shuts down the service-owned
executor during destruction. Start/stop and state callbacks carry the lifecycle
attempt ID, so late events from an older native client cannot terminalize a
newer session.

It also applies Android `VpnService.Builder` application routing immediately
before the TUN is established:

- `all`: disallow only Veilark itself;
- `bypass`: disallow selected packages and Veilark itself;
- `only`: allow only selected installed packages;
- unknown modes fall back to `all`;
- removed packages are ignored and an empty `only` set fails closed;
- allowed and disallowed application APIs are never mixed for one TUN.

Preferences are read from `profile_meta` using `application_mode` and
`selected_applications`. Android applies these rules when a new TUN is created,
so changing routing while connected requires a controlled tunnel restart.

## Rebuild

The tracked PowerShell recipe clones the exact upstream commit, exports the
pinned public Conan recipes, applies the patch, builds both Android native ABIs,
runs the upstream adapter unit tests, and verifies the final AAR and payload
hashes:

```powershell
pwsh -File vendor/trusttunnel-android/scripts/build-adapter.ps1 `
  -OutputAar app/libs/trusttunnel-client.aar `
  -WorkDirectory C:\build\trusttunnel-1.1.5
```

The final artifact is a full source build. No native library from the previous
1.1.4 AAR is retained. Exact toolchain, dependency commits, ABI hashes, and
verification results are recorded in `UPSTREAM.json`. Upstream 1.1.5 adds the
Android log-export API, exclusion matching improvements, a new HTTP/3 stack,
and native disconnect/recovery fixes. Veilark intentionally retains its
on-demand physical-network monitor instead of invoking upstream
`VpnService.initialize()`: the upstream initializer starts the monitor for the
whole process lifetime, while Veilark stops it after each terminal VPN session
to avoid idle battery and network-callback cost.

Absolute source/build roots are remapped for Clang and Rust. GNU SHA-1 BuildIds
still drifted while all remaining ELF sections were byte-identical, so the
recipe uses `-Wl,--build-id=none`. Android tombstones therefore require manual
matching by Veilark version and the per-ABI SHA-256 values in `UPSTREAM.json`.
The corresponding canary unstripped libraries are retained outside published
OTA artifacts until physical Android start/stop, application split, and
Wi-Fi/LTE transition acceptance is complete.
