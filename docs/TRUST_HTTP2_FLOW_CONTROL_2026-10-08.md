# TrustTunnel HTTP/2 send-credit repair

Veilark Android uses upstream TrustTunnelClient v1.1.7, commit
`170609c24ca865819fed68437b01c013049bc3fa`, with pinned local adapter patches.
The original native function returned a stream's remote send window without
limiting it by the shared connection window. A real-nghttp2 memory-only test
exhausted one ordinary 65535-byte connection window while stream credit stayed
positive: the original function advertised983041bytes with connection credit0.

The fourth vendor patch returns the minimum connection/stream credit. It also
notifies blocked readers after connection/stream WINDOW_UPDATE and non-ACK
SETTINGS; limiting credit without these wakeups would stall readers. TCP credit
callbacks skip closed clients/streams and recheck snapshot IDs after callbacks.
UDP credit notification requires a flushed socket buffer. UDP counters are
captured/reset before callbacks so newly written bytes remain pending. Existing
HTTP/3 callers retain the default byte-acknowledgement behavior.

Normal production logical connection IDs come from the VPN instance's monotonic
`upstream_conn_id_generator`; no reset callers were found. The snapshot contract
does not promise safe artificial reincarnation of the same connection ID.

## Verification

- Two separate full-source Android SDK builds produced identical AAR SHA256
  `D4B53999C3898A10A48FF065396094EDECD1E3D675D1BFB35C908CF59345F8AC`.
- Both ARM native ABIs rebuilt; all23upstream Android adapter tests passed.
- All Java/non-native payload members are byte-identical to the previous1.1.7
  AAR; both ABIs retain the same15JNI exports. ARM64 LOAD alignment is16KiB.
- Strict vendor receipt/hash/ABI checker and its seven unit tests passed.
- Local real-nghttp2 source-excerpt tests check connection/stream credit and
  reopening, SETTINGS vs ACK, socket-flush-gated UDP, counter reentry and TCP/UDP
  callback mutation/closure. The pre-guard candidate reproduced a closed-client
  wakeup; the final patch suppresses it.
- Host tests use nghttp2runtime1.68.0 with SDK headers1.56.0. They extract exact
  bodies into minimal used-field scaffolding; they are not full Android runtime
  or physical-device acceptance. Reproduction instructions are in
  `vendor/trusttunnel-android/tests/host-credit-contract/README.md`.

App checks after SDK adoption passed201tests in each private/OSS variant
(0failures/errors,3skips each), and both release lint reports have0errors and
87warnings. A Pixel8Pro Android17 was attached after these checks; compatible
upgrade and physical acceptance are in progress. Android TUN/Telegram transfer,
handover, battery and leak acceptance remain open. Healthy bounded
synthetic server uploads do not establish customer Telegram performance.
The patch corrects the reproduced credit contract; it does not prove the entire
evening incident or every reported slowdown is resolved.
