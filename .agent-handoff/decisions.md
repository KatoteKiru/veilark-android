# Decision Log

| Date | Decision | Reason | Evidence |
| --- | --- | --- | --- |
| 2026-09-29 | Keep macOS preview at 1.0.11 until a real Mac build and signed manifest pass | Source/JVM tests cannot produce or validate a DMG on Windows | Live manifest, `macos/docs/OTA.md`, GitHub Actions billing annotation |
| 2026-09-29 | Preserve current Ed25519 OTA key; no silent rotation | Existing installed clients trust the old public key | `macos/gradle.properties`, `macos/docs/OTA.md` |
| 2026-09-29 | Supersede the 1.0.11 hold after owner's explicit public-repository approval and two green native Mac CI runs | A runnable Apple Silicon build and the signed release gates became available | CI `36560617114`, `36560617122`; release `36561167600` |
| 2026-09-29 | Keep 1.0.12 labeled preview, not fully physically accepted | Apple Developer ID/notarization and an affected physical Mac are unavailable | GitHub prerelease `macos-v1.0.12`; `.agent-handoff/risks.md` |
