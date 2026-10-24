package com.example.veilark.core.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

internal enum class CoreFairwaySocketProtocol(val number: Int) { ICMP(1), TCP(6), UDP(17) }

/** Full immutable identity; protocol participates in equality and native fencing. */
internal data class CoreFairwaySocketRequest(
  val attemptHandle: Long,
  val networkEpoch: Long,
  val policyGeneration: Long,
  val flowId: Long,
  val sourceIpv4: Int,
  val sourcePort: Int,
  val destinationIpv4: Int,
  val destinationPort: Int,
  val protocol: CoreFairwaySocketProtocol,
) {
  init {
    if (protocol == CoreFairwaySocketProtocol.ICMP) {
      require(attemptHandle > 0 && networkEpoch > 0 && policyGeneration > 0 && flowId > 0)
      require(CoreFairwayFdRequest.isUnicast(sourceIpv4) && CoreFairwayFdRequest.isUnicast(destinationIpv4))
      // The source field is an echo identifier, including zero, not a port.
      require(sourcePort in 0..65535 && destinationPort == 0)
    } else {
      // Preserve the legacy TCP/UDP bounds exactly.
      CoreFairwayFdRequest(attemptHandle, networkEpoch, policyGeneration, flowId,
        sourceIpv4, sourcePort, destinationIpv4, destinationPort)
    }
  }
}

internal interface CoreFairwaySocketHost {
  /** Blocking IO wait; stopRequests must invalidate identities and wake it. */
  fun awaitRequest(): CoreFairwaySocketRequest?
  fun isCurrent(request: CoreFairwaySocketRequest): Boolean
  fun network(request: CoreFairwaySocketRequest): CoreFairwayNetworkBinding?
  /** Synchronous native dup, full-token recheck, reactor registration and ACK. */
  fun deliverBorrowed(request: CoreFairwaySocketRequest, fd: Int): Int
  fun rejected(request: CoreFairwaySocketRequest, failure: CoreFairwayFdFailure)
  /** Fast/idempotent/nonthrowing; also wakes any blocked admission ACK. */
  fun stopRequests()
}

internal interface CoreFairwaySocketOperation {
  /** IO worker; all original descriptor cleanup finishes before this returns. */
  fun deliver(host: CoreFairwaySocketHost, active: () -> Boolean): CoreFairwayFdFailure?
  /** Nonblocking, idempotent; must wake a pending connect without closing its fd. */
  fun cancel()
}

internal fun interface CoreFairwaySocketOpener {
  /** Construction performs no IO. Registration precedes any socket work. */
  fun operation(request: CoreFairwaySocketRequest): CoreFairwaySocketOperation
}

/** Dormant transport-aware module. No JNI guesses, service activation or timers. */
internal class CoreFairwaySocketBroker(
  private val maxPending: Int = 16,
  private val opener: CoreFairwaySocketOpener,
) {
  init { require(maxPending in 1..16) }

  suspend fun run(host: CoreFairwaySocketHost): Unit = coroutineScope {
    val lock = Any()
    val stopping = AtomicBoolean(false)
    val operations = mutableSetOf<CoreFairwaySocketOperation>()
    val slots = Semaphore(maxPending)
    fun stopAll() {
      val pending = synchronized(lock) {
        if (stopping.getAndSet(true)) return
        operations.toList()
      }
      var failure: Throwable? = null
      fun attempt(action: () -> Unit) {
        try { action() } catch (error: Throwable) {
          val previous = failure
          if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
      }
      attempt { host.stopRequests() }
      // Attempt every cancellation even if a faulty host/operation throws.
      pending.forEach { operation -> attempt { operation.cancel() } }
      failure?.let { throw it }
    }
    try {
      while (true) {
        slots.acquire() // Bound native dequeue, not just concurrent creation.
        var handedOff = false
        try {
          currentCoroutineContext().ensureActive()
          val waiter = async(Dispatchers.IO) { host.awaitRequest() }
          val request = try { waiter.await() }
          catch (failure: Throwable) { stopAll(); throw failure }
          finally { withContext(NonCancellable) { waiter.join() } }
          if (request == null) break
          currentCoroutineContext().ensureActive()
          launch(Dispatchers.IO) {
            var operation: CoreFairwaySocketOperation? = null
            try {
              currentCoroutineContext().ensureActive()
              val worker = currentCoroutineContext()
              val active = { !stopping.get() && worker.isActive }
              if (!active() || !host.isCurrent(request)) {
                if (!stopping.get()) host.rejected(request, CoreFairwayFdFailure(CoreFairwayFdFailureReason.STALE))
                return@launch
              }
              val created = opener.operation(request)
              operation = created
              val registered = synchronized(lock) {
                if (stopping.get()) false else { operations.add(created); true }
              }
              if (!registered) { created.cancel(); return@launch }
              val failure = created.deliver(host, active)
              if (failure != null && !stopping.get()) host.rejected(request, failure)
            } finally {
              synchronized(lock) { operation?.let { operations.remove(it) } }
              slots.release()
            }
          }
          handedOff = true
        } finally { if (!handedOff) slots.release() }
      }
    } finally { stopAll() } // Before coroutineScope joins blocking IO children.
  }
}
