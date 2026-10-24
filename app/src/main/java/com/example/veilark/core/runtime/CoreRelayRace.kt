package com.example.veilark.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Picks the relay route for whitelist mode, where the direct exit is blocked
 * and traffic can leave only through legitimate services. Outside that mode
 * the direct path runs alone and nothing here is consulted.
 *
 * Attempts start in provisioning order, one stagger interval apart; a failed
 * attempt starts the next one at once. The first success opens a short settle
 * window so that a slightly slower start does not hide a faster relay, and the
 * fastest success in that window wins, with provisioning order breaking ties.
 * Every other attempt is cancelled, and every session it produced is released,
 * including ones that complete while the race is being torn down.
 *
 * The measured time runs from the start of the attempt to an authenticated
 * session, so it includes the relay allocation as well as the handshake. That
 * is the delay the user waits for, which is what the choice should minimise.
 */
internal class CoreRelayRace(
  private val staggerMillis: Long = 250,
  private val settleMillis: Long = 150,
  private val deadlineMillis: Long = 8_000,
  private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
  class Winner<T, S>(val route: T, val session: S, val elapsedMillis: Long)

  private class Result<T, S>(val index: Int, val route: T, val session: S?, val elapsedMillis: Long)

  /**
   * Null when no candidate reached an authenticated session before the deadline.
   * [release] disposes of every session but the winner; it runs non-cancellably,
   * and a failing release does not stop the others.
   */
  suspend fun <T, S> run(candidates: List<T>, release: suspend (S) -> Unit, attempt: suspend (T) -> S): Winner<T, S>? {
    if (candidates.isEmpty()) return null
    val results = Channel<Result<T, S>>(Channel.UNLIMITED)
    val received = mutableListOf<Result<T, S>>()
    return coroutineScope {
      val jobs = candidates.mapIndexed { index, route ->
        launch(start = CoroutineStart.LAZY) {
          val started = clockMillis()
          val session = try { attempt(route) }
          catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
          // trySend never suspends, so a session created here cannot be lost to
          // a cancellation between the attempt returning and the hand-off.
          results.trySend(Result(index, route, session, clockMillis() - started))
        }
      }
      var chosen: Result<T, S>? = null
      try {
        chosen = withTimeoutOrNull(deadlineMillis) { pick(jobs, results, received) }
        chosen?.let { Winner(it.route, checkNotNull(it.session), it.elapsedMillis) }
      } finally {
        withContext(NonCancellable) {
          jobs.forEach(Job::cancel)
          jobs.forEach { it.join() }
          results.close()
          suspend fun dispose(session: S?) { if (session != null) runCatching { release(session) } }
          for (result in received) if (result !== chosen) dispose(result.session)
          // Attempts that finished after the decision left their sessions here.
          for (result in results) dispose(result.session)
        }
      }
    }
  }

  /** Every result taken from the channel is recorded in [received], so none escapes cleanup. */
  private suspend fun <T, S> pick(
    jobs: List<Job>, results: Channel<Result<T, S>>, received: MutableList<Result<T, S>>,
  ): Result<T, S>? {
    var started = 0
    var best: Result<T, S>? = null
    var settleUntil = Long.MAX_VALUE
    fun startNext() { if (started < jobs.size) jobs[started++].start() }
    startNext()
    while (received.size < started) {
      val wait = when {
        best != null -> settleUntil - clockMillis()
        started < jobs.size -> staggerMillis
        else -> Long.MAX_VALUE
      }
      if (wait <= 0) break
      val result = withTimeoutOrNull(wait) { results.receive() }
      if (result == null) {
        if (best != null) break
        startNext()
        continue
      }
      received += result
      if (result.session == null) {
        if (best == null) startNext()
        continue
      }
      val current = best
      if (current == null) settleUntil = clockMillis() + settleMillis
      if (current == null || result.elapsedMillis < current.elapsedMillis ||
        result.elapsedMillis == current.elapsedMillis && result.index < current.index) best = result
    }
    return best
  }
}
