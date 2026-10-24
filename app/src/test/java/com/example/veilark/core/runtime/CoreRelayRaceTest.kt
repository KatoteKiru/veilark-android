package com.example.veilark.core.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Virtual-time race over fake sessions; no sockets or native handles. */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime
class CoreRelayRaceTest {
  private class Session(val route: String) : AutoCloseable {
    var closed = false
    override fun close() { closed = true }
  }

  private fun TestScope.race() = CoreRelayRace(
    staggerMillis = 250, settleMillis = 150, deadlineMillis = 8_000, clockMillis = { testScheduler.currentTime })

  @Test fun fasterRelayInsideSettleWindowWinsAndTheSlowerSessionIsClosed() = runTest {
    val sessions = mutableListOf<Session>()
    val delays = mapOf("a" to 300L, "b" to 100L)
    val winner = race().run(listOf("a", "b"), { it.close() }) { route ->
      delay(delays.getValue(route)); Session(route).also { sessions += it }
    }
    // a starts at 0 and finishes first, at 300; b starts at 250 and finishes
    // at 350, inside the settle window, after a shorter attempt.
    assertEquals("b", winner?.route)
    assertEquals(100L, winner?.elapsedMillis)
    assertTrue(sessions.single { it.route == "a" }.closed)
    assertFalse(sessions.single { it.route == "b" }.closed)
  }

  @Test fun failureStartsTheNextCandidateWithoutWaitingForTheStagger() = runTest {
    val startedAt = mutableMapOf<String, Long>()
    val winner = race().run(listOf("a", "b"), { it.close() }) { route ->
      startedAt[route] = testScheduler.currentTime
      if (route == "a") { delay(50); error("refused") }
      delay(100); Session(route)
    }
    assertEquals("b", winner?.route)
    assertEquals(50L, startedAt["b"])
  }

  @Test fun deadlineReturnsNoWinnerAndCancelsEveryAttempt() = runTest {
    val cancelled = mutableListOf<String>()
    val winner = race().run(listOf("a", "b"), { it.close() }) { route ->
      try { delay(60_000); Session(route) }
      finally { cancelled += route }
    }
    assertNull(winner)
    assertEquals(8_000L, testScheduler.currentTime)
    assertEquals(setOf("a", "b"), cancelled.toSet())
  }
}
