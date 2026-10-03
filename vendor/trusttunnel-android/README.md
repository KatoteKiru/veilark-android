# TrustTunnel Android adapter provenance

This directory contains the reproducibility inputs for Veilark's locally
patched TrustTunnel Android library. The complete upstream repository is not
vendored into Veilark.

## Source canary baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.1.7`
- Commit: `170609c24ca865819fed68437b01c013049bc3fa`
- Patches: `patches/0001-android-per-app-routing.patch`,
  `patches/0002-android-lifecycle-hardening.patch`, and
  `patches/0003-post-close-terminal-fence.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`
- Installed AAR SHA-256:
  `37B13174F6FD7193EB9343E82B88D5D5847B98973B79462A680214B84D9CA949`

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

The artifact is built from the complete upstream v1.1.7 source with all three
local patches applied in order. The official v1.1.5-to-v1.1.7 comparison has no
Android-source changes; the update advances the upstream client and pins its
matching `dns-libs` 2.10.2 and `native_libs_common` 8.1.52 dependencies. This
build recompiles both native Android ABIs; no native library is copied from the
previous AAR.

The tracked PowerShell recipe clones the exact upstream commit, exports the
pinned public Conan recipes, checks and applies each patch sequentially,
builds both Android native ABIs, runs the upstream adapter unit tests, and
verifies the final AAR and payload hashes:

```powershell
pwsh -File vendor/trusttunnel-android/scripts/build-adapter.ps1 `
  -OutputAar trusttunnel-client-1.1.7.aar `
  -WorkDirectory C:\build\trusttunnel-1.1.7
```

The final artifact is a full source build. Exact toolchain, dependency commits,
patch hashes, per-ABI hashes, and verification results are recorded in
`UPSTREAM.json`. Two exact-recipe builds from separate work directories
produced byte-identical AARs and payload trees. All 23 upstream Android unit
tests passed. The bytecode gate confirms Android 14+ foreground-service type,
application routing, lifecycle/session fencing, and runtime exclusions. The
class-file comparison against the installed v1.1.5 AAR found all 68 class files
byte-identical; each native ABI retained the same 15 JNI exports. App-level
integration tests, release lint, and physical-device acceptance remain
separate checks and are not claimed here.

Veilark intentionally retains its on-demand physical-network monitor instead of invoking upstream
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
