package com.example.veilark.diagnostics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TunnelDiagnosticsTest {
  private class FakeConnection(
    url: URL,
    val response: () -> Int = { 204 },
    val onDisconnect: () -> Unit = {},
  ) : HttpURLConnection(url) {
    val disconnects = AtomicInteger()
    override fun connect() = Unit
    override fun usingProxy() = false
    override fun getResponseCode(): Int = response()
    override fun disconnect() {
      disconnects.incrementAndGet()
      onDisconnect()
    }
  }

  private fun CountDownLatch.awaitBounded() = assertTrue("Latch timed out", await(5, TimeUnit.SECONDS))

  @Test fun cancellationDisconnectsBlockedRequestAndDoesNotStartRemainingEndpoints() = runBlocking {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val opened = AtomicInteger()
    val logs = Collections.synchronizedList(mutableListOf<String>())
    lateinit var connection: FakeConnection
    val job = launch(Dispatchers.Default) {
      TunnelDiagnostics.runChecks({ url ->
        opened.incrementAndGet()
        FakeConnection(url, response = {
          started.countDown()
          try { release.awaitBounded(); throw IOException("disconnected") }
          finally { finished.countDown() }
        }, onDisconnect = { release.countDown() }).also { connection = it }
      }, { 0L }, { _, message -> logs.add(message) })
    }
    started.awaitBounded()
    try {
      withTimeout(5_000) { job.cancelAndJoin() }
      finished.awaitBounded()
      assertTrue(job.isCancelled)
      assertEquals(1, opened.get())
      assertEquals(1, connection.disconnects.get())
      assertFalse(logs.any { "failed=" in it || "Check completed" in it })
    } finally {
      release.countDown()
      job.cancelAndJoin()
    }
  }

  @Test fun cancellationWhileCreatingConnectionCleansUpWithoutStartingResponse() = runBlocking {
    val creating = CountDownLatch(1)
    val release = CountDownLatch(1)
    val responses = AtomicInteger()
    val opened = AtomicInteger()
    lateinit var connection: FakeConnection
    val job = launch(Dispatchers.Default) {
      TunnelDiagnostics.runChecks({ url ->
        opened.incrementAndGet()
        creating.countDown()
        release.awaitBounded()
        FakeConnection(url, response = { responses.incrementAndGet(); 204 }).also { connection = it }
      }, { 0L }, { _, _ -> })
    }
    creating.awaitBounded()
    job.cancel()
    release.countDown()
    withTimeout(5_000) { job.join() }
    assertEquals(1, opened.get())
    assertEquals(0, responses.get())
    assertEquals(1, connection.disconnects.get())
  }

  @Test fun cancelledBeforeStartDoesNotOpenConnections() = runBlocking {
    val opened = AtomicInteger()
    val job = launch(start = CoroutineStart.LAZY) {
      TunnelDiagnostics.runChecks({ url -> opened.incrementAndGet(); FakeConnection(url) }, { 0L }, { _, _ -> })
    }
    job.cancelAndJoin()
    assertEquals(0, opened.get())
  }

  @Test fun successfulRunKeepsFiveEndpointsAndRequestPolicy() = runBlocking {
    val connections = mutableListOf<FakeConnection>()
    val logs = mutableListOf<String>()
    TunnelDiagnostics.runChecks({ url -> FakeConnection(url).also { connections.add(it) } },
      { 0L }, { _, message -> logs.add(message) })
    assertEquals(5, connections.size)
    connections.forEach {
      assertEquals(1, it.disconnects.get())
      assertEquals(10_000, it.connectTimeout)
      assertEquals(10_000, it.readTimeout)
      assertFalse(it.instanceFollowRedirects)
      assertFalse(it.useCaches)
      assertEquals("Veilark-Diagnostics/0.3", it.getRequestProperty("User-Agent"))
      assertEquals("https", it.url.protocol)
    }
    assertEquals("Check completed: all 5 services responded", logs.last())
  }

  @Test fun ordinaryFailureStillChecksRemainingServicesAndReleasesEveryConnection() = runBlocking {
    val connections = mutableListOf<FakeConnection>()
    val logs = mutableListOf<String>()
    TunnelDiagnostics.runChecks({ url ->
      val first = connections.isEmpty()
      FakeConnection(url, response = { if (first) throw IOException("private detail"); 204 })
        .also { connections.add(it) }
    }, { 0L }, { _, message -> logs.add(message) })
    assertEquals(5, connections.size)
    assertTrue(connections.all { it.disconnects.get() == 1 })
    assertTrue(logs.any { "failed=IOException" in it })
    assertFalse(logs.any { "private detail" in it })
    assertEquals("Check completed: 4 of 5 services responded", logs.last())
  }

  @Test fun explicitCancellationExceptionIsNotLoggedOrSwallowed() = runBlocking {
    val opened = AtomicInteger()
    val logs = mutableListOf<String>()
    try {
      TunnelDiagnostics.runChecks({ url ->
        opened.incrementAndGet()
        FakeConnection(url, response = { throw CancellationException("stop") })
      }, { 0L }, { _, message -> logs.add(message) })
      fail("Cancellation must propagate")
    } catch (_: CancellationException) {
      assertEquals(1, opened.get())
      assertFalse(logs.any { "failed=" in it || "Check completed" in it })
    }
  }
}
