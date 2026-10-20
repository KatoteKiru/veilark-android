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

/** Control/ownership tests only, not native or Android socket acceptance. */
class CoreFairwaySocketBrokerTest {
  private fun request(id: Long = 1, protocol: CoreFairwaySocketProtocol = CoreFairwaySocketProtocol.TCP) =
    CoreFairwaySocketRequest(1, 1, 1, id, 0x0a000001, 1234, 0x0a000002, 443, protocol)

  private open class Host : CoreFairwaySocketHost {
    val queue = LinkedBlockingQueue<Any>()
    val eof = Any()
    val dequeued = AtomicInteger()
    val stopObserved = CompletableDeferred<Unit>()
    val failures = LinkedBlockingQueue<Pair<CoreFairwaySocketRequest, CoreFairwayFdFailure>>()
    @Volatile var stopped = false
    @Volatile var current = true
    override fun awaitRequest(): CoreFairwaySocketRequest? {
      val value = queue.take()
      if (value === eof) return null
      dequeued.incrementAndGet()
      return value as CoreFairwaySocketRequest
    }
    override fun isCurrent(request: CoreFairwaySocketRequest) = current && !stopped
    override fun network(request: CoreFairwaySocketRequest): CoreFairwayNetworkBinding? = null
    override fun deliverBorrowed(request: CoreFairwaySocketRequest, fd: Int): Int = error("No real FD in this test")
    override fun rejected(request: CoreFairwaySocketRequest, failure: CoreFairwayFdFailure) {
      failures.add(request to failure)
    }
    override fun stopRequests() { stopped = true; queue.offer(eof); stopObserved.complete(Unit) }
  }

  @Test fun typedIdentityIncludesProtocolAndValidatesLegacyAddressBounds() {
    assertNotEquals(request(), request(protocol = CoreFairwaySocketProtocol.UDP))
    assertEquals(6, request().protocol.number)
    assertEquals(17, request(protocol = CoreFairwaySocketProtocol.UDP).protocol.number)
    assertThrows(IllegalArgumentException::class.java) { request(0) }
    assertThrows(IllegalArgumentException::class.java) { request().copy(destinationPort = 0) }
    assertThrows(IllegalArgumentException::class.java) { request().copy(destinationIpv4 = 0x7f000001) }
    for (limit in listOf(0, 17)) assertThrows(IllegalArgumentException::class.java) {
      CoreFairwaySocketBroker(limit, CoreFairwaySocketOpener { error("No work") })
    }
  }

