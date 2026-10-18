package com.example.veilark.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class CoreBetaSessionGateTest {
  @Test fun blockingAndroidEffectsDoNotBlockServiceCallbacksOrReleaseAdmissionEarly() {
    val gate = CoreBetaSessionGate<Any>()
    val lease = Any()
    gate.admit(lease); gate.resources(lease, native = true, tun = true); gate.terminal(lease)
    val entered = CountDownLatch(1)
    val proceed = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    val effects = pool.submit { gate.finishServiceEffects(lease) {
      entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
    } }
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      val callback = pool.submit<Boolean> {
        assertSame(lease, gate.current())
        assertFalse(gate.takeReleased(lease))
        assertFalse(gate.admit(Any()))
        gate.finishServiceEffects(lease) { fail("Duplicate Android effects") }
        true
      }
      assertTrue(callback.get(2, TimeUnit.SECONDS))
    } finally { proceed.countDown(); effects.get(2, TimeUnit.SECONDS); pool.shutdownNow() }
    assertTrue(gate.takeReleased(lease))
  }
  private class Lease {
    val job = SupervisorJob()
    val scope = CoroutineScope(job)
  }

  @Test fun restartWhileBoundWaitsForResourcesJobAndGlobalEffects() {
    val gate = CoreBetaSessionGate<Lease>()
    val old = Lease(); val next = Lease()
    assertTrue(gate.admit(old))
    gate.resources(old, native = true, tun = true)
    assertFalse(gate.takeReleased(old)) // Cleanup in finally is not job completion.
    gate.terminal(old)
    assertFalse(gate.takeReleased(old)) // Old stopSelf/foreground removal not executed.
    var stops = 0
    gate.finishServiceEffects(old) { stops++ }
    assertTrue(gate.takeReleased(old))
    // No onDestroy callback: Android still holds the service binder.
    assertTrue(gate.admit(next))
    assertEquals(1, stops)
    old.scope.cancel()
    assertFalse(old.job.isActive)
    assertTrue(next.job.isActive) // A fresh lease must own a fresh parent Job.
    next.scope.cancel()
  }

  @Test fun lateOldCleanupCannotMutateReplacementOrRunGlobalServiceEffects() {
    val gate = CoreBetaSessionGate<Lease>()
    val old = Lease(); val next = Lease()
    gate.admit(old)
    gate.terminal(old)
    gate.finishServiceEffects(old) { }
    gate.resources(old, native = false, tun = true)
    assertFalse(gate.takeReleased(old))
    assertFalse(gate.admit(next)) // Native BUSY is not a stopped tunnel.
    gate.resources(old, native = true, tun = true)
    assertTrue(gate.takeReleased(old))
    assertTrue(gate.admit(next))
    gate.resources(old, native = true, tun = true)
    gate.terminal(old)
    gate.finishServiceEffects(old) { fail("Stale cleanup must not stop replacement") }
    assertFalse(gate.takeReleased(old))
    assertSame(next, gate.current())
    assertFalse(gate.takeReleased(next))
    old.scope.cancel(); next.scope.cancel()
  }

  @Test fun terminalFirstAndFailedTunCloseStillHoldAdmissionFence() {
    val gate = CoreBetaSessionGate<Lease>()
    val lease = Lease()
    gate.admit(lease); gate.terminal(lease)
    gate.finishServiceEffects(lease) { }
    gate.resources(lease, native = true, tun = false)
    assertFalse(gate.takeReleased(lease))
    assertFalse(gate.admit(Lease()))
    gate.resources(lease, native = true, tun = true)
    assertTrue(gate.takeReleased(lease))
    assertFalse(gate.takeReleased(lease))
    lease.scope.cancel()
  }
}
