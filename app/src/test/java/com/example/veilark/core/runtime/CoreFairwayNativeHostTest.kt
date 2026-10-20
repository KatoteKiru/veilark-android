package com.example.veilark.core.runtime

import java.io.FileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Injected metadata/ownership boundaries only: no JNI, Android sockets or TUN execution. */
class CoreFairwayNativeHostTest {
  private class Api : CoreFairwayNativeApi {
    var configureCalls = 0
    var currentCalls = 0
    var respondCalls = 0
    var stopCalls = 0
    var configureAction: (IntArray, IntArray) -> Int = { _, _ -> 0 }
    var awaitAction: (LongArray) -> Int = { -3 }
    var currentAction: (LongArray) -> Int = { 0 }
    var respondAction: (LongArray, Int) -> Int = { _, _ -> 0 }
    var rejectAction: (LongArray, Int) -> Int = { _, _ -> 0 }
    var stopAction: () -> Int = { 0 }
    override fun configure(handle: Long, epoch: Long, generation: Long,
      networks: IntArray, prefixes: IntArray): Int {
      assertEquals(11L, handle); assertEquals(22L, epoch); assertEquals(33L, generation)
      configureCalls++
      return configureAction(networks, prefixes)
    }
    override fun awaitRequest(handle: Long, output: LongArray): Int {
      assertEquals(11L, handle)
      assertEquals(9, output.size)
      return awaitAction(output)
    }
    override fun current(handle: Long, request: LongArray): Int {
      assertEquals(11L, handle); currentCalls++
      return currentAction(request)
    }
    override fun respond(handle: Long, request: LongArray, fd: Int): Int {
      assertEquals(11L, handle); respondCalls++
      return respondAction(request, fd)
    }
    override fun reject(handle: Long, request: LongArray, cause: Int): Int {
      assertEquals(11L, handle)
      return rejectAction(request, cause)
    }
    override fun stop(handle: Long): Int {
      assertEquals(11L, handle); stopCalls++
      return stopAction()
    }
  }

  private val binding = object : CoreFairwayNetworkBinding {
    override fun isUsable() = true
    override fun bind(fd: FileDescriptor) = error("No socket in this test")
    override fun protect(fd: Int) = error("No socket in this test")
  }
  private fun metadata(id: Long = 44) = longArrayOf(11, 22, 33, id, 0xc000_0201L, 1234, 0xcb00_7101L, 443, 6)
  private fun request(id: Long = 44) = CoreFairwayRequestCodec.decode(metadata(id))
  private fun host(api: Api, current: (Long) -> Boolean = { it == 22L }) =
    CoreFairwayNativeHost(api, 11, CoreFairwayCapturedNetwork(22, binding), 33, current)
  private fun configure(host: CoreFairwayNativeHost) = host.configure(intArrayOf(0xc000_0200L.toInt()), intArrayOf(24))
  private fun rejects(action: () -> Unit) {
    try { action(); fail("Expected boundary rejection") }
    catch (_: IllegalArgumentException) { }
    catch (_: IllegalStateException) { }
  }

  @Test fun configurationIsValidatedDetachedAndOneShot() {
    val api = Api()
    val host = host(api)
    val networks = intArrayOf(0xc000_0200L.toInt())
    val prefixes = intArrayOf(24)
    api.configureAction = { passedNetworks, passedPrefixes ->
      assertNotSame(networks, passedNetworks); assertNotSame(prefixes, passedPrefixes)
      assertArrayEquals(networks, passedNetworks); assertArrayEquals(prefixes, passedPrefixes)
      passedNetworks[0] = 0; passedPrefixes[0] = 0
      0
    }
    host.configure(networks, prefixes)
    assertEquals(0xc000_0200L.toInt(), networks[0]); assertEquals(24, prefixes[0])
    rejects { configure(host) }
    assertEquals(1, api.configureCalls)
    for ((nets, bits) in listOf(IntArray(0) to IntArray(0), intArrayOf(1) to IntArray(0),
      intArrayOf(0xc000_0201L.toInt()) to intArrayOf(24), intArrayOf(0) to intArrayOf(33),
      IntArray(16385) to IntArray(16385))) {
      val invalidApi = Api()
      rejects { host(invalidApi).configure(nets, bits) }
      assertEquals(0, invalidApi.configureCalls)
    }
    val failedApi = Api().also { it.configureAction = { _, _ -> -5 } }
    rejects { configure(host(failedApi)) }
    val stopped = host(Api()).also { it.stopRequests() }
    rejects { configure(stopped) }
  }

