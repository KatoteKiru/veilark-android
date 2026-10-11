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
  fun stop(handle: Long): Int
  fun release(handle: Long): Int
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
          check(started > 0) { "Core session could not start" }
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

  /** Bounded off-UI stop/release. On timeout ownership remains held and a new start is forbidden. */
  suspend fun stopAndRelease(maxAttempts: Int = 100): Boolean =
    withContext(Dispatchers.IO + NonCancellable) {
      require(maxAttempts in 1..100) { "Invalid cleanup bound" }
      lock.withLock {
        val current = handle ?: return@withLock true
        api.stop(current)
        repeat(maxAttempts) { attempt ->
          val status = api.status(current)
          if (status == 3 || status < 0) {
            when (api.release(current)) {
              0 -> { handle = null; return@withLock true }
              -2 -> Unit // terminal status can precede worker return
              else -> return@withLock false
            }
          }
          if (attempt + 1 < maxAttempts) pause(50)
        }
        false
      }
    }
}
