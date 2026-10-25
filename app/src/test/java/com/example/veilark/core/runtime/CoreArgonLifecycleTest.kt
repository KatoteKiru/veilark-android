package com.example.veilark.core.runtime

import com.example.veilark.profile.CoreArgonHandshakeProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic lifecycle mocks; these do not establish native TUN or device acceptance. */
class CoreArgonLifecycleTest {
  @Test fun passageAuthenticatesOnlyItsOwnPairAndMtuWithoutLegacyFallback() = runBlocking {
    for ((mtu, wire, feedback) in listOf(Triple(1357, 3, 1), Triple(1358, 3, 1),
      Triple(1280, 2, 1), Triple(1280, 3, 0))) {
      val fake = object : Fake() {
        override fun awaitAuthenticated(handle: Long, output: IntArray): Int = error("Legacy downgrade")
        override fun awaitAuthenticatedV2(handle: Long, output: IntArray): Int {
          assertEquals(7, output.size)
          intArrayOf(10, 0, 0, 1, mtu, wire, feedback).copyInto(output)
          return 0
        }
      }
      val owner = CoreArgonLifecycle(fake)
      val ticket = owner.startBorrowedWithProfile(CoreArgonHandshakeProfile.PASSAGE_V1, { 7L }, {})
      if (mtu == 1357 && wire == 3 && feedback == 1) {
        assertEquals(CoreArgonAuthenticated("10.0.0.1", mtu), owner.awaitAuthenticated(ticket))
        assertTrue(owner.stopAndRelease(ticket))
      } else {
        try { owner.awaitAuthenticated(ticket); fail("Invalid Passage authentication") }
        catch (_: IllegalStateException) { }
        assertNull(owner.status())
      }
      assertEquals(1, fake.releases.get())
    }
  }
  @Test fun explicitV2UsesOnlyAuthenticatedV2AndValidatesTheBoundPair() = runBlocking {
    val fake = object : Fake() {
      override fun awaitAuthenticated(handle: Long, output: IntArray): Int = error("Legacy downgrade")
      override fun awaitAuthenticatedV2(handle: Long, output: IntArray): Int {
        assertEquals(7, output.size)
        intArrayOf(10, 0, 0, 1, 1360, 2, 1).copyInto(output)
        return 0
      }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowedWithProfile(CoreArgonHandshakeProfile.CARRIER2_LAB1, { 7L }, {})
    assertEquals(CoreArgonAuthenticated("10.0.0.1", 1360), owner.awaitAuthenticated(ticket))
    assertTrue(owner.stopAndRelease(ticket))
  }

  @Test fun mismatchedAuthenticatedPairDisposesBeforeTunAdmission() = runBlocking {
    for ((wire, feedback) in listOf(1 to 1, 2 to 0, 2 to 2)) {
      val fake = object : Fake() {
        override fun awaitAuthenticatedV2(handle: Long, output: IntArray): Int {
          intArrayOf(10, 0, 0, 1, 1360, wire, feedback).copyInto(output)
          return 0
        }
      }
      val owner = CoreArgonLifecycle(fake)
      val ticket = owner.startBorrowedWithProfile(CoreArgonHandshakeProfile.CARRIER2_LAB1, { 7L }, {})
      try { owner.awaitAuthenticated(ticket); fail("Mismatched authenticated profile") }
      catch (_: IllegalStateException) { }
      assertEquals(1, fake.releases.get())
      assertNull(owner.status())
    }
  }

  @Test fun missingV2ApiCannotFallBackToLegacyAndStillDisposes() = runBlocking {
    val fake = Fake()
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowedWithProfile(CoreArgonHandshakeProfile.CARRIER2_LAB1, { 7L }, {})
    try { owner.awaitAuthenticated(ticket); fail("Missing capability") }
    catch (failure: CoreArgonFailure) { assertEquals(-3L, failure.code) }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun feedbackOutcomesAreRecognizedOnlyOnTheirExplicitProfile() = runBlocking {
    for (profile in CoreArgonHandshakeProfile.entries) for (code in listOf(28, 29)) {
      val owner = CoreArgonLifecycle(object : Fake() { override fun terminalOutcome(handle: Long) = code })
      val ticket = owner.startBorrowedWithProfile(profile, { 7L }, {})
      assertTrue(owner.stopAndRelease(ticket))
      assertEquals(if (profile.feedbackVersion == 1) code else null,
        ticket.releasedTerminalOutcome)
    }
  }

  @Test fun legacyRepairOutcomesRequireLegacyAndCompletedDisposal() = runBlocking {
    for (profile in CoreArgonHandshakeProfile.entries) for (code in listOf(32, 33)) {
      var joined = false
      val fake = object : Fake() {
        override fun join(handle: Long): Int { joined = true; return 0 }
        override fun terminalOutcome(handle: Long): Int { assertTrue(joined); return code }
      }
      val owner = CoreArgonLifecycle(fake)
      val ticket = owner.startBorrowedWithProfile(profile, { 7L }, {})
      fake.releaseCode = -1
      assertFalse(owner.stopAndRelease(ticket))
      assertNull(ticket.releasedTerminalOutcome)
      fake.releaseCode = 0
      assertTrue(owner.stopAndRelease(ticket))
      assertEquals(if (profile == CoreArgonHandshakeProfile.LEGACY) code else null,
        ticket.releasedTerminalOutcome)
    }
  }

  @Test fun terminalOutcomeIsReadAfterJoinAndExposedOnlyAfterDisposal() = runBlocking {
    val calls = mutableListOf<String>()
    lateinit var ticket: CoreArgonHandleTicket
    val fake = object : Fake() {
      override fun join(handle: Long): Int { calls += "join"; return 0 }
      override fun terminalOutcome(handle: Long): Int {
        calls += "outcome"; assertNull(ticket.releasedTerminalOutcome); return 2
      }
      override fun release(handle: Long): Int {
        calls += "release"; assertNull(ticket.releasedTerminalOutcome); return 0
      }
    }
    val owner = CoreArgonLifecycle(fake)
    ticket = owner.startBorrowed({ 7L }, {})
    assertTrue(owner.stopAndRelease(ticket))
    assertEquals(listOf("join", "outcome", "release"), calls)
    assertEquals(2, ticket.releasedTerminalOutcome)
  }

  @Test fun failedJoinOrReleaseCannotPublishOutcome() = runBlocking {
    var reads = 0
    val fake = object : Fake() {
      override fun terminalOutcome(handle: Long): Int { reads++; return 2 }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, {})
    fake.joinCode = -1
    assertFalse(owner.stopAndRelease(ticket)); assertNull(ticket.releasedTerminalOutcome)
    assertEquals(0, reads)
    fake.joinCode = 0; fake.releaseCode = -1
    assertFalse(owner.stopAndRelease(ticket)); assertNull(ticket.releasedTerminalOutcome)
    assertEquals(1, reads)
    fake.releaseCode = 0
    assertTrue(owner.stopAndRelease(ticket)); assertEquals(2, ticket.releasedTerminalOutcome)
  }

  @Test fun missingOrUnknownOutcomeNeverPreventsCleanupAndRemainsConservative() = runBlocking {
    for (code in listOf(null, 0, -3, 30, 31, 99)) {
      val fake = object : Fake() { override fun terminalOutcome(handle: Long) = code }
      val owner = CoreArgonLifecycle(fake)
      val ticket = owner.startBorrowed({ 7L }, {})
      assertTrue(owner.stopAndRelease(ticket)); assertNull(ticket.releasedTerminalOutcome)
    }
    val fake = object : Fake() {
      override fun terminalOutcome(handle: Long): Int = throw UnsatisfiedLinkError("test optional symbol")
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, {})
    assertTrue(owner.stopAndRelease(ticket)); assertNull(ticket.releasedTerminalOutcome)
  }

  @Test fun failedOutcomeIsNotChangedByLaterStopOrStaleCleanup() = runBlocking {
    val fake = object : Fake() { override fun terminalOutcome(handle: Long) = 24 }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, {})
    assertTrue(owner.stopAndRelease(ticket)); assertEquals(24, ticket.releasedTerminalOutcome)
    val replacement = owner.startBorrowed({ 8L }, {})
    assertTrue(owner.stopAndRelease(ticket))
    assertEquals(24, ticket.releasedTerminalOutcome); assertNull(replacement.releasedTerminalOutcome)
    assertTrue(owner.stopAndRelease(replacement))
  }

  private open class Fake : CoreArgonHandleApi {
    val stops = AtomicInteger()
    val releases = AtomicInteger()
    val attaches = AtomicInteger()
    val dnsAttaches = AtomicInteger()
    var joinCode = 0
    var releaseCode = 0
    override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
      intArrayOf(10, 20, 30, 40, 1320).copyInto(output)
      return 0
    }
    override fun attachTun(handle: Long, borrowedTunFd: Int): Int { attaches.incrementAndGet(); return 0 }
    override fun attachRuDns(handle: Long, borrowedResolverFd: Int): Int { dnsAttaches.incrementAndGet(); return 0 }
    override fun awaitRunning(handle: Long) = 0
    override fun awaitTerminal(handle: Long) = 6
    override fun status(handle: Long) = 3
    override fun stop(handle: Long): Int { stops.incrementAndGet(); return 0 }
    override fun join(handle: Long) = joinCode
    override fun release(handle: Long): Int { releases.incrementAndGet(); return releaseCode }
  }

