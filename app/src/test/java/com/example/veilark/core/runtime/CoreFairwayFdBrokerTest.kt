package com.example.veilark.core.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Event/ownership mocks only; no native or Android socket acceptance. */
class CoreFairwayFdBrokerTest {
  @Test fun rejectsCapacityOutsideBoundedRange() {
    val opener = CoreFairwayFdOpener { _, _, _ -> null }
    for (invalid in listOf(Int.MIN_VALUE, -1, 0, 17, 1000, Int.MAX_VALUE)) {
      assertThrows(IllegalArgumentException::class.java) { CoreFairwayFdBroker(invalid, opener) }
    }
  }

  private fun request(token: Long = 1) = CoreFairwayFdRequest(1, 1, 1, token, 0x0a000001, 1234, 0x0a000002, 443)

  private open class Host : CoreFairwayFdHost {
    val queue = LinkedBlockingQueue<Any>()
    val eof = Any()
    @Volatile var stopped = false
    @Volatile var current = true
    val dequeued = AtomicInteger()
    val accepted = AtomicInteger()
    val failures = LinkedBlockingQueue<Pair<CoreFairwayFdRequest, CoreFairwayFdFailure>>()
    override fun awaitRequest(): CoreFairwayFdRequest? {
      val item = queue.take()
      if (item === eof) return null
      dequeued.incrementAndGet()
      return item as CoreFairwayFdRequest
    }
    override fun isCurrent(request: CoreFairwayFdRequest) = current && !stopped
    override fun network(request: CoreFairwayFdRequest): CoreFairwayNetworkBinding? = null
    override fun deliverBorrowed(request: CoreFairwayFdRequest, fd: Int): Int = synchronized(this) {
      if (!isCurrent(request)) -90 else { accepted.incrementAndGet(); 0 }
    }
    override fun rejected(request: CoreFairwayFdRequest, failure: CoreFairwayFdFailure) {
      failures.add(request to failure)
    }
    override fun stopRequests() { stopped = true; queue.offer(eof) }
  }

  @Test fun blockedAwaitCancellationStopsBeforeJoining() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopObserved = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val exited = AtomicInteger()
    val host = object : Host() {
      override fun awaitRequest(): CoreFairwayFdRequest? {
        entered.complete(Unit)
        check(release.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
        return null
      }
      override fun stopRequests() { super.stopRequests(); stopObserved.complete(Unit) }
    }
    val broker = CoreFairwayFdBroker(opener = CoreFairwayFdOpener { _, _, _ -> fail("No request"); null })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { stopObserved.await() }
      assertFalse(job.isCompleted)
      assertEquals(0, exited.get())
    } finally { release.countDown(); job.join() }
    assertEquals(1, exited.get())
  }

  @Test fun capacityIncludesDequeuedFactoriesAndCancellationDrainsThem() = runBlocking {
    val host = Host()
    repeat(32) { host.queue.add(request(it + 1L)) }
    val entered = CountDownLatch(16)
    val release = CountDownLatch(1)
    val exits = AtomicInteger()
    val broker = CoreFairwayFdBroker(opener = CoreFairwayFdOpener { _, _, active ->
      entered.countDown()
      try {
        check(release.await(5, TimeUnit.SECONDS))
        assertFalse(active())
        CoreFairwayFdFailure(CoreFairwayFdFailureReason.STALE)
      } finally { exits.incrementAndGet() }
    })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      assertEquals(16, host.dequeued.get())
      job.cancel()
      // Workers stay blocked until explicitly released, so return must drain them.
      assertFalse(job.isCompleted)
    } finally { release.countDown(); job.join() }
    assertTrue(host.stopped)
    assertEquals(16, host.dequeued.get())
    assertEquals(16, exits.get())
    assertEquals(0, host.accepted.get())
  }

  @Test fun queuedStaleTicketNeverOpensAndRejectionKeepsFullRequest() = runBlocking {
    val host = Host().apply { current = false }
    val stale = request(9)
    host.queue.add(stale)
    val broker = CoreFairwayFdBroker(opener = CoreFairwayFdOpener { _, _, _ -> fail("Stale open"); null })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      val rejected = checkNotNull(host.failures.poll(3, TimeUnit.SECONDS))
      assertEquals(stale, rejected.first)
      assertEquals(CoreFairwayFdFailureReason.STALE, rejected.second.reason)
    } finally { job.cancel(); job.join() }
  }

  @Test fun lateGenerationCannotBeAcceptedAndNativeFailureIsExplicit() = runBlocking {
    val host = Host()
    val pending = request(11)
    host.queue.add(pending)
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val broker = CoreFairwayFdBroker(opener = CoreFairwayFdOpener { req, owner, _ ->
      entered.complete(Unit)
      check(release.await(5, TimeUnit.SECONDS))
      val result = owner.deliverBorrowed(req, 123)
      if (result == 0) null else CoreFairwayFdFailure(CoreFairwayFdFailureReason.NATIVE_REJECTED, result)
    })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      withTimeout(2000) { entered.await() }
      host.current = false
      release.countDown()
      val rejected = checkNotNull(host.failures.poll(3, TimeUnit.SECONDS))
      assertEquals(pending, rejected.first)
      assertEquals(CoreFairwayFdFailure(CoreFairwayFdFailureReason.NATIVE_REJECTED, -90), rejected.second)
      assertEquals(0, host.accepted.get())
    } finally { release.countDown(); job.cancel(); job.join() }
  }

  @Test fun eofStopsHostWithoutOpeningAndReturns() = runBlocking {
    val host = Host().apply { queue.add(eof) }
    withTimeout(2000) {
      CoreFairwayFdBroker(opener = CoreFairwayFdOpener { _, _, _ -> fail("EOF open"); null }).run(host)
    }
    assertTrue(host.stopped)
  }

  @Test fun unexpectedFactoryFailureStopsBlockedProducerBeforeReturning() = runBlocking {
    val host = Host().apply { queue.add(request()) }
    val failure = IllegalStateException("factory failed")
    try {
      withTimeout(2000) {
        CoreFairwayFdBroker(opener = CoreFairwayFdOpener { _, _, _ -> throw failure }).run(host)
      }
      fail("Factory exception must propagate")
    } catch (caught: IllegalStateException) {
      assertEquals(failure.javaClass, caught.javaClass)
      assertEquals(failure.message, caught.message)
      // Coroutine stacktrace recovery may copy the exception and retain the
      // original as its cause. Require propagation, not wrapper identity.
      assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === failure })
    }
    assertTrue(host.stopped)
    assertEquals(0, host.accepted.get())
  }
}
