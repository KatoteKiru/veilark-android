# Decision Log

| Date | Decision | Reason | Evidence |
| --- | --- | --- | --- |
| 2026-09-29 | Keep macOS preview at 1.0.11 until a real Mac build and signed manifest pass | Source/JVM tests cannot produce or validate a DMG on Windows | Live manifest, `macos/docs/OTA.md`, GitHub Actions billing annotation |
| 2026-09-29 | Preserve current Ed25519 OTA key; no silent rotation | Existing installed clients trust the old public key | `macos/gradle.properties`, `macos/docs/OTA.md` |