  @Test fun awaitStrictlyDecodesScopeAndDistinguishesEofFromFailure() {
    for (slot in listOf(0, 1, 2, 8)) {
      val api = Api().also { fake -> fake.awaitAction = { out ->
        metadata().also { it[slot] = if (slot == 8) 0x1_0000_0006L else 99L }.copyInto(out)
        0
      } }
      val host = host(api).also(::configure)
      rejects { host.awaitRequest() }
    }
    val eof = host(Api()).also(::configure)
    assertNull(eof.awaitRequest())
    val failed = host(Api().also { it.awaitAction = { -5 } }).also(::configure)
    rejects { failed.awaitRequest() }
  }

  @Test fun expiredRequestDoesNotStopAttemptButLostEpochDoes() {
    val api = Api()
    var issued = 0
    api.awaitAction = { out -> metadata((44 + issued++).toLong()).copyInto(out); 0 }
    api.currentAction = { if (it[3] == 44L) -3 else 0 }
    var epochCurrent = true
    val host = host(api) { epochCurrent }.also(::configure)
    assertEquals(45L, host.awaitRequest()!!.flowId)
    assertEquals(2, issued)
    assertEquals(0, api.stopCalls)
    epochCurrent = false
    assertNull(host.awaitRequest())
    assertEquals(1, api.stopCalls)
    assertFalse(host.isCurrent(request(45)))
  }

  @Test fun currentRechecksLocalEpochAfterNativeAndNeverBindsWrongScope() {
    val api = Api()
    var epochCurrent = true
    val host = host(api) { epochCurrent }.also(::configure)
    assertSame(binding, host.network(request()))
    assertNull(host.network(request().copy(networkEpoch = 23)))
    assertFalse(host.isCurrent(request().copy(attemptHandle = 12)))
    val before = api.currentCalls
    api.currentAction = { nativeMetadata ->
      assertArrayEquals(metadata(), nativeMetadata)
      epochCurrent = false
      0
    }
    assertFalse(host.isCurrent(request()))
    assertEquals(before + 1, api.currentCalls)
    assertNull(host.network(request()))
  }

  @Test fun borrowedResponseAndRejectUseExactIdentityAndFenceEpochRaces() {
    val api = Api()
    var epochCurrent = true
    val host = host(api) { epochCurrent }.also(::configure)
    assertTrue(host.deliverBorrowed(request(), -1) < 0)
    assertTrue(host.deliverBorrowed(request().copy(policyGeneration = 34), 7) < 0)
    assertEquals(0, api.respondCalls)
    api.respondAction = { values, fd -> assertArrayEquals(metadata(), values); assertEquals(7, fd); -4 }
    assertEquals(-4, host.deliverBorrowed(request(), 7))
    for (reason in CoreFairwayFdFailureReason.values()) {
      api.rejectAction = { values, cause ->
        assertArrayEquals(metadata(), values); assertEquals(reason.ordinal + 1, cause); 0
      }
      host.rejected(request(), CoreFairwayFdFailure(reason))
    }
    api.rejectAction = { _, _ -> -3 }
    host.rejected(request(), CoreFairwayFdFailure(CoreFairwayFdFailureReason.IO_FAILURE))
    api.rejectAction = { _, _ -> -5 }
    rejects { host.rejected(request(), CoreFairwayFdFailure(CoreFairwayFdFailureReason.IO_FAILURE)) }
    api.respondAction = { _, _ -> epochCurrent = false; 0 }
    assertEquals(-3, host.deliverBorrowed(request(), 7))
    assertEquals(1, api.stopCalls)
  }

  @Test fun stopInvalidatesBeforeNativeWakesWaitAndRunsExactlyOnce() {
    val api = Api()
    val host = host(api).also(::configure)
    val entered = CountDownLatch(1)
    val wake = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    api.awaitAction = { entered.countDown(); check(wake.await(5, TimeUnit.SECONDS)); -3 }
    api.stopAction = {
      assertFalse(host.isCurrent(request()))
      assertNull(host.network(request()))
      wake.countDown()
      -5
    }
    val waiter = Thread {
      try { assertNull(host.awaitRequest()) }
      catch (caught: Throwable) { failure.set(caught) }
      finally { finished.countDown() }
    }
    waiter.start()
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      host.stopRequests()
      host.stopRequests()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertEquals(1, api.stopCalls)
      assertEquals(-5, host.stopResult)
    } finally { wake.countDown(); waiter.join(5_000) }
  }
}
