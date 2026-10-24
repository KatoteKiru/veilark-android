package com.example.veilark.core.runtime

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.net.Inet4Address
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Android ownership side of the TURN carrier, not an allocation or ready tunnel.
 * The native adapter must do Allocate/CreatePermission/ChannelBind and validate
 * their authenticated responses before reporting readiness. No public mode/UI
 * or unmatched JNI entrypoint is enabled by this component.
 */
internal object CoreTurnCarrier {
  suspend fun prepare(grant: CoreTurnGrant, binding: CoreFairwayNetworkBinding,
    current: () -> Boolean, protect: (Int) -> Boolean,
    clock: () -> Long = SystemClock::elapsedRealtime): Prepared {
    suspend fun requireCurrent() {
      currentCoroutineContext().ensureActive()
      check(current() && binding.isUsable()) { "TURN network changed" }
      grant.remainingMillis(clock())
    }
    requireCurrent()
    // A cancelled/blocked lookup owns no FD. Never resolve through the VPN's
    // process-default network or retry over another network implicitly.
    val addresses = withTimeout(5_000) { binding.resolveIpv4(grant.endpoint.host) }
    requireCurrent()
    val peer = addresses.firstOrNull { !it.isAnyLocalAddress && !it.isMulticastAddress &&
      (it.address[0].toInt() and 255) in 1..223 } ?: error("TURN IPv4 relay unavailable")
    val socket = CoreProtectedUdp.open(peer, grant.endpoint.port, protect, binding)
    try {
      requireCurrent()
      return Prepared(socket, peer, grant, clock, binding, current)
    } catch (error: Throwable) {
      socket.close()
      throw error
    }
  }

  internal class Prepared internal constructor(
    private val socket: ParcelFileDescriptor,
    private val peer: Inet4Address,
    private val grant: CoreTurnGrant,
    private val clock: () -> Long,
    private val binding: CoreFairwayNetworkBinding,
    private val current: () -> Boolean,
  ) : AutoCloseable {
    private var closed = false
    private var begun = false
    /** Compose with CoreArgonLifecycle's synchronous begin/ownership transaction.
     * The native callback must duplicate FD and credentials before returning;
     * it cannot claim authenticated Core/TURN readiness merely from this socket.
     */
    @Synchronized fun beginBorrowed(begin: (Int, ByteArray, Int, ByteArray, ByteArray, Long) -> Long): Long {
      check(!closed && !begun) { "TURN socket ownership already consumed" }
      check(current() && binding.isUsable()) { "TURN network changed" }
      return grant.withCredentialBytes(clock()) { username, password, remaining ->
        check(current() && binding.isUsable()) { "TURN network changed" }
        val address = peer.address
        try {
          begun = true
          begin(socket.fd, address, grant.endpoint.port, username, password, remaining)
        }
        finally { address.fill(0) }
      }
    }

    @Synchronized override fun close() {
      if (!closed) {
        closed = true
        socket.close()
      }
    }
    override fun toString(): String = "CoreTurnCarrier.Prepared(redacted)"
  }
}
