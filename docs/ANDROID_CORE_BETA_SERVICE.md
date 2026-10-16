# Core beta Android service contract — 2026-10-12

CoreBetaManager delegates explicit start/stop to the process-wide Android tunnel
owner. An epoch/attempt token gates service attachment and every state callback;
queued starts are invalidated on stop. The service is non-sticky and does not
activate from profile import. Missing private native bundle/non-ARM64 fails closed.

The service retains one master TUN throughout the session and retry backoff.
Per-native-session descriptors are duplicates, closed after JNI start on every
return path. JNI retains its own duplicates. Restart requires stop, terminal
status, and release returning zero. Failed cleanup keeps stopped=false and blocks
another engine start; it is never treated as a successful stop.
The manager publishes stopped only after BOTH native/TUN cleanup and service
onDestroy. This prevents the owner dispatching a new start into an old service
instance whose stopSelf would otherwise terminate the replacement. Either
cleanup-before-destroy or destroy-before-cleanup ordering is supported.

TUN policy: assigned IPv4 /32, IPv4 default route, MTU1280, profile DNS IPv4,
Android29+ unmetered override inheriting underlying network constraints. IPv6
has no address/route/DNS/allowFamily call and remains blocked by Android default.
No allowBypass. Source: Android SDK VpnService.Builder allowFamily documentation:
https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int)

Each connection uses fresh AF_INET SOCK_STREAM, VPN protect before nonblocking
connect, bounded10-second cancellable poll, writable/no-error poll flags and
public SDK getpeername endpoint verification, then JNI. No hidden getsockopt API.
Native authenticated readiness is distinct from real Internet/DNS acceptance.
Status poll250ms; max5 consecutive unsuccessful/short sessions, retries1/2/4/8/16s.
Healthy TTL rotations reset the failure budget. No alternate-engine fallback.
Key/context copies are wiped after start, and exception text never reaches logs.
Source: https://developer.android.com/reference/android/net/VpnService#protect(int)

Validation prepared: CoreBetaLifecycleTest (including raw failure status and
cancellation ownership), CoreBetaReconnectPolicyTest (bounded retry policy).
Gradle runs are serialized by parent/root; this worker deliberately ran none.
Physical Android, native execution and public server forwarding remain separate
acceptance evidence. Existing Russia-direct preset and both existing engines
are not changed by this service implementation.
