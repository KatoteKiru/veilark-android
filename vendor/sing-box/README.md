# sing-box/libbox 1.13.21 production core

This directory records the isolated validation that preceded promotion of
sing-box 1.13.21 to `app/libs/libbox.aar`. The large intermediate AAR is
intentionally ignored by Git; the tracked production build recipe writes the
same core directly to the application library directory.

## Artifact

- `artifacts/libbox-1.13.21-android-arm-arm64.aar`
- SHA-256: `B82148CCE2853BFC3C0796F0C32D057229F4C6B45103BF86B53A59B66BA71EF4`
- Size: 44,757,399 bytes
- Android ABIs: `arm64-v8a`, `armeabi-v7a`
- Minimum Android API: 23
- Upstream tag: `v1.13.21`
- Upstream commit: `628cb31ffa79cffffd34c2f9cde6cae044e4fc12`

`UPSTREAM.json` records the complete source, toolchain, artifact and native
library provenance. The build recipe is `scripts/build-canary.ps1`.

## Compatibility evidence

Compared with the previous production 1.13.14 AAR:

- `classes.jar` is byte-identical (`95A1A5B...A04CC82`);
- all 90 `io.nekohasekai.libbox` class names match;
- the 879-line `javap -public` API dump has zero differences;
- both ABIs expose the same 499 JNI symbol names;
- native dependencies are unchanged: `liblog`, `libdl`, `libm`, `libandroid`,
  and `libc`;
- arm64 `PT_LOAD` segments are 16 KiB aligned (`0x4000`); armv7 uses its
  expected 4 KiB alignment (`0x1000`);
- the embedded core version is `1.13.21` for both ABIs.

An isolated Veilark copy successfully completed `compileReleaseKotlin` and
`mergeReleaseNativeLibs` with this AAR. The merged native library hashes were
identical to the hashes in `UPSTREAM.json`. Full `assembleRelease` in that
throw-away copy stopped only because production TrustTunnel credential inputs
were intentionally not copied into the isolated workspace.

The source/API/ABI gates passed before promotion. Physical-device release
acceptance still covers cold start, profile import, DNS, TCP/UDP, QUIC,
Wi-Fi/LTE handover, reconnect, split tunnelling, 30-minute soak, and rollback.

The artifact is GPLv3 software. Preserve the license and make the exact
corresponding source available when distributing a binary that contains it.
