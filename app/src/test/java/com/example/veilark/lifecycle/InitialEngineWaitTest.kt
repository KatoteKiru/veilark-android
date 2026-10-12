package com.example.veilark.lifecycle

import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InitialEngineWaitTest {
  @Test fun coreRetryWaitRetainsOwnershipPastLegacyDeadlineAndObservesStop() = runBlocking {
    val states = MutableStateFlow(ConnectionState.Connecting)
    val commands = MutableStateFlow(7L)
    val pending = async(start = CoroutineStart.UNDISPATCHED) {
      awaitInitialEngineState(EngineId.CoreBeta, states, commands, 7L, legacyTimeoutMillis = 1L)
    }
    delay(20)
    assertFalse(pending.isCompleted)
    commands.value = 8L // Explicit stop/switch publishes its generation before taking owner mutex.
    assertEquals(ConnectionState.Connecting to 8L, withTimeout(1_000) { pending.await() })
  }

  @Test fun coreInitialWaitExitsOnReadyFatalFailureOrCompletedStop() = runBlocking {
    for (terminal in listOf(ConnectionState.Connected, ConnectionState.Failed, ConnectionState.Disconnected)) {
      val states = MutableStateFlow(ConnectionState.Connecting)
      val commands = MutableStateFlow(7L)
      val pending = async(start = CoroutineStart.UNDISPATCHED) {
        awaitInitialEngineState(EngineId.CoreBeta, states, commands, 7L, legacyTimeoutMillis = 1L)
      }
      states.value = terminal
      assertEquals(terminal to 7L, withTimeout(1_000) { pending.await() })
    }
  }

  @Test fun existingEnginesStillExpireTheirInitialWait() = runBlocking {
    for (engine in listOf(EngineId.SingBox, EngineId.TrustTunnel)) {
      assertNull(awaitInitialEngineState(engine, MutableStateFlow(ConnectionState.Connecting),
        MutableStateFlow(7L), 7L, legacyTimeoutMillis = 1L))
    }
  }
}
