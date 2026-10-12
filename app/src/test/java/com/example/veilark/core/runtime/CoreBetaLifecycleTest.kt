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