  @Test fun stopCancelsAllSixteenBlockedOperationsBeforeJoiningAndBoundsDequeue() = runBlocking {
    val host = Host()
    repeat(32) { host.queue.add(request(it + 1L, if (it % 2 == 0) CoreFairwaySocketProtocol.TCP else CoreFairwaySocketProtocol.UDP)) }
    val entered = CountDownLatch(16)
    val release = CountDownLatch(1)
    val cancelled = AtomicInteger()
    val exited = AtomicInteger()
    val broker = CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener {
      object : CoreFairwaySocketOperation {
        override fun deliver(host: CoreFairwaySocketHost, active: () -> Boolean): CoreFairwayFdFailure? {
          entered.countDown()
          try {
            check(release.await(5, TimeUnit.SECONDS))
            assertFalse(active())
            return CoreFairwayFdFailure(CoreFairwayFdFailureReason.STALE)
          } finally { exited.incrementAndGet() }
        }
        override fun cancel() { assertTrue(host.stopped); cancelled.incrementAndGet(); release.countDown() }
      }
    })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      assertTrue(entered.await(3, TimeUnit.SECONDS))
      assertEquals(16, host.dequeued.get())
      job.cancel()
      withTimeout(2000) { job.join() }
      assertEquals(16, cancelled.get())
      assertEquals(16, exited.get())
      assertEquals(16, host.dequeued.get())
    } finally { release.countDown(); job.cancel(); job.join() }
  }

  @Test fun cancellationRetainsBlockedNativeWaitUntilItActuallyExits() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val host = object : Host() {
      override fun awaitRequest(): CoreFairwaySocketRequest? {
        entered.complete(Unit)
        check(release.await(5, TimeUnit.SECONDS))
        return null
      }
    }
    val broker = CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener { error("No request") })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { host.stopObserved.await() }
      assertFalse(job.isCompleted)
    } finally { release.countDown(); job.join() }
  }

  @Test fun cancellationFailureStillCancelsEveryOperationAndWaitsForCleanup() = runBlocking {
    val entered = CountDownLatch(2)
    val release = CountDownLatch(1)
    val cancelled = AtomicInteger()
    val exited = AtomicInteger()
    val host = object : Host() {
      override fun awaitRequest(): CoreFairwaySocketRequest? {
        val next = super.awaitRequest()
        if (next == null) check(entered.await(3, TimeUnit.SECONDS))
        return next
      }
    }.apply { queue.add(request(1)); queue.add(request(2)); queue.add(eof) }
    val broker = CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener { request ->
      object : CoreFairwaySocketOperation {
        override fun deliver(host: CoreFairwaySocketHost, active: () -> Boolean): CoreFairwayFdFailure? {
          entered.countDown()
          try { check(release.await(5, TimeUnit.SECONDS)); return null }
          finally { exited.incrementAndGet() }
        }
        override fun cancel() {
          assertTrue(host.stopped)
          cancelled.incrementAndGet()
          release.countDown()
          if (request.flowId == 1L) error("Cancellation failure")
        }
      }
    })
    try {
      withTimeout(3000) { broker.run(host) }
      fail("Cancellation failure swallowed")
    } catch (error: IllegalStateException) { assertEquals("Cancellation failure", error.message) }
    finally { release.countDown() }
    assertEquals(2, cancelled.get())
    assertEquals(2, exited.get())
  }

  @Test fun staleRequestCannotConstructAnOperationAndRetainsFullTypedIdentity() = runBlocking {
    val host = Host().apply { current = false }
    val stale = request(7, CoreFairwaySocketProtocol.UDP)
    host.queue.add(stale)
    val broker = CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener { error("Stale operation opened") })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      val failure = checkNotNull(host.failures.poll(3, TimeUnit.SECONDS))
      assertEquals(stale, failure.first)
      assertEquals(CoreFairwayFdFailureReason.STALE, failure.second.reason)
    } finally { job.cancel(); job.join() }
  }

  @Test fun stopDuringOperationConstructionCancelsBeforeAnySocketWork() = runBlocking {
    val host = Host().apply { queue.add(request()) }
    val entered = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val cancelled = AtomicInteger()
    val opened = AtomicInteger()
    val broker = CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener {
      entered.complete(Unit)
      check(release.await(5, TimeUnit.SECONDS))
      object : CoreFairwaySocketOperation {
        override fun deliver(host: CoreFairwaySocketHost, active: () -> Boolean): CoreFairwayFdFailure? {
          opened.incrementAndGet(); return null
        }
        override fun cancel() { cancelled.incrementAndGet() }
      }
    })
    val job = launch(Dispatchers.Default) { broker.run(host) }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { host.stopObserved.await() }
      release.countDown()
      withTimeout(2000) { job.join() }
      assertEquals(1, cancelled.get())
      assertEquals(0, opened.get())
    } finally { release.countDown(); job.cancel(); job.join() }
  }

  @Test fun eofStopsWithoutOpeningAndFactoryFailureUnblocksProducer() = runBlocking {
    val ended = Host().apply { queue.add(eof) }
    withTimeout(2000) {
      CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener { error("EOF opened") }).run(ended)
    }
    assertTrue(ended.stopped)
    val failed = Host().apply { queue.add(request()) }
    try {
      withTimeout(2000) {
        CoreFairwaySocketBroker(opener = CoreFairwaySocketOpener { error("Factory failure") }).run(failed)
      }
      fail("Factory failure swallowed")
    } catch (error: IllegalStateException) { assertEquals("Factory failure", error.message) }
    assertTrue(failed.stopped)
  }
}
