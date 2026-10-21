package com.example.veilark.core.runtime

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** API-action ownership tests, not real ConnectivityManager callback acceptance. */
class CoreFairwayCallbackCustodyTest {
  @Test fun oneOrTwoDistinctRegistrationsCloseIdempotentlyAndNeverRestart() {
    for (plan in listOf(listOf("best"), listOf("physical", "default"))) {
      val registered = mutableListOf<String>()
      val removed = mutableListOf<String>()
      var invalid = false
      val custody = CoreFairwayCallbackCustody<String>(
        invalidate = { invalid = true }, register = { registered.add(it) },
        unregister = { assertTrue(invalid); removed.add(it) },
      )
      custody.start(plan)
      custody.start(plan)
      assertEquals(plan, registered)
      custody.close()
      custody.close()
      custody.start(plan)
      assertEquals(plan, removed)
      assertEquals(plan, registered)
    }
    val invalid = CoreFairwayCallbackCustody<String>({}, { fail("Must not register") }, {})
    assertThrows(IllegalArgumentException::class.java) { invalid.start(emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { invalid.start(listOf("same", "same")) }
    assertThrows(IllegalArgumentException::class.java) { invalid.start(listOf("1", "2", "3")) }
  }

  @Test fun partialRegistrationFailureInvalidatesBeforeRollbackAndRetainsFailedRemoval() {
    val registerFailure = IllegalStateException("second registration rejected")
    val rollbackFailure = IOException("first unregister failed")
    val registered = mutableListOf<String>()
    val removals = mutableListOf<String>()
    var invalid = false
    var failRemoval = true
    val custody = CoreFairwayCallbackCustody<String>(
      invalidate = { invalid = true },
      register = { registered.add(it); if (it == "default") throw registerFailure },
      unregister = {
        assertTrue(invalid); removals.add(it)
        if (failRemoval) { failRemoval = false; throw rollbackFailure }
      },
    )
    val error = assertThrows(IllegalStateException::class.java) { custody.start(listOf("physical", "default")) }
    assertSame(registerFailure, error)
    assertSame(rollbackFailure, error.suppressed.single())
    assertEquals(listOf("physical"), removals)
    custody.close()
    custody.close()
    custody.start(listOf("physical", "default"))
    assertEquals(listOf("physical", "physical"), removals)
    assertEquals(listOf("physical", "default"), registered)
  }

  @Test fun closeDuringRegistrationCompensatesLateSuccessAndSkipsSecondObserver() {
    val entered = CountDownLatch(1)
    val completeRegistration = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val registered = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var invalidated = false
    val custody = CoreFairwayCallbackCustody<String>(
      invalidate = { invalidated = true },
      register = {
        registered.add(it)
        entered.countDown()
        check(completeRegistration.await(5, TimeUnit.SECONDS))
      },
      unregister = { assertTrue(invalidated); removed.add(it) },
    )
    val worker = Thread {
      try { custody.start(listOf("physical", "default")) }
      catch (caught: Throwable) { failure.set(caught) }
      finally { finished.countDown() }
    }
    worker.start()
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      custody.close()
      assertTrue(invalidated)
      completeRegistration.countDown()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertEquals(listOf("physical"), registered)
      assertEquals(listOf("physical"), removed)
    } finally { completeRegistration.countDown(); worker.join(5_000) }
  }

  @Test fun oneUnregisterFailureCannotSkipTheOtherObserverAndCanBeRetried() {
    var failFirst = true
    val removed = mutableListOf<String>()
    val custody = CoreFairwayCallbackCustody<String>({}, {}, {
      removed.add(it)
      if (it == "physical" && failFirst) { failFirst = false; throw IOException("retry") }
    })
    custody.start(listOf("physical", "default"))
    assertThrows(IOException::class.java) { custody.close() }
    assertEquals(listOf("physical", "default"), removed)
    custody.close()
    custody.close()
    assertEquals(listOf("physical", "default", "physical"), removed)
  }
}
