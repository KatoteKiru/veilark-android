package com.example.veilark.core.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CoreBetaLifecycleTest {
  @Test fun secondPhaseAttachRetainsOwnerAndClosesDescriptorsOnAllPaths() = runBlocking {
    val fake = Fake().apply { busy = 0 }
    val owner = CoreBetaLifecycle(fake) { }
    var closed = 0
    try { owner.attachBorrowed(CoreBetaHandleTicket(7L), { fail("Must not attach without a handle") }, { closed++ }); fail("Missing handle") }
    catch (_: IllegalStateException) { }
    assertEquals(1,closed)
    val ticket = owner.startBorrowedTicket({ 7L }, { })
    owner.attachBorrowed(ticket, { assertEquals(7L,it) }, { closed++ })
    try { owner.attachBorrowed(ticket, { throw IllegalStateException("Native rejected resource") }, { closed++ }); fail("Reject attach") }
    catch (_: IllegalStateException) { }
    assertEquals(3,closed);assertEquals(3,owner.status())
    assertTrue(owner.stopAndRelease())
    try { owner.attachBorrowed(ticket, { fail("Released handle") }, { closed++ });fail("Released owner") }
    catch (_: IllegalStateException) { }
    assertEquals(4,closed)
  }

  @Test fun cancellationDuringSecondPhaseClosesFdThenStopsAndReleases() = runBlocking {
    val fake=Fake().apply { busy=0 };val owner=CoreBetaLifecycle(fake) { }
    val ticket = owner.startBorrowedTicket({ 8L }, { })
    val entered=CompletableDeferred<Unit>();val permit=CountDownLatch(1);val closed=AtomicInteger()
    val job=launch(Dispatchers.Default) {
      owner.attachBorrowed(ticket, { handle ->
        assertEquals(8L,handle);entered.complete(Unit)
        check(permit.await(3,TimeUnit.SECONDS))
        assertFalse(fake.calls.contains("release"))
      }, { closed.incrementAndGet() })
    }
    entered.await();job.cancel();permit.countDown();job.join()
    assertEquals(1,closed.get());assertTrue(fake.calls.contains("stop"));assertTrue(fake.calls.contains("release"))
    assertNull(owner.status())
  }

  @Test fun simultaneousStopCannotReleaseHandleDuringSecondPhaseNativeDup() = runBlocking {
    val released=AtomicInteger();val closed=AtomicInteger()
    val api=object:CoreBetaHandleApi {
      override fun status(handle:Long)=3
      override fun stop(handle:Long):Int { assertEquals(1,closed.get());return 0 }
      override fun release(handle:Long):Int { released.incrementAndGet();return 0 }
    }
    val owner=CoreBetaLifecycle(api) { };val ticket=owner.startBorrowedTicket({ 9L }, { })
    val entered=CompletableDeferred<Unit>();val permit=CountDownLatch(1)
    val attaching=launch(Dispatchers.Default) {
      owner.attachBorrowed(ticket, { entered.complete(Unit);check(permit.await(3,TimeUnit.SECONDS));assertEquals(0,released.get()) }, { closed.incrementAndGet() })
    }
    entered.await()
    val stopping=launch(Dispatchers.Default) { assertTrue(owner.stopAndRelease()) }
    permit.countDown();attaching.join();stopping.join()
    assertEquals(1,released.get());assertEquals(1,closed.get());assertNull(owner.status())
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test fun staleAttachmentAndCancellationCannotAffectReplacementWithReusedHandle() = runBlocking {
    val fake=Fake().apply { busy=0 };val owner=CoreBetaLifecycle(fake) { }
    val old=owner.startBorrowedTicket({ 7L }, { });assertTrue(owner.stopAndRelease())
    val current=owner.startBorrowedTicket({ 7L }, { });var closed=0
    try { owner.attachBorrowed(old,{ fail("Old ticket must not attach") },{ closed++ });fail("Stale ticket") }
    catch (_: IllegalStateException) { }
    val stops=fake.calls.count { it=="stop" }
    val cancelled=kotlinx.coroutines.Job().apply { cancel() }
    val job=launch(Dispatchers.Default+cancelled,start=kotlinx.coroutines.CoroutineStart.ATOMIC) {
      owner.attachBorrowed(old,{ fail("Cancelled stale attach") },{ closed++ })
    }
    job.join();assertEquals(2,closed);assertEquals(stops,fake.calls.count { it=="stop" })
    owner.attachBorrowed(current,{ assertEquals(7L,it) },{ closed++ })
    assertEquals(3,closed);assertTrue(owner.stopAndRelease())
  }

  @Test fun plannedTimerCannotHideRacingFailureMaskedByStoppedStatus() = runBlocking {
    for (reason in listOf(0, 13, 11, 12, 16, -4)) {
      var status = 1
      val calls = mutableListOf<String>()
      val api = object : CoreBetaHandleApi {
        override fun status(handle: Long) = status
        override fun failure(handle: Long): Int { calls += "failure"; return reason }
        // Models READY -> timer decision -> native error -> requestStop masking status.
        override fun stop(handle: Long): Int { status = 3; return 0 }
        override fun release(handle: Long): Int { calls += "release"; return 0 }
      }
      val owner = CoreBetaLifecycle(api) { }
      owner.startBorrowed({ 7L }, { })
      val outcome = owner.stopAndReleaseForRotation()
      assertTrue(outcome.released)
      assertEquals(reason, outcome.terminalReason)
      assertEquals(reason == 13, outcome.allowsPlannedRotation)
      assertEquals(listOf("failure", "release"), calls)
      assertNull(owner.failure())
      assertEquals(listOf("failure", "release"), calls)
    }
    assertFalse(CoreBetaReleaseOutcome(false, 3, 13).allowsPlannedRotation)
    assertFalse(CoreBetaReleaseOutcome(true, -3, 13).allowsPlannedRotation)
    assertFalse(CoreBetaReleaseOutcome(true).allowsPlannedRotation)
  }

  @Test fun additiveTelemetryPreservesOldProviderAndStopsReadingAfterRelease() = runBlocking {
    var fields = 5
    var reads = 0
    val api = object : CoreBetaHandleApi {
      override fun status(handle: Long) = 3
      override fun stop(handle: Long) = 0
      override fun release(handle: Long) = 0
      override fun progress(handle: Long, field: Int): Long {
        reads++
        return if (field < fields) field.toLong() else -1
      }
    }
    val owner = CoreBetaLifecycle(api) { }
    owner.startBorrowed({ 7L }, { })
    assertArrayEquals(longArrayOf(0, 1, 2, 3, 4), owner.progress())
    fields = 10
    assertArrayEquals(LongArray(10) { it.toLong() }, owner.progress())
    fields = 4
    assertNull(owner.progress()) // Missing required counters are unavailable.
    assertTrue(owner.stopAndRelease())
    val before = reads
    assertNull(owner.progress())
    assertEquals(before, reads) // Never query a released handle.
  }
  private class Fake : CoreBetaHandleApi {
    var statusCode = 3
    var busy = 1
    val calls = mutableListOf<String>()
    override fun status(handle: Long): Int { calls += "status"; return statusCode }
    override fun failure(handle: Long): Int { calls += "failure"; return 17 }
    override fun stop(handle: Long): Int { calls += "stop"; return 0 }
    override fun release(handle: Long): Int { calls += "release"; return if (busy-- > 0) -2 else 0 }
  }
  @Test fun terminalReasonMustBeCapturedBeforeHandleRelease() = runBlocking {
    val fake = Fake(); fake.busy = 0
    val lifecycle = CoreBetaLifecycle(fake) { }
    lifecycle.startBorrowed({ 1L }, { })
    assertEquals(17, lifecycle.failure())
    assertTrue(lifecycle.stopAndRelease())
    assertNull(lifecycle.failure())
    assertTrue(fake.calls.indexOf("failure") < fake.calls.indexOf("release"))
    assertEquals(1, fake.calls.count { it == "failure" })
  }
  @Test fun failedNativeAdmissionKeepsCodeAndClosesCallerDescriptors() = runBlocking {
    val lifecycle = CoreBetaLifecycle(Fake()) { }
    var closed = 0
    try {
      lifecycle.startBorrowed({ -3L }, { closed++ })
      fail("Expected native admission failure")
    } catch (failure: CoreBetaStartFailure) { assertEquals(-3L, failure.code) }
    assertEquals(1, closed)
    assertNull(lifecycle.failure())
  }
  @Test fun terminalBusyRequiresSuccessfulReleaseBeforeRestart() = runBlocking {
    val fake = Fake(); val lifecycle = CoreBetaLifecycle(fake) { }
    var closed = 0
    assertEquals(1L, lifecycle.startBorrowed({ 1L }, { closed++ }))
    assertTrue(lifecycle.stopAndRelease(3))
    assertEquals(listOf("stop", "status", "release", "status", "release"), fake.calls)
    assertEquals(2L, lifecycle.startBorrowed({ 2L }, { closed++ }))
    assertEquals(2, closed)
  }
  @Test fun cleanupTimeoutKeepsOwnershipAndClosesRejectedNewDescriptors() = runBlocking {
    val fake = Fake().apply { statusCode = 2 }
    val lifecycle = CoreBetaLifecycle(fake) { }
    lifecycle.startBorrowed({ 1L }, { })
    assertFalse(lifecycle.stopAndRelease(2))
    var called = false; var closed = false
    try {
      lifecycle.startBorrowed({ called = true; 2L }, { closed = true })
      fail("Must preserve existing worker ownership")
    } catch (_: IllegalStateException) { }
    assertFalse(called); assertTrue(closed)
    assertFalse(fake.calls.contains("release"))
  }
  @Test fun failedStartClosesOriginalsAndLeavesNoHandle() = runBlocking {
    val lifecycle = CoreBetaLifecycle(Fake()) { }
    var closed = false
    try { lifecycle.startBorrowed({ -1L }, { closed = true }); fail("Expected rejection") }
    catch (_: IllegalStateException) { }
    assertTrue(closed); assertTrue(lifecycle.stopAndRelease())
  }
  @Test fun lateReaperReleasesRetainedHandleOnlyAfterWorkerBecomesTerminal() = runBlocking {
    val fake = Fake().apply { statusCode = 2; busy = 0 }
    val lifecycle = CoreBetaLifecycle(fake) { }
    lifecycle.startBorrowed({ 1L }, { })
    assertFalse(lifecycle.stopAndRelease(2))
    assertFalse(fake.calls.contains("release"))
    fake.statusCode = 3
    val released = AtomicInteger()
    val reaper = CoreBetaLateCleanup(
      cleanup = { lifecycle.stopAndRelease() },
      onReleased = { released.incrementAndGet() }, event = { },
    )
    reaper.launchOnce()!!.join()
    assertEquals(1, released.get())
    assertNull(lifecycle.status())
    assertEquals(2L, lifecycle.startBorrowed({ 2L }, { }))
  }
  @Test fun statusReportsTerminalAndFailureWithoutDroppingOwnership() = runBlocking {
    val fake = Fake().apply { busy = 0 }
    val lifecycle = CoreBetaLifecycle(fake) { }
    assertNull(lifecycle.status())
    lifecycle.startBorrowed({ 9L }, { })
    fake.statusCode = 1
    assertEquals(1, lifecycle.status())
    fake.statusCode = -3
    assertEquals(-3, lifecycle.status())
    assertTrue(lifecycle.stopAndRelease())
    assertNull(lifecycle.status())
  }
  @Test fun cancellationDuringStartStopsAndReleasesCreatedHandle() = runBlocking {
    val fake = Fake().apply { busy = 0 }
    val lifecycle = CoreBetaLifecycle(fake) { }
    val entered = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val closed = AtomicInteger()
    val job = launch(Dispatchers.Default) {
      lifecycle.startBorrowed({
        entered.complete(Unit)
        check(permit.await(3, TimeUnit.SECONDS))
        1L
      }, { closed.incrementAndGet() })
    }
    entered.await(); job.cancel(); permit.countDown(); job.join()
    assertEquals(1, closed.get())
    assertTrue(fake.calls.contains("stop"))
    assertTrue(fake.calls.contains("release"))
    assertTrue(lifecycle.stopAndRelease())
  }
}
