package com.example.veilark.core.runtime

import com.example.veilark.lifecycle.EngineId
import com.example.veilark.lifecycle.LifecycleAttempt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class CoreBetaStopRequestTest {
  @Test fun boundWorkerIsCancelledAndRunsCleanupWithoutOnDestroy() = runBlocking {
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val cleanup = CompletableDeferred<Unit>()
    val worker = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try { awaitCancellation() } finally { cleanup.complete(Unit) }
    }
    val current = LifecycleAttempt(9, 2, EngineId.CoreBeta)
    assertFalse(handleCoreStopCommand(current, 8, 2) { serviceScope.cancel() })
    assertTrue(worker.isActive)
    // Android may still bind the service: no destruction callback is invoked.
    assertTrue(handleCoreStopCommand(current, 9, 2) { serviceScope.cancel() })
    withTimeout(1_000) { cleanup.await(); worker.join() }
    assertTrue(worker.isCancelled)
  }
  @Test fun staleOrMissingStopCommandCannotCancelReplacementService() {
    val current = LifecycleAttempt(9, 2, EngineId.CoreBeta)
    assertTrue(coreStopCommandMatches(current, 9, 2))
    assertFalse(coreStopCommandMatches(current, 8, 2))
    assertFalse(coreStopCommandMatches(current, 9, 1))
    assertFalse(coreStopCommandMatches(current, -1, -1))
    assertFalse(coreStopCommandMatches(null, 9, 2))
  }
  @Test fun cancellationReachesCapturedServiceWhileSerializedOwnerIsBlocked() = runBlocking {
    val ownerMutex = Mutex(locked = true)
    val captured = LifecycleAttempt(7, 2, EngineId.CoreBeta)
    var serviceStop: LifecycleAttempt? = null
    var serializedStopRan = false
    val queued = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
    requestCoreStop(captured, submitOwnerStop = {
      queued += async(start = CoroutineStart.UNDISPATCHED) {
        ownerMutex.withLock { serializedStopRan = true }
      }
    }, stopCapturedService = { serviceStop = it })
    assertEquals(captured, serviceStop)
    assertFalse(serializedStopRan)
    ownerMutex.unlock()
    queued.single().await()
    assertTrue(serializedStopRan)
  }

  @Test fun queuedStartWithoutServiceStillReceivesOwnerStopAndDoesNotStopFutureLease() {
    var submitted = false
    requestCoreStop(null, { submitted = true }, { fail("No current lease to stop") })
    assertTrue(submitted)
  }
}
