# Local HTTP2 credit contract regression

This test extracts seven unchanged function/case bodies from a patched TrustTunnelClient checkout, then executes them with real nghttp2 sessions in memory. The surrounding scaffold supplies only the fields those bodies use. No sockets, network payloads, project environment files or production databases are accessed. Each session consumes at most one ordinary 65535-byte connection window.

It checks the minimum connection/stream credit, connection and stream WINDOW_UPDATE wakeups, non-ACK SETTINGS wakeups and ACK silence, UDP acknowledgement deferred until socket flush, new output reblocking UDP acknowledgements, reset-before-callback counters, nested-credit/new-byte retention, TCP erase/add/rebind/close safety, closed-client/closed-stream suppression, and UDP stream-close/erase/add safety. Newly opened logical connections use distinct IDs; the test does not invent same-ID reincarnation.

These are **source-excerpt contract regressions**, not full SDK integration or physical Android/Telegram transfer acceptance. The host nghttp2 runtime and SDK header versions are reported independently. Compiler builds and Android acceptance remain separate checks.

## Run on Windows

Requires Python, MSVC BuildTools with `vcvars64.bat`, existing x64 nghttp2 DLL, and nghttp2 headers. No binaries are checked in or automatically downloaded. The recipe generates a minimal import library from the exact API exports used by the test, aliases `ssize_t` to signed 64-bit for the Windows C ABI, and uses a fresh output subdirectory per source hash/run.

```powershell
python .\run_host.py --source "$SourceCheckout" --headers "$Nghttp2Headers" --nghttp2-dll "$Nghttp2Dll" --vcvars "$VcVars64" --output "$HostOutput" --expect-dll-sha256 "$RuntimeSha256"
```

Choose the output directory outside tracked source. The generated C++, import-library `.def` recipe, compile/run log and `result.json` remain there. `result.json` records source excerpt hashes, DLL/header hashes and actual runtime/header versions. Any failed assertion or closed-client wakeup fails the run. The extractor targets the pinned candidate's known brace-balanced source bodies; it is not a general C++ parser.

## Recorded check, 2026-10-08

MSVC BuildTools2022, existing Git nghttp2 DLL 1.68.0 (SHA256 `de07491c946e10a16d23d1e1f9aad872f6db8d58934cc658ca17f69e441b79fd`), SDK Conan headers 1.56.0. All final assertions passed, including `tcp_client_closed_blocked=true`. The pre-guard candidate returned false for this check; its evidence was retained separately.

Final core credit excerpt SHA256: `2ffa0d8170e1ee57c8929e9ebae520c78e9562a201751caf7fdf127d1091c0d4`. Generated complete companion source SHA256: `b93eeb157c4d638c79f55fa257345dfeca7a757f859bee03a69697118307d8fd` (UTF-8 text with normalized line endings). See `recorded-result.json`, including the integrated non-ACK SETTINGS→UDP wake assertion.
