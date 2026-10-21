package com.example.veilark.core.runtime

import androidx.annotation.Keep
import android.os.Build
import com.example.veilark.BuildConfig

/** Additive JNI declarations only. No library load or engine activation at class initialization. */
@Keep
object VeilarkCoreRuntimeNative {
  private var loaded = false

  /** Explicit candidate-only loading; it does not start a VPN or change engine selection. */
  @Synchronized fun loadCandidate(): Boolean {
    if (!BuildConfig.CORE_BETA_RUNTIME_BUNDLED || !coreRuntimeAbiSupported(Build.SUPPORTED_ABIS.toList())) return false
    if (loaded) return true
    return try {
      System.loadLibrary("veilark_core_runtime")
      loaded = true
      true
    } catch (_: UnsatisfiedLinkError) { false }
  }
  @JvmStatic external fun nativeStartOriginalIpv4BetaV1(
    tunFd: Int, socketFd: Int, enrollment: ByteArray,
    carrier: ByteArray, initiator: ByteArray, responder: ByteArray,
    sourceIpv4: Int, lifetimeSeconds: Int,
  ): Long
  @JvmStatic external fun nativeStatus(handle: Long): Int
  @JvmStatic external fun nativeFailureV1(handle: Long): Int
  /** Aggregate counts fields0..5; additive numeric rejection fields6..9;
   * field10 is terminal PreAuth category0..7 (negative on older providers).
   * Independent snapshots, no IP addresses/payloads; unavailable is negative. */
  @JvmStatic external fun nativeProgressV1(handle: Long, field: Int): Long
  @JvmStatic external fun nativeStop(handle: Long): Int
  @JvmStatic external fun nativeRelease(handle: Long): Int
}
