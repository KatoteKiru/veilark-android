package com.example.veilark.profile

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.cert.Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Offline HTTPS-boundary tests; no real URL connection or private profiles. */
class EnrollmentV2FetcherTest {
  private val locator = b64(ByteArray(32) { (it + 64).toByte() })
  private val key = b64(ByteArray(32) { (it + 32).toByte() })
  private val link = "veilark://enroll/v2?t=$locator#k=$key"
  private val wire = "a..b.c.d".toByteArray(Charsets.US_ASCII)

  @Test fun fixedMappingAndHeadersNeverSendFragmentOrLinkSecrets() = runBlocking {
    val connection = Fake(wire)
    val output = perform(connection)
    assertArrayEquals(wire, output)
    assertEquals("https://nl2.senyasenyavski.uk:2096/veilark/404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f.jwe", connection.openedUrl.toString())
    assertNull(connection.openedUrl!!.query); assertNull(connection.openedUrl!!.ref)
    assertFalse(connection.openedUrl.toString().contains(key))
    assertFalse(connection.openedUrl.toString().contains(locator))
    assertEquals("GET", connection.requestMethod)
    assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
    assertFalse(connection.doOutput); assertTrue(connection.doInput)
    assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
    assertEquals("no-store", connection.getRequestProperty("Cache-Control"))
    for (header in listOf("Cookie", "Cookie2", "Authorization", "Proxy-Authorization", "Referer"))
      assertEquals("", connection.getRequestProperty(header))
    assertTrue(connection.connectTimeout in 1..5_000)
    assertTrue(connection.readTimeout in 1..5_000)
    assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
  }

  @Test fun malformedOrNoncanonicalLinksNeverReachTransport() = runBlocking {
    for (bad in listOf("$link&extra=1", link.replace("?t=", "?other="), link.replace("#k=", "#k=%"),
      link.replace("veilark://", "https://"), link.replace("enroll/v2", "enroll/v3"),
      link.replace("?t=", "?t=$locator&t="), link.substringBefore('#'), link.dropLast(1) + "9", " $link")) {
      val calls = AtomicInteger()
      failure { EnrollmentV2Fetcher.fetchWithTransport(bad, { calls.incrementAndGet(); Fake(wire) }) }
      assertEquals(0, calls.get())
    }
  }

  @Test fun redirectsCompressedResponsesAndOversizeHeadersRejectWithoutReadingBody() = runBlocking {
    for (connection in listOf(Fake(wire, status = 302), Fake(wire, status = 401),
      Fake(wire, encoding = "gzip"), Fake(wire, declaredLength = 65_537))) {
      failure { perform(connection) }
      assertEquals(0, connection.inputOpens.get())
      assertEquals(1, connection.opens.get())
    }
    failure { perform(Fake(wire).apply { responseFailure = IllegalStateException(link) }) }
  }

  @Test fun streamingCapIsExactAndRejectsNonAsciiOrNonJweData() = runBlocking {
    val maximum = ("a..b." + "c".repeat(65_529) + ".d").toByteArray()
    assertEquals(65_536, maximum.size)
    assertArrayEquals(maximum, perform(Fake(maximum)))
    for (body in listOf(maximum + 'c'.code.toByte(), byteArrayOf(0xff.toByte()),
      "a..b.c.d\n".toByteArray(), "plaintext".toByteArray(), ByteArray(0)))
      failure { perform(Fake(body)) }
  }

  @Test fun ambientCookieConfigurationFailsClosed() = runBlocking {
    val cookies = CookieHandler.getDefault()
    try {
      val calls = AtomicInteger()
      CookieHandler.setDefault(CookieManager())
      failure { EnrollmentV2Fetcher.fetchWithTransport(link, { calls.incrementAndGet(); Fake(wire) }) }
      assertEquals(0, calls.get())
    } finally { CookieHandler.setDefault(cookies) }
  }

