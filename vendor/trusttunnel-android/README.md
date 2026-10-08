# TrustTunnel Android adapter provenance

This directory contains the reproducibility inputs for Veilark's locally
patched TrustTunnel Android library. The complete upstream repository is not
vendored into Veilark.

## Source canary baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.1.7`
- Commit: `170609c24ca865819fed68437b01c013049bc3fa`
- Patches: `patches/0001-android-per-app-routing.patch`,
  `patches/0002-android-lifecycle-hardening.patch`,
  `patches/0003-post-close-terminal-fence.patch`,
  `patches/0004-http2-flow-control.patch`, and
  `patches/0005-android-metering-inheritance.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`
- Installed AAR SHA-256:
  `FCB2B2E8980E500E461CA973CA22630415380D84163BB2DDCAC113D603BAC52F`

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

The artifact is built from the complete upstream v1.1.7 source with all five
local patches applied in order. The official v1.1.5-to-v1.1.7 comparison has no
Android-source changes; the update advances the upstream client and pins its
matching `dns-libs` 2.10.2 and `native_libs_common` 8.1.52 dependencies. This
clean-build recipe recompiles both native Android ABIs; no native library is
copied from a previous AAR. The current metering update used incremental full
SDK assembly in the two existing pinned source trees, retaining their verified
paired-H2 native outputs. It does not claim a fresh clean native rebuild.

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
historical class-file comparison before the metering update against the
installed v1.1.5 AAR found all 68 class files byte-identical; each native ABI
retained the same 15 JNI exports. The current Java payload changes are described
below. App-level
integration tests, release lint, and physical-device acceptance remain
separate checks and are not claimed here.

The HTTP/2 patch limits available send credit by both connection and stream
windows, and resumes blocked readers after connection/stream WINDOW_UPDATE or
non-ACK SETTINGS. UDP acknowledgements remain gated by socket flush; callbacks
use snapshots and reset counters before calling downstream code. Closed TCP
clients and streams receive no writable notification. The H2-only update had
byte-identical Java and non-native members versus its predecessor. Both native
ABIs retain the same 15 JNI exports. Local real-nghttp2 source-excerpt regressions live in
`tests/host-credit-contract`; they do not establish Android Telegram throughput.

The Android metering patch calls `Builder.setMetered(false)` on API29+ before
TUN establishment. Android documents that this inherits metering from the
underlying networks; it preserves cellular and metered Wi-Fi restrictions rather
than forcing an unmetered network. Without this call target-Q+ VPNs default to
metered. Underlying networks remain unspecified, preserving Android's default
system-network tracking; no routing or native handover policy changes.
Official contract: https://developer.android.com/reference/android/net/VpnService.Builder#setMetered(boolean).

Two independent incremental complete SDK builds (`assembleRelease` and
`testDebugUnitTest`, 23 tests each) produced the current byte-identical AAR.
Only `classes.jar` changed relative to the paired-H2 AAR
`D4B53999C3898A10A48FF065396094EDECD1E3D675D1BFB35C908CF59345F8AC`.
Four VpnService-related class files changed, with identical Java declarations;
all other AAR members, including both native libraries, are byte-identical.
The strict recipe checks the new AAR/classes/content-tree hashes and executes
`tests/check-metering-bytecode.ps1` to require the API29 guard, false argument
before `establish()`, and preserved default underlying-network behavior.
Physical before-fix mismatch was observed on Pixel8Pro/API37: underlying Wi-Fi
NOT_METERED=true, VPN NOT_METERED=false. After-fix physical and app integration
checks remain separate; metering does not establish the cause of Telegram speed.

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
