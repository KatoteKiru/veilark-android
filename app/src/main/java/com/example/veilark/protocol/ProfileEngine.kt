package com.example.veilark.protocol

import com.example.veilark.vpn.ConnectionState

object ProfileEngine {
  const val SING_BOX = "sing-box"
  const val TRUST_TUNNEL = "trusttunnel"
  const val CORE_BETA = "veilark-core-beta"
}

fun ConnectionState.allowsProfileSwitch(): Boolean =
  this == ConnectionState.Disconnected || this == ConnectionState.Failed
