# TrustTunnel Android adapter provenance

This directory contains the reproducibility inputs for Veilark's locally
patched TrustTunnel Android library. The complete upstream repository is not
vendored into Veilark.

## Production baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.1.4`
- Commit: `7da863b1b947d22a3131d94dcc7c80b0240b6e97`
- Patches: `patches/0001-android-per-app-routing.patch` and
  `patches/0002-android-lifecycle-hardening.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`
- Installed AAR SHA-256:
  `3F442054AF06297C9E6103FACB34508B420C2E28F198CB6EE6958546679B2944`

The patch preserves two Veilark Android lifecycle requirements:

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
  -WorkDirectory C:\build\trusttunnel-1.1.4
```

The final artifact is a full source build. No native library from the previous
1.0.49 AAR is retained. Exact toolchain, dependency commits, ABI hashes, and
verification results are recorded in `UPSTREAM.json`.

Absolute source/build roots are remapped for Clang and Rust. GNU SHA-1 BuildIds
still drifted while all remaining ELF sections were byte-identical, so the
recipe uses `-Wl,--build-id=none`. Android tombstones therefore require manual
matching by Veilark version and the per-ABI SHA-256 values in `UPSTREAM.json`.
The corresponding unstripped libraries are retained in
`build/release/rc19/trusttunnel-client-1.1.4-native-symbols.zip`.