  @Test fun cleanupWakesAndDrainsCallbacksBeforeNativeJoinOrRelease() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopped = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun stop(handle: Long): Int { stopped.countDown(); return super.stop(handle) }
      override fun join(handle: Long): Int { assertEquals(1, exited.get()); return super.join(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, {})
    val worker = owner.startCallbacks(ticket, this) {
      kotlinx.coroutines.withContext(Dispatchers.IO) {
        entered.complete(Unit)
        check(stopped.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
      }
    }
    withTimeout(2000) { entered.await() }
    assertTrue(withTimeout(2000) { owner.stopAndRelease(ticket) })
    assertTrue(worker.isCompleted)
    assertEquals(1, fake.releases.get())
  }

  @Test fun runningFailureCannotReleaseWhileCallbackIsReturningFromNative() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopped = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitRunning(handle: Long) = -23
      override fun stop(handle: Long): Int { stopped.countDown(); return super.stop(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, {})
    owner.startCallbacks(ticket, this) {
      kotlinx.coroutines.withContext(Dispatchers.IO) {
        entered.complete(Unit)
        check(stopped.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
      }
    }
    withTimeout(2000) { entered.await() }
    try { owner.awaitRunning(ticket); fail("Expected native failure") }
    catch (failure: CoreArgonFailure) { assertEquals(-23, failure.code.toInt()) }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun staleTicketCannotLaunchCallbacksAgainstReusedNumericHandle() = runBlocking {
    val fake = Fake()
    val owner = CoreArgonLifecycle(fake)
    val old = owner.startBorrowed({ 7L }, {})
    assertTrue(owner.stopAndRelease(old))
    val current = owner.startBorrowed({ 7L }, {})
    val entered = AtomicInteger()
    try { owner.startCallbacks(old, this) { entered.incrementAndGet() }; fail("Stale callback registration") }
    catch (_: IllegalStateException) { }
    assertEquals(0, entered.get())
    assertEquals(3, owner.status())
    assertTrue(owner.stopAndRelease(current))
  }

  @Test fun cancellationInAuthenticationSignalsStopBeforeJoiningBlockedWait() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
        entered.complete(Unit)
        check(permit.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
        return -1
      }
      override fun stop(handle: Long): Int { stopped.complete(Unit); return super.stop(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    val job = launch(Dispatchers.Default) { owner.awaitAuthenticated(ticket) }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { stopped.await() }
      assertFalse(job.isCompleted)
      assertEquals(0, fake.releases.get())
    } finally { permit.countDown(); job.join() }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun cancellationInTerminalWaitStopsBeforeJoiningAndReleasing() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stopped = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitTerminal(handle: Long): Int {
        entered.complete(Unit)
        check(permit.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
        return 4
      }
      override fun stop(handle: Long): Int { stopped.complete(Unit); return super.stop(handle) }
      override fun join(handle: Long): Int { assertEquals(1, exited.get()); return super.join(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    val job = launch(Dispatchers.Default) {
      try { owner.awaitTerminal(ticket); fail("Cancelled terminal must not reach reconnect") }
      finally { owner.stopAndRelease(ticket) }
    }
    try {
      withTimeout(2000) { entered.await() }
      job.cancel()
      withTimeout(2000) { stopped.await() }
      assertFalse(job.isCompleted)
      assertEquals(0, fake.releases.get())
    } finally { permit.countDown(); job.join() }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun terminalWaitAcceptsOnlyLatchedStopOrWorkerEnd() = runBlocking {
    for (result in listOf(4, 6, 0, 3, 5, -3, -90)) {
      val fake = object : Fake() { override fun awaitTerminal(handle: Long) = result }
      val owner = CoreArgonLifecycle(fake)
      val ticket = owner.startBorrowed({ 7L }, { })
      try {
        try {
          assertEquals(result, withTimeout(2000) { owner.awaitTerminal(ticket) })
          assertTrue("Unexpected nonterminal success $result", result == 4 || result == 6)
        } catch (failure: CoreArgonFailure) {
          assertFalse(result == 4 || result == 6)
          assertEquals(result.toLong(), failure.code)
        }
        assertEquals(0, fake.releases.get())
      } finally { assertTrue(owner.stopAndRelease(ticket)) }
    }
  }

  @Test fun terminalWaitCannotObserveReplacementWithReusedNumericHandle() = runBlocking {
    val calls = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitTerminal(handle: Long): Int { calls.incrementAndGet(); return 6 }
    }
    val owner = CoreArgonLifecycle(fake)
    val old = owner.startBorrowed({ 7L }, { })
    assertTrue(owner.stopAndRelease(old))
    val current = owner.startBorrowed({ 7L }, { })
    val stops = fake.stops.get()
    try { owner.awaitTerminal(old); fail("Stale wait") }
    catch (_: IllegalStateException) { }
    assertEquals(0, calls.get())
    assertEquals(stops, fake.stops.get())
    assertEquals(6, owner.awaitTerminal(current))
    assertTrue(owner.stopAndRelease(current))
  }

  @Test fun concurrentCleanupDrainsTerminalLeaseBeforeDisposingNativeHandle() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val stop = CountDownLatch(1)
    val exited = AtomicInteger()
    val fake = object : Fake() {
      override fun awaitTerminal(handle: Long): Int {
        entered.complete(Unit)
        check(stop.await(5, TimeUnit.SECONDS))
        exited.incrementAndGet()
        return 4
      }
      override fun stop(handle: Long): Int { stop.countDown(); return super.stop(handle) }
      override fun join(handle: Long): Int { assertEquals(1, exited.get()); return super.join(handle) }
      override fun release(handle: Long): Int { assertEquals(1, exited.get()); return super.release(handle) }
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    val waiter = launch(Dispatchers.Default) {
      try { owner.awaitTerminal(ticket); fail("Cleaning owner cannot publish terminal") }
      catch (_: IllegalStateException) { /* Cleanup owns the ticket now. */ }
    }
    try {
      withTimeout(2000) {
        entered.await()
        assertTrue(owner.stopAndRelease(ticket))
        waiter.join()
      }
    } finally {
      stop.countDown()
      waiter.cancel()
      waiter.join()
    }
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }

  @Test fun joinFailureRetainsOwnershipAndAllowsCleanupRetry() = runBlocking {
    val fake = Fake().apply { joinCode = -2 }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    assertFalse(owner.stopAndRelease(ticket))
    assertEquals(0, fake.releases.get())
    var closed = 0
    try { owner.startBorrowed({ fail("No replacement while retained"); 8L }, { closed++ }); fail("Retained owner") }
    catch (_: IllegalStateException) { }
    assertEquals(1, closed)
    fake.joinCode = 0
    assertTrue(owner.stopAndRelease(ticket))
    assertNull(owner.status())
  }

  @Test fun staleTicketCannotAttachOrCleanReplacementWithSameNumericHandle() = runBlocking {
    val fake = Fake()
    val owner = CoreArgonLifecycle(fake)
    val old = owner.startBorrowed({ 7L }, { })
    assertTrue(owner.stopAndRelease(old))
    val current = owner.startBorrowed({ 7L }, { })
    val stopsBefore = fake.stops.get()
    var closed = 0
    try { owner.attachBorrowed(old, 8, { closed++ }); fail("Stale attach") }
    catch (_: IllegalStateException) { }
    assertTrue(owner.stopAndRelease(old))
    assertEquals(stopsBefore, fake.stops.get())
    assertEquals(0, fake.attaches.get())
    assertEquals(1, closed)
    owner.attachBorrowed(current, 9, { closed++ })
    assertEquals(1, fake.attaches.get())
    assertEquals(2, closed)
    assertTrue(owner.stopAndRelease(current))
  }

  @Test fun statusRemainsResponsiveDuringCleanupJoin() = runBlocking {
    val entered = CompletableDeferred<Unit>()
    val permit = CountDownLatch(1)
    val fake = object : Fake() {
      override fun join(handle: Long): Int {
        entered.complete(Unit)
        check(permit.await(5, TimeUnit.SECONDS))
        return 0
      }
    }
    val owner = CoreArgonLifecycle(fake)
    owner.startBorrowed({ 7L }, { })
    val cleanup = launch(Dispatchers.Default) { assertTrue(owner.stopAndRelease()) }
    try {
      withTimeout(2000) { entered.await() }
      assertEquals(3, withTimeout(2000) { owner.status() })
      assertFalse(owner.stopAndRelease())
    } finally { permit.countDown(); cleanup.join() }
    assertNull(owner.status())
  }

  @Test fun successfulAdmissionSurvivesThrowingOriginalCloseUntilCleanup() = runBlocking {
    val fake = Fake().apply { joinCode = -2 }
    val owner = CoreArgonLifecycle(fake)
    var closed = 0
    try {
      owner.startBorrowed({ 7L }, { closed++; throw IllegalStateException("close failed") })
      fail("Throwing close")
    } catch (_: IllegalStateException) { }
    assertEquals(1, closed)
    assertEquals(1, fake.stops.get())
    assertEquals(3, owner.status())
    fake.joinCode = 0
    assertTrue(owner.stopAndRelease())
  }

  @Test fun validatesAuthenticatedConfigurationAndRequiresRealRunningWait() = runBlocking {
    val fake = object : Fake() {
      var invalid = false
      override fun awaitAuthenticated(handle: Long, output: IntArray): Int {
        super.awaitAuthenticated(handle, output)
        if (invalid) output[0] = 127
        return 0
      }
      override fun awaitRunning(handle: Long) = -3
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    assertEquals(CoreArgonAuthenticated("10.20.30.40", 1320), owner.awaitAuthenticated(ticket))
    try { owner.awaitRunning(ticket); fail("Running wait rejected") }
    catch (failure: CoreArgonFailure) { assertEquals(-3L, failure.code) }
    assertNull(owner.status())
    fake.invalid = true
    val next = owner.startBorrowed({ 7L }, { })
    try { owner.awaitAuthenticated(next); fail("Loopback assignment rejected") }
    catch (_: IllegalStateException) { }
    assertNull(owner.status())
  }

  @Test fun dnsInstallClosesBorrowedOriginalAndStaleTicketCannotReachReplacement() = runBlocking {
    val fake = Fake()
    val owner = CoreArgonLifecycle(fake)
    val old = owner.startBorrowed({ 7L }, { })
    assertTrue(owner.stopAndRelease(old))
    val current = owner.startBorrowed({ 7L }, { })
    val stops = fake.stops.get()
    var closed = 0
    try { owner.attachRuDnsBorrowed(old, 19) { closed++ }; fail("Stale DNS install") }
    catch (_: IllegalStateException) { }
    assertEquals(1, closed)
    assertEquals(0, fake.dnsAttaches.get())
    assertEquals(stops, fake.stops.get())
    owner.attachRuDnsBorrowed(current, 20) { closed++ }
    assertEquals(2, closed)
    assertEquals(1, fake.dnsAttaches.get())
    assertTrue(owner.stopAndRelease(current))
  }

  @Test fun failedDnsInstallClosesOriginalAndJoinsNativeBeforeRelease() = runBlocking {
    val fake = object : Fake() {
      override fun attachRuDns(handle: Long, borrowedResolverFd: Int) = -3
    }
    val owner = CoreArgonLifecycle(fake)
    val ticket = owner.startBorrowed({ 7L }, { })
    var closed = 0
    try { owner.attachRuDnsBorrowed(ticket, 20) { closed++ }; fail("DNS install rejected") }
    catch (failure: CoreArgonFailure) { assertEquals("attach_dns", failure.operation) }
    assertEquals(1, closed)
    assertEquals(1, fake.stops.get())
    assertEquals(1, fake.releases.get())
    assertNull(owner.status())
  }
}
