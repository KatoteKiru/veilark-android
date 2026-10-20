package com.example.veilark.core.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.Inet4Address
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Protected Argon carrier/resolver creation; UDP connect is not authentication or DNS readiness.
 * Caller owns the result and must close it, including cancellation after return.
 * Native may borrow it only if it synchronously duplicates it before returning.
 * This helper sends no packets and is not connected to the legacy TCP JNI.
 */
internal object CoreProtectedUdp {
  suspend fun open(
    peer: Inet4Address,
    port: Int,
    protect: (Int) -> Boolean,
    binding: CoreFairwayNetworkBinding? = null,
  ): ParcelFileDescriptor {
    require(port in 1..65535)
    currentCoroutineContext().ensureActive()
    val raw = Os.socket(
      OsConstants.AF_INET,
      OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
      OsConstants.IPPROTO_UDP,
    )
    var owned: ParcelFileDescriptor? = null
    var transferred = false
    try {
      // ParcelFileDescriptor.dup creates a separately owned descriptor; close raw
      // even when duplication fails. No descriptor is detached or globally saved.
      try { owned = ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
      val carrier = checkNotNull(owned)
      currentCoroutineContext().ensureActive()
      if (binding != null) {
        check(binding.isUsable()) { "Core UDP network unavailable" }
        binding.bind(carrier.fileDescriptor)
      }
      check(binding?.protect(carrier.fd) ?: protect(carrier.fd)) { "Core UDP protection failed" }
      currentCoroutineContext().ensureActive()
      Os.connect(carrier.fileDescriptor, peer, port)
      currentCoroutineContext().ensureActive()
      check(binding?.isUsable() != false) { "Core UDP network changed" }
      transferred = true
      return carrier
    } finally {
      if (!transferred) runCatching { owned?.close() }
    }
  }
}
