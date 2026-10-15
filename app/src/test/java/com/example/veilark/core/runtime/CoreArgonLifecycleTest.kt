package com.example.veilark.core.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic lifecycle mocks; these do not establish native TUN or device acceptance. */
class CoreArgonLifecycleTest {
  private open class Fake : CoreArgonHandleApi {
    val stops = AtomicInteger()
    val releases = AtomicInteger()
    val attaches = AtomicInteger()
    var joinCode = 0
    var releaseCode = 0
    override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
      intArrayOf(10, 20, 30, 40, 1320).copyInto(output)
      return 0
    }
    override fun attachTun(handle: Long, borrowedTunFd: Int): Int { attaches.incrementAndGet(); return 0 }
    override fun awaitRunning(handle: Long) = 0
    override fun status(handle: Long) = 3
    override fun stop(handle: Long): Int { stops.incrementAndGet(); return 0 }
    override fun join(handle: Long) = joinCode
    override fun release(handle: Long): Int { releases.incrementAndGet(); return releaseCode }
  }

  @Test fun cancellationInAuthenticationSignalsStopBeforeJoiningBlockedWait() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
        entered.complete(Unit)
        check(permit.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
        return -1
      }
      override fun stop(handle: Long): Int { stopped.complete(Unit); return super.stop(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    val job = launch(Dispatchers.Default) { owner.awaitAuthenticated(ticket) }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { stopped.await() }
      assertFalse(job.isCompleted)
      assertEquals(0, fake.releases.get())
    } finally { permit.countDown(); job.join() }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun joinFailureRetainsOwnershipAndAllowsCleanupRetry() = runBlocking {
    val fake = Fake().apply { joinCode = -2 }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    assertFalse(owner.stopAndRelease(ticket))
    assertEquals(0, fake.releases.get())
    var closed = 0
    try { owner.startBorrowed({ fail("No replacement while retained"); 8L }, { closed++ }); fail("Retained owner") }
    catch (_: IllegalStateException) { }
    assertEquals(1, closed)
    fake.joinCode = 0
    assertTrue(owner.stopAndRelease(ticket))
    assertNull(owner.status())
  }

  @Test fun staleTicketCannotAttachOrCleanReplacementWithSameNumericHandle() = runBlocking {
    val fake = Fake()
    val owner = CoreArgonLifecycle(fake)
    val old = owner.startBorrowed({ 7L }, { })
    assertTrue(owner.stopAndRelease(old))
    val current = owner.startBorrowed({ 7L }, { })
    val stopsBefore = fake.stops.get()
    var closed = 0
    try { owner.attachBorrowed(old, 8, { closed++ }); fail("Stale attach") }
    catch (_: IllegalStateException) { }
    assertTrue(owner.stopAndRelease(old))
    assertEquals(stopsBefore, fake.stops.get())
    assertEquals(0, fake.attaches.get())
    assertEquals(1, closed)
    owner.attachBorrowed(current, 9, { closed++ })
    assertEquals(1, fake.attaches.get())
    assertEquals(2, closed)
    assertTrue(owner.stopAndRelease(current))
  }

  @Test fun statusRemainsResponsiveDuringCleanupJoin() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val fake = object : Fake() {
      override fun join(handle: Long): Int {
        entered.complete(Unit)
        check(permit.await(5, TimeUnit.SECONDS))
        return 0
      }
    }
    val owner = CoreArgonLifecycle(fake)
    owner.startBorrowed({ 7L }, { })
    val cleanup = launch(Dispatchers.Default) { assertTrue(owner.stopAndRelease()) }
    try {
      withTimeout(2000) { entered.await() }
      assertEquals(3, withTimeout(2000) { owner.status() })
      assertFalse(owner.stopAndRelease())
    } finally { permit.countDown(); cleanup.join() }
    assertNull(owner.status())
  }

  @Test fun successfulAdmissionSurvivesThrowingOriginalCloseUntilCleanup() = runBlocking {
    val fake = Fake().apply { joinCode = -2 }
    val owner = CoreArgonLifecycle(fake)
    var closed = 0
    try {
      owner.startBorrowed({ 7L }, { closed++; throw IllegalStateException("close failed") })
      fail("Throwing close")
    } catch (_: IllegalStateException) { }
    assertEquals(1, closed)
    assertEquals(1, fake.stops.get())
    assertEquals(3, owner.status())
    fake.joinCode = 0
    assertTrue(owner.stopAndRelease())
  }

  @Test fun validatesAuthenticatedConfigurationAndRequiresRealRunningWait() = runBlocking {
    val fake = object : Fake() {
      var invalid = false
      override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
        super.awaitAuthenticated(handle, output)
        if (invalid) output[0] = 127
        return 0
      }
      override fun awaitRunning(handle: Long) = -3
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    assertEquals(CoreArgonAuthenticated("10.20.30.40", 1320), owner.awaitAuthenticated(ticket))
    try { owner.awaitRunning(ticket); fail("Running wait rejected") }
    catch (failure: CoreArgonFailure) { assertEquals(-3L, failure.code) }
    assertNull(owner.status())
    fake.invalid = true
    val next = owner.startBorrowed({ 7L }, { })
    try { owner.awaitAuthenticated(next); fail("Loopback assignment rejected") }
    catch (_: IllegalStateException) { }
    assertNull(owner.status())
  }
}
