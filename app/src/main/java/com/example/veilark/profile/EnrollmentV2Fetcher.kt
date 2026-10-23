package com.example.veilark.profile

import java.net.CookieHandler
import java.net.URL
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.coroutineContext

/** Unactivated fixed-origin transport; never authenticates an issuer or stores a profile.
 * Caller owns returned ciphertext. A blocked resolver/provider cannot be forcibly stopped:
 * caller wait is bounded, and the process stays fail-busy until real IO/cleanup ends.
 */
internal object EnrollmentV2Fetcher {
  private const val ERROR = "Protected enrollment download failed."
  private const val MAX_BYTES = 65_536
  private const val ORIGIN = "https://nl2.senyasenyavski.uk:2096/veilark/"
  private val LINK = Regex("veilark://enroll/v2\\?t=([A-Za-z0-9_-]{43})#k=([A-Za-z0-9_-]{43})")
  private val busy = AtomicBoolean()
  // One network job and at most one disconnect job; both threads expire when idle.
  // Bounded queue permits the tiny handoff interval before a previous runnable returns.
  private fun executor(name: String) = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
    LinkedBlockingQueue<Runnable>(1), { task -> Thread(task, name).apply { isDaemon = true } })
  private val io = executor("enrollment-fetch")
  private val cleanup = executor("enrollment-disconnect")

  suspend fun fetch(link: String): ByteArray = fetchWithTransport(link,
    { url -> url.openConnection() as HttpsURLConnection }, Limits())

  internal fun isCanonicalLink(link: String): Boolean =
    try { deliveryUrl(link); true } catch (_: Exception) { false }

  internal class Limits(val connectMillis: Int = 5_000, val readMillis: Int = 5_000,
    val overallMillis: Int = 15_000)

  /** Internal test transport only. No alternate origin, TLS verifier, or trust-map API. */
  internal suspend fun fetchWithTransport(link: String, open: (URL) -> HttpsURLConnection,
    limits: Limits = Limits(), nanoTime: () -> Long = System::nanoTime): ByteArray {
    coroutineContext.ensureActive()
    var request: Request? = null
    var owned: ByteArray? = null
    try {
      check(listOf(limits.connectMillis, limits.readMillis, limits.overallMillis).all { it in 1..60_000 })
      val started = nanoTime()
      val url = deliveryUrl(link)
      noAmbientCredentials()
      check(busy.compareAndSet(false, true))
      val work = Request(url, open, limits, started, nanoTime)
      request = work
      // Plain Runnable, NOT FutureTask: cancel-before-start must still execute finally.
      io.execute { work.run() }
      runInterruptible(Dispatchers.IO) {
        work.result.get(work.remainingNanos(), TimeUnit.NANOSECONDS)
        owned = work.takeResult()
      }
      coroutineContext.ensureActive()
      return checkNotNull(owned).also { owned = null }
    } catch (failure: CancellationException) {
      request?.cancel()
      throw failure
    } catch (_: Exception) {
      request?.cancel()
      throw IllegalArgumentException(ERROR)
    } finally { owned?.fill(0) }
  }

  private class Request(val url: URL, val open: (URL) -> HttpsURLConnection,
    val limits: Limits, val started: Long, val nanoTime: () -> Long) {
    val result = CompletableFuture<Unit>()
    private val state = Any()
    private var cancelled = false
    private var connection: HttpsURLConnection? = null
    private var thread: Thread? = null
    private var bytes: ByteArray? = null
    private var workerDone = false
    private var cleanupScheduled = false
    private var cleanupDone = true
    private var released = false

    fun remainingNanos(): Long = (TimeUnit.MILLISECONDS.toNanos(limits.overallMillis.toLong()) -
      (nanoTime() - started)).also { check(it > 0) }
    private fun timeout(max: Int): Int = minOf(max.toLong(),
      (remainingNanos() + 999_999) / 1_000_000).toInt().coerceAtLeast(1)
    private fun active() { synchronized(state) { check(!cancelled) }; remainingNanos() }

    fun takeResult(): ByteArray = synchronized(state) {
      check(!cancelled)
      checkNotNull(bytes).also { bytes = null }
    }
    fun cancel() {
      synchronized(state) {
        cancelled = true
        bytes?.fill(0); bytes = null
        result.cancel(false)
        // Under state lock: cannot interrupt a thread already reused by a later job.
        if (!workerDone) thread?.interrupt()
      }
      disconnectLater()
    }
    fun run() {
      var output: ByteArray? = null
      try {
        synchronized(state) { thread = Thread.currentThread() }
        active(); noAmbientCredentials()
        val connected = open(url)
        synchronized(state) { connection = connected }
        active(); noAmbientCredentials()
        connected.instanceFollowRedirects = false
        connected.useCaches = false
        connected.doInput = true
        connected.doOutput = false
        connected.requestMethod = "GET"
        connected.connectTimeout = timeout(limits.connectMillis)
        connected.readTimeout = timeout(limits.readMillis)
        connected.setRequestProperty("Accept", "application/jose")
        connected.setRequestProperty("Accept-Encoding", "identity")
        connected.setRequestProperty("Cache-Control", "no-store")
        for (header in listOf("Cookie", "Cookie2", "Authorization", "Proxy-Authorization", "Referer"))
          connected.setRequestProperty(header, "")
        active(); noAmbientCredentials()
        check(connected.responseCode == 200)
        active()
        val encoding = connected.getHeaderField("Content-Encoding")
        check(encoding == null || encoding.equals("identity", ignoreCase = true))
        check(connected.contentLengthLong <= MAX_BYTES)
        val buffer = ByteArray(MAX_BYTES)
        val chunk = ByteArray(4096)
        try {
          var count = 0
          connected.inputStream.use { input ->
            while (true) {
              active()
              connected.readTimeout = timeout(limits.readMillis)
              val length = input.read(chunk, 0, minOf(chunk.size, MAX_BYTES - count + 1))
              active() // Includes the last read/EOF, not just the next iteration.
              if (length < 0) break
              check(length > 0 && count + length <= MAX_BYTES)
              check((0 until length).all { asciiJwe(chunk[it]) })
              chunk.copyInto(buffer, count, 0, length)
              count += length
            }
          }
          check(count > 0)
          val text = String(buffer, 0, count, Charsets.US_ASCII).split('.', limit = 6)
          check(text.size == 5 && text[1].isEmpty() && listOf(0, 2, 3, 4).all { text[it].isNotEmpty() })
          output = buffer.copyOf(count)
        } finally { buffer.fill(0); chunk.fill(0) }
        synchronized(state) {
          if (!cancelled) { bytes = output; output = null; result.complete(Unit) }
        }
      } catch (_: Exception) {
        result.completeExceptionally(IllegalArgumentException(ERROR))
      } finally {
        output?.fill(0)
        disconnectLater()
        synchronized(state) { workerDone = true; thread = null; releaseIfFinished() }
        // Do not leak an interrupt flag to a subsequent task on this bounded worker.
        Thread.interrupted()
      }
    }
    private fun disconnectLater() {
      val connected = synchronized(state) {
        if (connection == null || cleanupScheduled) null else {
          cleanupScheduled = true; cleanupDone = false; connection
        }
      } ?: return
      cleanup.execute {
        try { connected.disconnect() } catch (_: Exception) { /* bounded error, no provider details */ }
        finally { synchronized(state) { connection = null; cleanupDone = true; releaseIfFinished() } }
      }
    }
    private fun releaseIfFinished() {
      if (workerDone && cleanupDone && !released) { released = true; busy.set(false) }
    }
  }

  private fun noAmbientCredentials() {
    // Cooperative app-process guard, not isolation from hostile code changing globals.
    // Android has no supported Authenticator getter. This app installs no VM-wide
    // Authenticator; introducing one requires an isolated HTTP client before use here.
    // Blank auth headers are defense in depth, not independent authenticator isolation.
    check(CookieHandler.getDefault() == null)
  }
  private fun deliveryUrl(link: String): URL {
    check(link.length <= 160 && link.all { it.code in 0..127 })
    val match = LINK.matchEntire(link) ?: error(ERROR)
    val locator = Base64.getUrlDecoder().decode(match.groupValues[1])
    var key: ByteArray? = null
    try {
      key = Base64.getUrlDecoder().decode(match.groupValues[2])
      for ((bytes, encoded) in listOf(locator to match.groupValues[1], key to match.groupValues[2])) {
        check(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
      }
      val hex = locator.joinToString("") { "%02x".format(it.toInt() and 255) }
      return URL(ORIGIN + hex + ".jwe")
    } finally { locator.fill(0); key?.fill(0) }
  }
  private fun asciiJwe(byte: Byte): Boolean = byte.toInt().let {
    it in 65..90 || it in 97..122 || it in 48..57 || it == 95 || it == 45 || it == 46
  }
}
