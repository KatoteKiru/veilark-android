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
    override fun stop(handle: Long): Int { calls += "stop"; return 0 }
    override fun release(handle: Long): Int { calls += "release"; return if (busy-- > 0) -2 else 0 }
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
