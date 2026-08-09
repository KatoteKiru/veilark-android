package com.example.veilark.protocol

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.StateFlow

/**
 * Stable boundary between Android's VPN lifecycle and replaceable protocol cores.
 * Implementations must never log credentials or private keys.
 */
interface TunnelEngine {
  val protocol: Protocol
  val state: StateFlow<EngineState>

  suspend fun probe(profile: TunnelProfile): ProbeReport
  suspend fun start(tun: ParcelFileDescriptor, profile: TunnelProfile)
  suspend fun stop()
}

data class TunnelProfile(
  val id: String,
  val displayName: String,
  val protocol: Protocol,
  val endpoint: String,
  val port: Int,
  val secretReference: String,
  val mtu: Int = 1320,
)

sealed interface EngineState {
  data object Idle : EngineState
  data class Probing(val stage: ProbeStage) : EngineState
  data object Connecting : EngineState
  data object Connected : EngineState
  data class Failed(val code: String, val safeMessage: String) : EngineState
}

enum class ProbeStage {
  Dns,
  Socket,
  Tls,
  Authentication,
  Tunnel,
  Internet,
}

data class ProbeReport(
  val successful: Boolean,
  val stages: Map<ProbeStage, Long>,
  val selectedMtu: Int,
  val safeFailureCode: String? = null,
)
