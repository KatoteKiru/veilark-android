package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

class CoreBetaStartCommandsTest {
  @Test fun stopWhileAdmissionIsPendingCannotSubmitLateStart() {
    val commands = CoreBetaStartCommands()
    val pending = commands.begin()
    var events = ""
    commands.stop { events += "stop" }
    assertFalse(commands.ifCurrent(pending) { events += "start" })
    assertEquals("stop", events)
    val next = commands.begin()
    assertTrue(commands.ifCurrent(next) { events += "+start" })
    assertEquals("stop+start", events)
  }

  @Test fun newerStartSupersedesOlderPreflight() {
    val commands = CoreBetaStartCommands()
    val first = commands.begin()
    val second = commands.begin()
    assertFalse(commands.ifCurrent(first) { fail("Old preflight must not launch") })
    assertTrue(commands.ifCurrent(second) { })
  }

  @Test fun profileMutationInvalidatesPendingStartAndKeepsAdmissionPaused() {
    val commands = CoreBetaStartCommands()
    val pending = commands.begin()
    commands.pauseWhen { true }
    assertFalse(commands.ifCurrent(pending) { fail("Deleted identity must not launch") })
    try { commands.begin(); fail("Mutation reservation") } catch (_: IllegalStateException) { }
    commands.stop { } // Stop remains responsive while disk work owns the reservation.
    commands.resume()
    assertTrue(commands.ifCurrent(commands.begin()) { })
  }

  @Test fun activeSessionRejectsProfileMutationWithoutInvalidatingStart() {
    val commands = CoreBetaStartCommands()
    val current = commands.begin()
    try { commands.pauseWhen { false }; fail("Live owner") } catch (_: IllegalStateException) { }
    assertTrue(commands.ifCurrent(current) { })
  }
}
