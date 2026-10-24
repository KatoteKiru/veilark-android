package com.example.veilark.core.runtime

import androidx.annotation.Keep
import com.example.veilark.BuildConfig
import com.example.veilark.profile.CoreArgonHandshakeProfile

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

  /** Old binaries never resolve/call the additive JNI. The feature flag may be
   * enabled only for a separately pinned artifact implementing the full profile.
   * The capability query performs no admission or descriptor operations.
   */
  fun isProfileAbiAvailable(profile: CoreArgonHandshakeProfile): Boolean {
    if (profile == CoreArgonHandshakeProfile.LEGACY) return isAbiAvailable()
    if (!BuildConfig.ARGON_ACTIVE_FEEDBACK_ENABLED || !isAbiAvailable()) return false
    return try {
      nativeProfileAbiVersion() == 2
    } catch (_: UnsatisfiedLinkError) { false }
  }

  /** Veilark Passage needs begin_v3, probed separately so that the profile ABI
   * stays at 2 and older app builds keep their exact check against new libraries.
   * A library without the probe symbol simply has no relay route. The relay version only
   * grows (4 adds the Passage wire profile), so each feature checks its own minimum.
   */
  fun isRelayAbiAvailable(profile: CoreArgonHandshakeProfile = CoreArgonHandshakeProfile.LEGACY): Boolean =
    isProfileAbiAvailable(profile) && try {
      nativeRelayAbiVersion() >= RELAY_ABI_VERSION
    } catch (_: UnsatisfiedLinkError) { false }

  private const val RELAY_ABI_VERSION = 3

  @JvmStatic external fun nativeAbiVersion(): Int
  @JvmStatic external fun nativeBegin(
    borrowedUdpFd: Int, serverIpv4: ByteArray, serverPort: Int, mtu: Int,
    clientStaticPrivate: ByteArray, serverStaticPublic: ByteArray, routingMode: Int,
  ): Long
  /** Additive explicit profile entry; no fallback to legacy after failure. */
  @JvmStatic external fun nativeProfileAbiVersion(): Int
  @JvmStatic external fun nativeBeginV2(
    borrowedUdpFd: Int, serverIpv4: ByteArray, serverPort: Int, mtu: Int,
    clientStaticPrivate: ByteArray, serverStaticPublic: ByteArray, routingMode: Int,
    wireProfile: Int, feedbackVersion: Int,
  ): Long
  @JvmStatic external fun nativeRelayAbiVersion(): Int
  /** Argon through a TURN relay. The UDP descriptor is protected, non-blocking and
   * connected to [relayIpv4]:[relayPort]; native allocates on the relay and runs the
   * handshake to the exit through it. Credentials are printable ASCII that native
   * copies before returning, valid for [grantMillis] more. Authentication is awaited
   * with the v1 or v2 call matching the wire profile, as for a direct handle.
   */
  @JvmStatic external fun nativeBeginV3(
    borrowedUdpFd: Int, exitIpv4: ByteArray, exitPort: Int, mtu: Int,
    clientStaticPrivate: ByteArray, serverStaticPublic: ByteArray, routingMode: Int,
    wireProfile: Int, feedbackVersion: Int, relayIpv4: ByteArray, relayPort: Int,
    username: ByteArray, password: ByteArray, grantMillis: Long,
  ): Long
  /** Authenticated output: IPv4 octets, MTU, wireProfile, feedbackVersion. */
  @JvmStatic external fun nativeAwaitAuthenticatedV2(handle: Long, output: IntArray): Int
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
  /** Additive common Fairway ABI. Requires a matched artifact, not base ABI1 alone.
   * Configure once after Authenticated/before TUN, routingMode1 only; mutually
   * exclusive with nativeAttachRuDns. Epoch/policy immutable for this attempt.
   */
  @JvmStatic external fun nativeConfigureFairway(
    handle: Long, epoch: Long, generation: Long, networks: IntArray, prefixes: IntArray,
  ): Int
  /** Exact9 Longs: attempt,epoch,policy,flowId,srcUInt32,srcPort,dstUInt32,dstPort,protocol6|17.
   * Blocking observer; Stop wakes it. No pinned Java array during native wait.
   */
  @JvmStatic external fun nativeAwaitFairwayRequest(handle: Long, output: LongArray): Int
  @JvmStatic external fun nativeFairwayRequestCurrent(handle: Long, request: LongArray): Int
  /** BORROWED fd; native full-token check, sync dup and reactor-registration ACK. */
  @JvmStatic external fun nativeRespondFairwayRequest(handle: Long, request: LongArray, borrowedFd: Int): Int
  @JvmStatic external fun nativeRejectFairwayRequest(handle: Long, request: LongArray, causePositive: Int): Int
  @JvmStatic external fun nativeStatus(handle: Long): Int
  @JvmStatic external fun nativeStop(handle: Long): Int
  @JvmStatic external fun nativeJoin(handle: Long): Int
  /** Optional outcome extension, only for an explicitly matched native candidate. */
  @JvmStatic external fun nativeTerminalOutcome(handle: Long): Int
  @JvmStatic external fun nativeRelease(handle: Long): Int
}
