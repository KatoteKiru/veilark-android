package com.example.veilark.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class CoreBetaSessionGateTest {
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
