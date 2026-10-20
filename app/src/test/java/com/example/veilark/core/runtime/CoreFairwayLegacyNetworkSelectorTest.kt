package com.example.veilark.core.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Pure callback selection/currency checks; no Android/API29 network acceptance. */
class CoreFairwayLegacyNetworkSelectorTest {
  private fun ready(state: CoreFairwayLegacyNetworkSelector<String>, network: String) {
    state.available(network)
    state.capabilities(network, true)
    state.link(network, listOf(network, "address", "routes", "dns"))
    state.blocked(network, false)
  }

  @Test fun allInitialCallbackOrdersRequireCapabilitiesLinkAndExplicitUnblocked() {
    for (order in listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
      listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))) {
      val state = CoreFairwayLegacyNetworkSelector<String>()
      state.capabilities("unknown", true)
      state.link("unknown", "link")
      state.blocked("unknown", false)
      assertNull(state.snapshot())
      state.available("A")
      order.forEachIndexed { index, event ->
        when (event) {
          0 -> state.capabilities("A", true)
          1 -> state.link("A", "link")
          2 -> state.blocked("A", false)
        }
        if (index < 2) assertNull(state.snapshot())
      }
      assertEquals("A", state.snapshot()!!.network)
      assertEquals(1L, state.snapshot()!!.epoch)
    }
  }

  @Test fun firstSoleReadyDoesNotWinLaterAmbiguityInEitherArrivalOrder() {
    for ((first, second) in listOf("A" to "B", "B" to "A")) {
      val state = CoreFairwayLegacyNetworkSelector<String>()
      ready(state, first)
      val provisional = state.snapshot()!!
      ready(state, second)
      assertNull(state.snapshot())
      assertFalse(state.isCurrent(provisional))
      state.available(first)
      state.capabilities(first, true)
      state.blocked(first, false)
      assertNull(state.snapshot())
      state.lost(second)
      val soleAgain = state.snapshot()!!
      assertEquals(first, soleAgain.network)
      assertTrue(soleAgain.epoch > provisional.epoch)
      assertFalse(state.isCurrent(provisional))
    }
  }

  @Test fun onlyVerifiedDefaultCustodyCanRemainPinnedWhenDefaultBecomesOpaque() {
    val state = CoreFairwayLegacyNetworkSelector<String>()
    ready(state, "A")
    state.verifiedPhysicalDefault("A")
    val verifiedA = state.snapshot()!!
    ready(state, "B")
    assertSame(verifiedA, state.snapshot())
    state.verifiedPhysicalDefault(null) // A virtual/default callback is not physical loss.
    assertSame(verifiedA, state.snapshot())
    state.verifiedPhysicalDefault("B")
    val verifiedB = state.snapshot()!!
    assertEquals("B", verifiedB.network)
    assertTrue(verifiedB.epoch > verifiedA.epoch)
    assertFalse(state.isCurrent(verifiedA))
    assertFalse(state.isCurrent(verifiedB.copy()))
    state.verifiedPhysicalDefault(null)
    assertSame(verifiedB, state.snapshot())
  }

  @Test fun lossBlockingAndSuspensionRevokeVerificationWithoutStaleResurrection() {
    for (invalidate in listOf<(CoreFairwayLegacyNetworkSelector<String>) -> Unit>(
      { it.blocked("A", true) }, { it.capabilities("A", false) }, { it.lost("A") },
    )) {
      val state = CoreFairwayLegacyNetworkSelector<String>()
      ready(state, "A")
      state.verifiedPhysicalDefault("A")
      val old = state.snapshot()!!
      ready(state, "B")
      ready(state, "C")
      invalidate(state)
      assertNull(state.snapshot()) // B/C remain ambiguous after verified A is lost.
      assertFalse(state.isCurrent(old))
      ready(state, "A")
      assertNull(state.snapshot()) // Restored A has no old default authority.
      state.verifiedPhysicalDefault("A")
      val fresh = state.snapshot()!!
      assertEquals("A", fresh.network)
      assertTrue(fresh.epoch > old.epoch)
      assertFalse(state.isCurrent(old))
    }
  }

  @Test fun verifiedHintsNeverBypassReadinessAndSelectedLinkChangesRenewEpoch() {
    val state = CoreFairwayLegacyNetworkSelector<String>()
    state.verifiedPhysicalDefault("A")
    assertNull(state.snapshot())
    ready(state, "A")
    val initial = state.snapshot()!!
    ready(state, "B")
    state.link("B", "changed B link")
    state.available("A")
    state.capabilities("A", true)
    state.blocked("A", false)
    state.link("A", listOf("A", "address", "routes", "dns"))
    assertSame(initial, state.snapshot())
    state.link("A", "changed A link")
    val changed = state.snapshot()!!
    assertEquals("A", changed.network)
    assertEquals(initial.epoch + 1, changed.epoch)
    assertFalse(state.isCurrent(initial))
    state.lost("A")
    val soleB = state.snapshot()!!
    state.link("A", "late old event")
    state.capabilities("A", true)
    state.blocked("A", false)
    assertSame(soleB, state.snapshot())
  }

  @Test fun capacityAndGenerationExhaustionClosePermanentlyWithoutEvictionOrWrap() {
    assertThrows(IllegalArgumentException::class.java) { CoreFairwayLegacyNetworkSelector<String>(9) }
    val bounded = CoreFairwayLegacyNetworkSelector<String>()
    for (index in 0 until 8) ready(bounded, "network$index")
    bounded.verifiedPhysicalDefault("network0")
    val old = bounded.snapshot()!!
    bounded.available("network0") // Duplicate does not consume another capacity slot.
    assertSame(old, bounded.snapshot())
    bounded.available("ninth")
    assertNull(bounded.snapshot())
    bounded.lost("ninth")
    bounded.verifiedPhysicalDefault("network0")
    ready(bounded, "network0")
    assertNull(bounded.snapshot())
    assertFalse(bounded.isCurrent(old))

    val exhausted = CoreFairwayLegacyNetworkSelector<String>(initialEpoch = Long.MAX_VALUE - 1)
    ready(exhausted, "A")
    val last = exhausted.snapshot()!!
    assertEquals(Long.MAX_VALUE, last.epoch)
    exhausted.link("A", "new link")
    assertNull(exhausted.snapshot())
    ready(exhausted, "B")
    exhausted.verifiedPhysicalDefault("B")
    assertNull(exhausted.snapshot())
    assertFalse(exhausted.isCurrent(last))
  }

  @Test fun closeRacingPhysicalAndVerifiedDefaultCallbacksCannotRepublish() {
    val state = CoreFairwayLegacyNetworkSelector<String>()
    ready(state, "A")
    val old = state.snapshot()!!
    val start = CountDownLatch(1)
    val done = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val callback = Thread {
      try {
        check(start.await(5, TimeUnit.SECONDS))
        repeat(200) { index ->
          val network = "network${index % 4}"
          ready(state, network)
          state.verifiedPhysicalDefault(network)
          state.link(network, index)
          state.lost(network)
        }
      } catch (caught: Throwable) { failure.set(caught) }
      finally { done.countDown() }
    }
    callback.start()
    try {
      start.countDown()
      state.close()
      state.close()
      assertTrue(done.await(5, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertNull(state.snapshot())
      assertFalse(state.isCurrent(old))
    } finally { callback.join(5_000) }
  }
}
