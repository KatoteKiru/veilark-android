package com.example.veilark.protocol

import com.example.veilark.vpn.ConnectionState

object ProfileEngine {
  const val SING_BOX = "sing-box"
  const val TRUST_TUNNEL = "trusttunnel"
}

fun ConnectionState.allowsProfileSwitch(): Boolean =
  this == ConnectionState.Disconnected || this == ConnectionState.Failed
