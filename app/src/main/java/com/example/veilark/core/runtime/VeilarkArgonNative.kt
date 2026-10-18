package com.example.veilark.core.runtime

import androidx.annotation.Keep

/** Separate matched Argon ABI. Class initialization does not load/start a VPN.
 * Descriptors are BORROWED: native duplicates before begin/attach return.
 * Blocking waits/join belong on worker threads outside ownership mutexes.
 */
@Keep
internal object VeilarkArgonNative {
  const val ABI_VERSION = 1
  const val HANDSHAKING = 0
  const val AUTHENTICATED = 1
  const val ATTACHED = 2
  const val RUNNING = 3
  const val STOPPING = 4
  const val CLOSED = 5
  const val FAILED = 6

  /** A symbol/ABI probe, not authentication or data-plane acceptance. */
  fun isAbiAvailable(): Boolean = VeilarkCoreRuntimeNative.loadCandidate() && try {
    // The additive event symbol is required even though the base ABI is still1.
    // JNI rejects handle0 synchronously before entering Rust or admitting a session.
    nativeAbiVersion() == ABI_VERSION && nativeAwaitTerminal(0L) == -3
  } catch (_: UnsatisfiedLinkError) { false }

  @JvmStatic external fun nativeAbiVersion(): Int
  @JvmStatic external fun nativeBegin(
    borrowedUdpFd: Int, serverIpv4: ByteArray, serverPort: Int, mtu: Int,
    clientStaticPrivate: ByteArray, serverStaticPublic: ByteArray, routingMode: Int,
  ): Long
  /** Output length5: four authenticated IPv4 octets followed by MTU.
   * Check return0 before consuming; no Java array is pinned during the wait.
   */
  @JvmStatic external fun nativeAwaitAuthenticated(handle: Long, output: IntArray): Int
  /** RU-direct only, after authentication and before TUN; sync dup of protected 77.88.8.8:53. */
  @JvmStatic external fun nativeAttachRuDns(handle: Long, borrowedResolverFd: Int): Int
  @JvmStatic external fun nativeAttachTun(handle: Long, borrowedTunFd: Int): Int
  @JvmStatic external fun nativeAwaitRunning(handle: Long): Int
  /** Blocking event wait: Stop=4, worker End=6, negative ABI errors. No timer. */
  @JvmStatic external fun nativeAwaitTerminal(handle: Long): Int
  @JvmStatic external fun nativeStatus(handle: Long): Int
  @JvmStatic external fun nativeStop(handle: Long): Int
  @JvmStatic external fun nativeJoin(handle: Long): Int
  @JvmStatic external fun nativeRelease(handle: Long): Int
}