  @Test fun tricklingReadsCannotExtendOverallDeadline() = runBlocking {
    val clock = AtomicLong()
    val reads = AtomicInteger()
    val input = object : InputStream() {
      override fun read(): Int = error("bulk reads only")
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        reads.incrementAndGet(); clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(150))
        bytes[offset] = 'a'.code.toByte(); return 1
      }
    }
    val connection = Fake(wire, stream = input)
    failure { perform(connection, EnrollmentV2Fetcher.Limits(250, 250, 500), clock::get) }
    assertTrue(reads.get() in 1..4)
    assertTrue(connection.readTimeout in 1..250)
  }

  @Test fun blockedOpenHasCallerDeadlineAndRetainsSlotUntilActualWorkerEnds() = runBlocking {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val connection = Fake(wire)
    val started = System.nanoTime()
    try {
      failure { performOpen({
        entered.countDown(); awaitUninterruptibly(release); connection
      }, EnrollmentV2Fetcher.Limits(50, 50, 100)) }
      assertTrue(entered.await(1, TimeUnit.SECONDS))
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
      val otherOpens = AtomicInteger()
      failure { EnrollmentV2Fetcher.fetchWithTransport(link, { otherOpens.incrementAndGet(); Fake(wire) }) }
      assertEquals(0, otherOpens.get())
    } finally { release.countDown() }
    assertArrayEquals(wire, perform(Fake(wire)))
    assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
    assertEquals(0, connection.inputOpens.get())
  }

  @Test fun blockedDisconnectDoesNotAccumulateCleanupOrReleaseSingleFlightEarly() = runBlocking {
    val release = CountDownLatch(1)
    val connection = Fake(wire).apply { disconnectBlock = release }
    try {
      assertArrayEquals(wire, perform(connection))
      assertTrue(connection.disconnectEntered.await(2, TimeUnit.SECONDS))
      val calls = AtomicInteger()
      repeat(3) {
        failure { EnrollmentV2Fetcher.fetchWithTransport(link, { calls.incrementAndGet(); Fake(wire) }) }
      }
      assertEquals(0, calls.get()); assertEquals(1, connection.disconnects.get())
    } finally { release.countDown() }
    assertArrayEquals(wire, perform(Fake(wire)))
  }

  @Test fun cancellationDisconnectsBlockedInputAndPreservesCoroutineCancellation() = runBlocking {
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    val connection = Fake(wire, stream = object : InputStream() {
      override fun read(): Int = error("bulk reads only")
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        entered.countDown(); awaitUninterruptibly(release); return -1
      }
    }).apply { disconnectAction = { release.countDown() } }
    val delivered = AtomicReference<ByteArray?>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) { delivered.set(perform(connection)) }
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS))
      withTimeout(2_000) { job.cancelAndJoin() }
      assertTrue(job.isCancelled); assertNull(delivered.get())
      assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
    } finally { release.countDown(); job.cancelAndJoin() }
    assertArrayEquals(wire, perform(Fake(wire)))
  }

  @Test fun cancellationAfterWorkerCompletionDoesNotPublishUnownedResult() = runBlocking {
    val respond = CountDownLatch(1)
    val connection = Fake(wire).apply { responseGate = respond }
    val delivered = AtomicReference<ByteArray?>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) { delivered.set(perform(connection)) }
    // The gate keeps the worker from finishing before the caller suspends; an
    // instant worker would let the IO hop return without suspending at all.
    // Then block this event loop until worker completion. The queued continuation
    // must observe cancellation instead of publishing the result to the caller.
    respond.countDown()
    assertTrue(connection.disconnected.await(2, TimeUnit.SECONDS))
    withTimeout(2_000) { job.cancelAndJoin() }
    assertTrue(job.isCancelled); assertNull(delivered.get())
    assertArrayEquals(wire, perform(Fake(wire)))
  }

  /** Retry only a fail-busy gate (factory was NOT invoked). No transport retry/fallback. */
  private suspend fun perform(connection: Fake, limits: EnrollmentV2Fetcher.Limits = EnrollmentV2Fetcher.Limits(),
    nanoTime: () -> Long = System::nanoTime): ByteArray = performOpen({ url ->
      connection.opens.incrementAndGet(); connection.openedUrl = url; connection
    }, limits, nanoTime)

  private suspend fun performOpen(open: (URL) -> HttpsURLConnection,
    limits: EnrollmentV2Fetcher.Limits = EnrollmentV2Fetcher.Limits(),
    nanoTime: () -> Long = System::nanoTime): ByteArray {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    val opened = AtomicBoolean()
    while (true) {
      try {
        return EnrollmentV2Fetcher.fetchWithTransport(link, { url ->
          opened.set(true); open(url)
        }, limits, nanoTime)
      } catch (failure: IllegalArgumentException) {
        if (opened.get() || System.nanoTime() >= until) throw failure
        Thread.yield()
      }
    }
  }
  private suspend fun failure(action: suspend () -> Unit) {
    try { action(); fail("Expected bounded transport failure") }
    catch (failure: IllegalArgumentException) {
      assertEquals("Protected enrollment download failed.", failure.message); assertNull(failure.cause)
    }
  }
  private class Fake(body: ByteArray, private val status: Int = 200, private val encoding: String? = null,
    private val declaredLength: Long = -1, private val stream: InputStream = ByteArrayInputStream(body)) :
    HttpsURLConnection(URL("https://fixture.invalid/")) {
    val opens = AtomicInteger(); val inputOpens = AtomicInteger(); val disconnects = AtomicInteger()
    val disconnectEntered = CountDownLatch(1); val disconnected = CountDownLatch(1)
    var openedUrl: URL? = null
    var responseFailure: Exception? = null
    var responseGate: CountDownLatch? = null
    var disconnectBlock: CountDownLatch? = null
    var disconnectAction: (() -> Unit)? = null
    override fun getResponseCode(): Int {
      responseGate?.let { awaitUninterruptibly(it) }
      responseFailure?.let { throw it }; return status
    }
    override fun getHeaderField(name: String?): String? = if (name == "Content-Encoding") encoding else null
    override fun getContentLengthLong(): Long = declaredLength
    override fun getInputStream(): InputStream { inputOpens.incrementAndGet(); return stream }
    override fun disconnect() {
      disconnects.incrementAndGet(); disconnectEntered.countDown()
      disconnectBlock?.let { awaitUninterruptibly(it) }; disconnectAction?.invoke(); disconnected.countDown()
    }
    override fun connect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getCipherSuite(): String = "SYNTHETIC"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
  }
  companion object {
    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun awaitUninterruptibly(latch: CountDownLatch) {
      while (true) try { latch.await(); return } catch (_: InterruptedException) { /* model blocking provider */ }
    }
  }
}
