# Android private rc41 / 69 — candidate, NOT published

Production remains rc40 / 67 until release gates pass. Counter68 belongs to
the separate Core-lab work and is not reused. This production source is based
on634ddf7; experimental Core code is not included.

Planned change: native TrustTunnel1.1.5 -> stable1.1.7, using official pinned
TrustTunnel/DnsLibs/NativeLibsCommon sources. Preserve all three existing adapter
patches (per-app routing, lifecycle hardening, post-close terminal fence).
sing-box, routing defaults/preferences, subscription storage and UI are unchanged.

Required before publication: final native build with documented recipe environment,
unit tests, JNI/ABI review, AAR payload hashes/provenance, Android private tests/lint,
release build, access-material scan, matching established certificate, signed OTA
manifest, backup, artifact-first publication and full public redownload verification.

Signing preflight: existing rc40 V3 certificate matches the local historical key,
SHA-256 `4c6e3e834ed8d9ae2da0cf8cc177d283a83dabeeb7895655a04e212e1e0a15d3`.
Never replace that identity silently. No physical-device acceptance performed.
Do not state that the single-PC Windows failure is diagnosed from upstream #92.
