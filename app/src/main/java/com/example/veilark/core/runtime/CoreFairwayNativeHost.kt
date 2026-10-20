package com.example.veilark.core.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface CoreFairwayNativeApi {
  fun configure(handle: Long, epoch: Long, generation: Long, networks: IntArray, prefixes: IntArray): Int
  fun awaitRequest(handle: Long, output: LongArray): Int
  fun current(handle: Long, request: LongArray): Int
  fun respond(handle: Long, request: LongArray, fd: Int): Int
  fun reject(handle: Long, request: LongArray, cause: Int): Int
  fun stop(handle: Long): Int
}

/** Exact additive ABI supplied by Core. Does not load or activate it. */
internal object CoreFairwayArgonApi : CoreFairwayNativeApi {
  override fun configure(handle: Long, epoch: Long, generation: Long, networks: IntArray, prefixes: IntArray) =
    VeilarkArgonNative.nativeConfigureFairway(handle, epoch, generation, networks, prefixes)
  override fun awaitRequest(handle: Long, output: LongArray) = VeilarkArgonNative.nativeAwaitFairwayRequest(handle, output)
  override fun current(handle: Long, request: LongArray) = VeilarkArgonNative.nativeFairwayRequestCurrent(handle, request)
  override fun respond(handle: Long, request: LongArray, fd: Int) =
    VeilarkArgonNative.nativeRespondFairwayRequest(handle, request, fd)
  override fun reject(handle: Long, request: LongArray, cause: Int) =
    VeilarkArgonNative.nativeRejectFairwayRequest(handle, request, cause)
  override fun stop(handle: Long) = VeilarkArgonNative.nativeStop(handle)
}

/**
 * Dormant host for one authenticated native attempt and immutable Network lease.
 * Owner must nativeStop BEFORE broker/observer join, then nativeJoin/Release.
 * Network change requires a NEW begin/configure, never engineEpoch reuse or hot
 * native epoch migration. This host neither owns nor releases the native handle.
 * Base ABI1 probe alone is insufficient: verify additive artifact symbols first.
 */
internal class CoreFairwayNativeHost(
  private val api: CoreFairwayNativeApi,
  private val handle: Long,
  private val capture: CoreFairwayCapturedNetwork,
  private val policyGeneration: Long,
  private val epochIsCurrent: (Long) -> Boolean,
) : CoreFairwaySocketHost {
  private val configurationAttempted = AtomicBoolean(false)
  private val configured = AtomicBoolean(false)
  private val stopping = AtomicBoolean(false)
  private val stoppedWith = AtomicReference<Int?>(null)
  val stopResult: Int? get() = stoppedWith.get()

  init { require(handle > 0 && capture.epoch > 0 && policyGeneration > 0) }

  /** Call on the authenticated owner before TUN attach; native enforces phase. */
  fun configure(networks: IntArray, prefixes: IntArray) {
    val addresses = networks.copyOf()
    val lengths = prefixes.copyOf()
    require(addresses.size in 1..16_384 && addresses.size == lengths.size)
    addresses.indices.forEach { index ->
      val prefix = lengths[index]
      require(prefix in 0..32)
      val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
      require((addresses[index] and mask) == addresses[index]) { "Noncanonical Fairway CIDR" }
    }
    check(!stopping.get() && epochIsCurrent(capture.epoch) && capture.binding.isUsable())
    check(configurationAttempted.compareAndSet(false, true)) { "Fairway configuration is one-shot" }
    val code = api.configure(handle, capture.epoch, policyGeneration, addresses, lengths)
    check(code == 0) { "Fairway configure failed ($code)" }
    if (stopping.get() || !epochIsCurrent(capture.epoch) || !capture.binding.isUsable()) {
      stopRequests()
      error("Fairway network changed during configure")
    }
    configured.set(true)
  }

  private fun locallyCurrent() = configured.get() && !stopping.get() &&
    epochIsCurrent(capture.epoch) && capture.binding.isUsable()

  private fun matches(request: CoreFairwaySocketRequest) = request.attemptHandle == handle &&
    request.networkEpoch == capture.epoch && request.policyGeneration == policyGeneration

  override fun awaitRequest(): CoreFairwaySocketRequest? {
    check(configured.get()) { "Fairway host is not configured" }
    while (locallyCurrent()) {
      val output = LongArray(9)
      when (val code = api.awaitRequest(handle, output)) {
        0 -> {
          val request = CoreFairwayRequestCodec.decode(output)
          check(matches(request)) { "Fairway native request scope mismatch" }
          if (isCurrent(request)) return request
          // Expiry after dequeue is a per-flow race, not whole-VPN failure.
        }
        -3 -> return null // Native filters expired queue entries; terminal/unconfigured only.
        else -> error("Fairway await failed ($code)")
      }
    }
    stopRequests()
    return null
  }

  override fun isCurrent(request: CoreFairwaySocketRequest): Boolean =
    matches(request) && locallyCurrent() && api.current(handle, CoreFairwayRequestCodec.encode(request)) == 0 && locallyCurrent()

  override fun network(request: CoreFairwaySocketRequest): CoreFairwayNetworkBinding? =
    if (isCurrent(request)) capture.binding else null

  override fun deliverBorrowed(request: CoreFairwaySocketRequest, fd: Int): Int {
    if (fd < 0) return -2
    if (!isCurrent(request)) return -3
    val code = api.respond(handle, CoreFairwayRequestCodec.encode(request), fd)
    check(code <= 0) { "Invalid Fairway native admission result" }
    if (!locallyCurrent()) {
      stopRequests() // Stop can return before disposal; only owner's nativeJoin proves it.
      return -3
    }
    return code
  }

  override fun rejected(request: CoreFairwaySocketRequest, failure: CoreFairwayFdFailure) {
    if (!matches(request) || !locallyCurrent()) return
    val code = api.reject(handle, CoreFairwayRequestCodec.encode(request), failure.reason.ordinal + 1)
    check(code == 0 || code == -3) { "Fairway reject failed ($code)" }
  }

  override fun stopRequests() {
    if (stopping.compareAndSet(false, true)) {
      // Local invalidation precedes native Stop, which wakes observer and ACK.
      // Retain the result for lifecycle cleanup; never claim Join/Release here.
      stoppedWith.set(api.stop(handle))
    }
  }
}
