package com.example.veilark.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Inject only a verified native artifact. This seam is not registered as a VPN engine. */
internal interface CoreBetaHandleApi {
  fun status(handle: Long): Int
  fun failure(handle: Long): Int = 0
  fun progress(handle: Long, field: Int): Long = -4
  fun stop(handle: Long): Int
  fun release(handle: Long): Int
}

internal class CoreBetaStartFailure(val code: Long) : IllegalStateException("Core session could not start")

/** Terminal values captured before release; only an explicitly requested rotation may use them. */
internal data class CoreBetaReleaseOutcome(
  val released: Boolean,
  val terminalStatus: Int? = null,
  val terminalReason: Int? = null,
) {
  val allowsPlannedRotation: Boolean
    get() = released && terminalStatus == 3 && terminalReason == 13
}

/** Keeps ownership through terminal-but-still-running workers; never falls back to another engine. */
internal class CoreBetaLifecycle(
  private val api: CoreBetaHandleApi,
  private val pause: suspend (Long) -> Unit = { delay(it) },
) {
  private val lock = Mutex()
  private var handle: Long? = null

  /** Caller provides protected, connected NONBLOCK socket/TUN; originals remain caller-owned. */
  suspend fun startBorrowed(start: () -> Long, closeOriginals: () -> Unit): Long = try {
    val result = withContext(Dispatchers.IO + NonCancellable) {
      lock.withLock {
        try {
          check(handle == null) { "Previous Core session has not been released" }
          val started = start()
          if (started <= 0) throw CoreBetaStartFailure(started)
          handle = started
          started
        } finally {
          // Native owns duplicates only. Also close originals on negative return/exception.
          closeOriginals()
        }
      }
    }
    currentCoroutineContext().ensureActive()
    result
  } catch (cancelled: CancellationException) {
    // Cancellation can arrive while JNI creates a handle, or when its result
    // crosses back to the cancelled caller. Preserve/clean ownership in either case.
    stopAndRelease()
    throw cancelled
  }

  /** Ready means authenticated admission, not public internet/DNS acceptance. */
  suspend fun isAuthenticatedReady(): Boolean = withContext(Dispatchers.IO) {
    lock.withLock { handle?.let { api.status(it) == 1 } ?: false }
  }

  suspend fun status(): Int? = withContext(Dispatchers.IO) {
    lock.withLock { handle?.let { api.status(it) } }
  }

  /** Capture payload-free terminal diagnostics while this owner still holds the handle. */
  suspend fun failure(): Int? = withContext(Dispatchers.IO) {
    lock.withLock { handle?.let { api.failure(it) } }
  }

  /** Read only FAILED/PreAuth while owning the handle; old providers return unsupported. */
  suspend fun preAuthFailureDetail(): CorePreAuthDetail? = withContext(Dispatchers.IO) {
    lock.withLock {
      handle?.let { current ->
        if (api.status(current) >= 0 || api.failure(current) != 11) null
        else CorePreAuthDetail.fromNative(api.progress(current, 10))
      }
    }
  }

  /** Query under the same ownership lock as status/release; never exposes packet contents. */
  suspend fun progress(): LongArray? = withContext(Dispatchers.IO) {
    lock.withLock {
      handle?.let { current ->
        val counts = LongArray(10) { field -> api.progress(current, field) }
        // Fields 5..9 are additive: old providers may return a negative value
        // for those fields. Preserve their original five counters in that case.
        if (counts.take(5).any { it < 0 }) null
        else if (counts.drop(5).any { it < 0 }) counts.copyOf(5) else counts
      }
    }
  }

  /** Bounded off-UI stop/release. On timeout ownership remains held and a new start is forbidden. */
  suspend fun stopAndRelease(maxAttempts: Int = 100): Boolean = cleanup(maxAttempts, false).released

  /** An own timer decision is not enough: retain a racing native failure before release. */
  suspend fun stopAndReleaseForRotation(): CoreBetaReleaseOutcome = cleanup(100, true)

  private suspend fun cleanup(maxAttempts: Int, captureTerminal: Boolean): CoreBetaReleaseOutcome =
    withContext(Dispatchers.IO + NonCancellable) {
      require(maxAttempts in 1..100) { "Invalid cleanup bound" }
      lock.withLock {
        val current = handle ?: return@withLock CoreBetaReleaseOutcome(true)
        api.stop(current)
        repeat(maxAttempts) { attempt ->
          val status = api.status(current)
          if (status == 3 || status < 0) {
            val reason = if (captureTerminal) api.failure(current) else null
            when (api.release(current)) {
              0 -> { handle = null; return@withLock CoreBetaReleaseOutcome(true, status, reason) }
              -2 -> Unit // terminal status can precede worker return
              else -> return@withLock CoreBetaReleaseOutcome(false, status, reason)
            }
          }
          if (attempt + 1 < maxAttempts) pause(50)
        }
        CoreBetaReleaseOutcome(false)
      }
    }
}
