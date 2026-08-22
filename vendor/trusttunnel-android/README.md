# TrustTunnel Android adapter provenance

This directory intentionally contains only the reproducibility inputs for the
locally patched Android adapter. The complete upstream repository is not
vendored into Veilark.

## Production baseline

- Upstream repository: `https://github.com/TrustTunnel/TrustTunnelClient.git`
- Tag: `v1.0.49`
- Commit: `be6596652d9722c3109164f505be8e7975a2daa5`
- Patch: `patches/0001-android-per-app-routing.patch`
- Installed AAR: `app/libs/trusttunnel-client.aar`

The patch first preserves two Veilark fixes already present in the verified
baseline AAR but absent from the clean upstream tag:

- Android 14+ starts the service with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`,
  matching Veilark's host manifest;
- an early connection failure stops the foreground service even if the native
  client never reached `Started`.

It then adds Android `VpnService.Builder` application routing immediately
before the TUN is established:

- `all`: disallow only Veilark itself;
- `bypass`: disallow the selected packages and Veilark itself;
- `only`: allow selected packages, excluding Veilark itself;
- unknown modes fall back to `all`;
- removed packages are ignored; `only` fails closed if no selected package is
  still installed;
- allowed and disallowed application APIs are never mixed for one TUN.

Preferences are read from `profile_meta` using `application_mode` and
`selected_applications`. Android only applies these rules when a new TUN is
created, so changing routing while connected requires a controlled tunnel
restart.

## Rebuild

`scripts/build-adapter.ps1` checks out the pinned upstream commit, applies the
tracked patch, runs the adapter unit tests and Kotlin release compilation, and
repackages only `classes.jar` over the verified Veilark baseline AAR:

```powershell
pwsh -File vendor/trusttunnel-android/scripts/build-adapter.ps1 `
  -BaselineAar C:\path\to\veilark-baseline-v1.0.49.aar `
  -OutputAar C:\path\to\trusttunnel-client.aar `
  -WorkDirectory C:\path\to\empty-build-directory
```

The verified Veilark baseline AAR must have SHA-256
`8de1d62df1d2242b527c99ab28d172886326bbdeddcc931caa3d23251d6393b1`.
One auditable source for that exact file is Veilark commit `be7fb66`, path
`app/libs/trusttunnel-client.aar`.

This is an adapter-only rebuild. The stable `arm64-v8a` and `armeabi-v7a` JNI
libraries from the verified Veilark baseline are copied byte-for-byte and
verified individually. No native TrustTunnel source was rebuilt for the
installed artifact. The script rejects unexpected source, class, JNI,
manifest, metadata, asset, or final AAR hashes. It also verifies the compiled
bytecode for both lifecycle fixes and verifies that the host manifest declares
the matching `specialUse` service type and permission.

The failed experimental full native build was not used. Historical Conan
recipes did not resolve reproducibly on the current host, so retaining the
verified stable native payload is lower-risk than publishing an unverified
native rebuild.

See `UPSTREAM.json` for the machine-readable hashes and toolchain.
