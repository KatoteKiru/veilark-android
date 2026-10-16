package com.example.veilark.core.runtime

/** Additive terminal field10; fixed categories only, never native exception text. */
internal enum class CorePreAuthDetail(val code: Int) {
  UNKNOWN(0), CONFIGURATION(1), FENCED(2), TIMEOUT(3), IO(4), HANDSHAKE(5), CANCELLED(6), NOT_READY(7);

  companion object {
    fun fromNative(value: Long): CorePreAuthDetail? =
      entries.firstOrNull { it.code.toLong() == value }
  }
}
