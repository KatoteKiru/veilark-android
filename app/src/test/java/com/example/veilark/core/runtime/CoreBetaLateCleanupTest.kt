package com.example.veilark.core.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreBetaLateCleanupTest {
  @Test fun completionAfterInitialTimeoutReleasesExactlyOnce() = runBlocking {
    val attempts = AtomicInteger()
    val released = AtomicInteger()
    val events = mutableListOf<String>()
    val cleanup = CoreBetaLateCleanup(
      cleanup = { attempts.incrementAndGet() == 3 },
      onReleased = { released.incrementAndGet() }, event = { events += it },
    )
    cleanup.launchOnce()!!.join()
    assertEquals(3, attempts.get())
    assertEquals(1, released.get())
    assertEquals(listOf("cleanup_pending", "cleanup_complete"), events)
    assertNull(cleanup.launchOnce())
  }

  @Test fun exhaustedBudgetDoesNotReleaseOrPermitAnotherReaper() = runBlocking {
    val attempts = AtomicInteger()
    val released = AtomicInteger()
    val events = mutableListOf<String>()
    val cleanup = CoreBetaLateCleanup(
      cleanup = { attempts.incrementAndGet(); false },
      onReleased = { released.incrementAndGet() }, event = { events += it },
    )
    cleanup.launchOnce()!!.join()
    assertEquals(6, attempts.get())
    assertEquals(0, released.get())
    assertEquals(listOf("cleanup_pending", "cleanup_timeout"), events)
    assertNull(cleanup.launchOnce())
  }

  @Test fun callerCancellationCannotCancelRetainedCleanupAndDuplicateIsRejected() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val permit = CompletableDeferred<Unit>()
    val released = AtomicInteger()
    val events = mutableListOf<String>()
    val cleanup = CoreBetaLateCleanup(
      cleanup = { entered.complete(Unit); permit.await(); true },
      onReleased = { released.incrementAndGet() }, event = { events += it },
    )
    val retainedJob = CompletableDeferred<kotlinx.coroutines.Job>()
    val caller = launch(Dispatchers.Default) {
      retainedJob.complete(cleanup.launchOnce()!!)
      permit.await()
    }
    entered.await()
    caller.cancel(); caller.join()
    assertNull(cleanup.launchOnce())
    assertEquals(0, released.get())
    permit.complete(Unit)
    retainedJob.await().join()
    assertEquals(1, released.get())
    assertEquals(listOf("cleanup_pending", "cleanup_complete"), events)
  }
}
