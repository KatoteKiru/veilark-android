# sing-box/libbox 1.13.19 canary

This directory describes an isolated Android canary of upstream sing-box. It is
not used by a normal application build and does not replace
`app/libs/libbox.aar`. The large generated AAR is intentionally ignored by Git;
run the tracked build recipe to create it locally before enabling the canary.

## Artifact

- `artifacts/libbox-1.13.19-android-arm-arm64.aar`
- SHA-256: `703729EB73C1CB41A84F11D4603C8C4471487035061D676B058AB76C3D1328BB`
- Size: 44,757,399 bytes
- Android ABIs: `arm64-v8a`, `armeabi-v7a`
- Minimum Android API: 23
- Upstream tag: `v1.13.19`
- Upstream commit: `b5ebaa1fc0f2b94256180b95468e73ef53caa27d`

`UPSTREAM.json` records the complete source, toolchain, artifact and native
library provenance. The build recipe is `scripts/build-canary.ps1`.

## Compatibility evidence

Compared with the production 1.13.14 AAR without changing it:

- `classes.jar` is byte-identical (`95A1A5B...A04CC82`);
- all 90 `io.nekohasekai.libbox` class names match;
- the 879-line `javap -public` API dump has zero differences;
- both ABIs expose the same 499 JNI symbol names;
- native dependencies are unchanged: `liblog`, `libdl`, `libm`, `libandroid`,
  and `libc`;
- arm64 `PT_LOAD` segments are 16 KiB aligned (`0x4000`); armv7 uses its
  expected 4 KiB alignment (`0x1000`);
- the embedded core version is `1.13.19` for both ABIs.

An isolated Veilark copy successfully completed `compileReleaseKotlin` and
`mergeReleaseNativeLibs` with this AAR. The merged native library hashes were
identical to the hashes in `UPSTREAM.json`. Full `assembleRelease` in that
throw-away copy stopped only because production TrustTunnel credential inputs
were intentionally not copied into the isolated workspace.

This is a canary input, not a production approval. Before integration, copy the
AAR into a throw-away branch/worktree, build the signed release, and validate on
a physical arm64 device: cold start, profile import, DNS, TCP/UDP, QUIC,
Wi-Fi/LTE handover, reconnect, split tunnelling, 30-minute soak, and rollback.

The artifact is GPLv3 software. Preserve the license and make the exact
corresponding source available when distributing a binary that contains it.
