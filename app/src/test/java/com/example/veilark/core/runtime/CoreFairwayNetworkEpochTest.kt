package com.example.veilark.core.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Pure callback-generation tests; not Android network or socket acceptance. */
class CoreFairwayNetworkEpochTest {
  private fun ready(state: CoreFairwayNetworkEpoch<String>, network: String = "wifi"):
    CoreFairwayNetworkEpoch.Snapshot<String> {
    state.available(network)
    state.link(network, listOf("192.0.2.1/24", "192.0.2.53"))
    state.blocked(network, false)
    state.capabilities(network, true)
    return requireNotNull(state.snapshot())
  }

  @Test fun requiresAvailableCapabilitiesExplicitUnblockedAndInitialLinkInAnyOrder() {
    val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
      listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
    for (order in orders) {
      val state = CoreFairwayNetworkEpoch<String>()
      state.capabilities("wifi", true)
      state.blocked("wifi", false)
      state.link("wifi", "link")
      assertNull(state.snapshot())
      state.available("wifi")
      order.forEachIndexed { index, event ->
        when (event) {
          0 -> state.capabilities("wifi", true)
          1 -> state.blocked("wifi", false)
          2 -> state.link("wifi", "link")
        }
        if (index < 2) assertNull(state.snapshot())
      }
      assertEquals(1L, state.snapshot()!!.epoch)
    }
  }

  @Test fun duplicateEventsKeepSnapshotButLinkChangesInvalidateAndAdvance() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    state.available("wifi")
    state.capabilities("wifi", true)
    state.blocked("wifi", false)
    state.link("wifi", listOf("192.0.2.1/24", "192.0.2.53"))
    assertSame(first, state.snapshot())
    assertTrue(state.isCurrent(first))
    assertFalse(state.isCurrent(first.copy()))
    state.link("wifi", listOf("192.0.2.1/24", "192.0.2.54"))
    val changed = state.snapshot()!!
    assertEquals(first.epoch + 1, changed.epoch)
    assertFalse(state.isCurrent(first))
    assertTrue(state.isCurrent(changed))
  }

  @Test fun blockingAndIneligibleInvalidateImmediatelyAndNeverReuseEpoch() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    state.blocked("wifi", true)
    assertNull(state.snapshot())
    assertFalse(state.isCurrent(first))
    state.blocked("wifi", false)
    val second = state.snapshot()!!
    assertEquals(first.epoch + 1, second.epoch)
    state.capabilities("wifi", false) // Includes suspended/ineligible transports.
    assertNull(state.snapshot())
    state.capabilities("wifi", true)
    assertEquals(second.epoch + 1, state.snapshot()!!.epoch)
    assertFalse(state.isCurrent(second))
  }

  @Test fun switchingNetworkIgnoresOldCallbacksAndRequiresFreshReadiness() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    state.available("cell")
    assertFalse(state.isCurrent(first))
    state.capabilities("wifi", true)
    state.blocked("wifi", false)
    state.link("wifi", "old changed")
    state.lost("wifi")
    assertNull(state.snapshot())
    val second = ready(state, "cell")
    assertEquals("cell", second.network)
    assertTrue(second.epoch > first.epoch)
    state.lost("wifi")
    assertSame(second, state.snapshot())
  }

  @Test fun lossAndSameNetworkReconnectCannotReviveOldSnapshot() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    state.lost("wifi")
    assertNull(state.snapshot())
    state.link("wifi", "late")
    state.capabilities("wifi", true)
    assertNull(state.snapshot())
    val second = ready(state)
    assertTrue(second.epoch > first.epoch)
    assertFalse(state.isCurrent(first))
  }

  @Test fun closeIsTerminalAndClearsCurrent() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    state.close()
    state.close()
    state.available("cell")
    state.capabilities("cell", true)
    state.blocked("cell", false)
    state.link("cell", "link")
    state.lost("wifi")
    assertNull(state.snapshot())
    assertFalse(state.isCurrent(first))
  }

  @Test fun overflowFailsClosedWithoutWrappingOrReopening() {
    val state = CoreFairwayNetworkEpoch<String>(Long.MAX_VALUE - 1)
    val last = ready(state)
    assertEquals(Long.MAX_VALUE, last.epoch)
    state.link("wifi", "new link")
    assertNull(state.snapshot())
    assertFalse(state.isCurrent(last))
    state.available("cell")
    state.capabilities("cell", true)
    state.blocked("cell", false)
    state.link("cell", "link")
    assertNull(state.snapshot())
  }

  @Test fun concurrentCloseAndCallbackSequenceCannotRepublishAfterClose() {
    val state = CoreFairwayNetworkEpoch<String>()
    val first = ready(state)
    val start = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val worker = Thread {
      try {
        check(start.await(5, TimeUnit.SECONDS))
        repeat(200) { index ->
          state.available("wifi")
          state.capabilities("wifi", true)
          state.blocked("wifi", false)
          state.link("wifi", index)
        }
      } catch (caught: Throwable) { failure.set(caught) }
      finally { finished.countDown() }
    }
    worker.start()
    try {
      start.countDown()
      state.close()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertNull(state.snapshot())
      assertFalse(state.isCurrent(first))
    } finally { worker.join(5_000) }
  }
}
