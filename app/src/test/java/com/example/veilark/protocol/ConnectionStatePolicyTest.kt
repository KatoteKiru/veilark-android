package com.example.veilark.protocol

import com.example.veilark.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatePolicyTest {
  @Test
  fun failedConnectionStillAllowsEngineSwitch() {
    assertTrue(ConnectionState.Disconnected.allowsProfileSwitch())
    assertTrue(ConnectionState.Failed.allowsProfileSwitch())
    assertFalse(ConnectionState.Connecting.allowsProfileSwitch())
    assertFalse(ConnectionState.Connected.allowsProfileSwitch())
  }

  @Test
  fun recoveryKeepsEstablishedTunnelVisuallyConnected() {
    assertEquals(
      ConnectionState.Connected,
      trustRecoveryUiState(wasConnected = true, connectionRequested = true),
    )
    assertEquals(
      ConnectionState.Connecting,
      trustRecoveryUiState(wasConnected = false, connectionRequested = true),
    )
  }
}
