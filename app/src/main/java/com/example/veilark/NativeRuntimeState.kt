package com.example.veilark

object NativeRuntimeState {
  @Volatile
  var libboxFailure: String? = null
    private set

  @Volatile
  var trustTunnelFailure: String? = null
    private set

  fun recordLibbox(failure: Throwable) {
    libboxFailure = failure.javaClass.simpleName.ifBlank { "NativeError" }
  }

  fun recordTrustTunnel(failure: Throwable) {
    trustTunnelFailure = failure.javaClass.simpleName.ifBlank { "NativeError" }
  }

  fun requireLibbox() {
    check(libboxFailure == null) {
      "Ядро sing-box не загружено (${libboxFailure ?: "unknown"})"
    }
  }

  fun requireTrustTunnel() {
    check(trustTunnelFailure == null) {
      "Ядро TrustTunnel не загружено (${trustTunnelFailure ?: "unknown"})"
    }
  }
}
