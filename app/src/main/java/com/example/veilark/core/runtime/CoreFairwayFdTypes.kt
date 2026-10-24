package com.example.veilark.core.runtime

import java.io.FileDescriptor
import java.io.IOException

/** One immutable request, not a packet callback. The native engine authorizes the full tuple. */
internal data class CoreFairwayFdRequest(
  val attemptHandle: Long,
  val networkEpoch: Long,
  val policyGeneration: Long,
  val requestToken: Long,
  val sourceIpv4: Int,
  val sourcePort: Int,
  val destinationIpv4: Int,
  val destinationPort: Int,
) {
  init {
    require(attemptHandle > 0 && networkEpoch > 0 && policyGeneration > 0 && requestToken > 0)
    require(sourcePort in 1..65535 && destinationPort in 1..65535)
    require(isUnicast(sourceIpv4) && isUnicast(destinationIpv4))
  }

  companion object {
    private fun isUnicast(address: Int): Boolean =
      (address ushr 24) in 1..223 && (address ushr 24) != 127
  }
}

/** Captured underlying Network for one epoch. Never resolve the default VPN Network here. */
internal interface CoreFairwayNetworkBinding {
  fun isUsable(): Boolean
  @Throws(IOException::class) fun bind(fd: FileDescriptor)
  fun protect(fd: Int): Boolean
  /** Explicit captured-network lookup. Unsupported bindings fail closed. */
  suspend fun resolveIpv4(host: String): List<java.net.Inet4Address> =
    throw IOException("Captured network resolution unavailable")
}

internal enum class CoreFairwayFdFailureReason {
  STALE, NETWORK_UNAVAILABLE, PROTECTION_FAILED, IO_FAILURE, NATIVE_REJECTED,
}

internal data class CoreFairwayFdFailure(val reason: CoreFairwayFdFailureReason, val nativeCode: Int? = null)
