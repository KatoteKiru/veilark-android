package com.example.veilark.ui.main

import com.example.veilark.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionCardActionTest {
  @Test fun heldCoreLeaseAlwaysAllowsCancellationWithoutChangingFailureState() {
    for (state in listOf(ConnectionState.Failed, ConnectionState.Disconnected, ConnectionState.Connecting)) {
      for (hasProfile in listOf(false, true)) {
        for (importing in listOf(false, true)) {
          assertEquals(ConnectionCardAction.Cancel, connectionCardAction(state, hasProfile, importing, stopAvailable = true))
        }
      }
    }
    assertEquals(ConnectionCardAction.Disconnect, connectionCardAction(ConnectionState.Connected, false, true, true))
  }

  @Test fun legacyActionsKeepTheirExistingImportAndConnectionPriority() {
    assertEquals(ConnectionCardAction.Checking, connectionCardAction(ConnectionState.Failed, false, true))
    assertEquals(ConnectionCardAction.Add, connectionCardAction(ConnectionState.Disconnected, false, false))
    assertEquals(ConnectionCardAction.Connect, connectionCardAction(ConnectionState.Failed, true, false))
    assertEquals(ConnectionCardAction.Cancel, connectionCardAction(ConnectionState.Connecting, false, true))
    assertEquals(ConnectionCardAction.Disconnect, connectionCardAction(ConnectionState.Connected, false, true))
  }
}
