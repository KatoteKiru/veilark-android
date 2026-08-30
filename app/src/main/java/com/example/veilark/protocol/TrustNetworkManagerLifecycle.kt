package com.example.veilark.protocol

internal class TrustNetworkManagerLifecycle {
  private var started = false

  @Synchronized
  fun ensure(start: () -> Unit) {
    if (started) return
    start()
    started = true
  }

  @Synchronized
  fun stop(stop: () -> Unit) {
    if (!started) return
    try {
      stop()
    } finally {
      started = false
    }
  }
}
