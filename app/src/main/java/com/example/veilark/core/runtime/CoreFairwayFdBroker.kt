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

internal interface CoreFairwayFdHost {
  fun awaitRequest(): CoreFairwayFdRequest?
  fun isCurrent(request: CoreFairwayFdRequest): Boolean
  fun network(request: CoreFairwayFdRequest): CoreFairwayNetworkBinding?
  fun deliverBorrowed(request: CoreFairwayFdRequest, fd: Int): Int
  fun rejected(request: CoreFairwayFdRequest, failure: CoreFairwayFdFailure)
  /** Fast, idempotent; invalidates pending requests and wakes awaitRequest. */
  fun stopRequests()
}

internal fun interface CoreFairwayFdOpener {
  /** null means accepted; implementation owns descriptor cleanup and final host fencing. */
  fun deliver(request: CoreFairwayFdRequest, host: CoreFairwayFdHost, isActive: () -> Boolean): CoreFairwayFdFailure?
}

/** Dormant injected broker: no JNI, service activation or periodic idle wakeups. */
internal class CoreFairwayFdBroker(
  private val maxPending: Int = 16,
  private val opener: CoreFairwayFdOpener,
) {
  init { require(maxPending in 1..16) }

  suspend fun run(host: CoreFairwayFdHost): Unit = coroutineScope {
    val stopping = AtomicBoolean(false)
    val slots = Semaphore(maxPending)
    try {
      while (true) {
        // Reserve capacity BEFORE removing any request from the native queue.
        slots.acquire()
        var handedOff = false
        try {
          currentCoroutineContext().ensureActive()
          val request = awaitRequest(host) ?: break
          currentCoroutineContext().ensureActive()
          launch(Dispatchers.IO) {
            try {
              currentCoroutineContext().ensureActive()
              val workerContext = currentCoroutineContext()
              val active = { !stopping.get() && workerContext.isActive }
              val failure = if (!active() || !host.isCurrent(request)) {
                CoreFairwayFdFailure(CoreFairwayFdFailureReason.STALE)
              } else opener.deliver(request, host, active)
              if (failure != null) host.rejected(request, failure)
            } finally { slots.release() }
          }
          handedOff = true
        } finally {
          if (!handedOff) slots.release()
        }
      }
    } finally {
      // This finally executes INSIDE coroutineScope, before it joins IO workers.
      stopping.set(true)
      host.stopRequests()
    }
  }

  private suspend fun awaitRequest(host: CoreFairwayFdHost): CoreFairwayFdRequest? = coroutineScope {
    val waiter = async(Dispatchers.IO) { host.awaitRequest() }
    try {
      waiter.await()
    } catch (failure: Throwable) {
      // Cancellation alone cannot interrupt a blocking JNI/event wait.
      host.stopRequests()
      throw failure
    } finally {
      withContext(NonCancellable) { waiter.join() }
    }
  }
}
