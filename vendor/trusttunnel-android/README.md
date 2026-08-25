# TrustTunnel Android adapter provenance

This directory contains the reproducibility inputs for Veilark's locally
patched TrustTunnel Android library. The complete upstream repository is not
vendored into Veilark.

## Production baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.1.4`
- Commit: `7da863b1b947d22a3131d94dcc7c80b0240b6e97`
- Patch: `patches/0001-android-per-app-routing.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`
- Installed AAR SHA-256:
  `3BC3B2D39915305B8F18AD3D33E805054EB3C21FFBD2B0D554CCD48A08DB40D8`

The patch preserves two Veilark Android lifecycle requirements:

- Android 14+ starts the service with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`,
  matching Veilark's host manifest;
- an early connection failure stops the foreground service even if the native
  client never reached `Started`.

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
