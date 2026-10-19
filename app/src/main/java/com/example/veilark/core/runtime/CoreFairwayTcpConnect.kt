package com.example.veilark.core.runtime

import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * Dormant one-shot TCP connection custody, not a native API or active VPN path.
 * An IO worker calls connect; the attempt owner calls cancel before joining it.
 * Cancel wakes poll through a pipe and never closes/reuses the worker's socket fd.
 * The caller owns the connected PFD after success and must close it after sync-dup.
 * The caller must authorize the exact peer and captured network epoch first.
 */
internal class CoreFairwayTcpConnect {
  private val lock = Any()
  private var started = false
  private var cancelled = false
  private var wakeRead: ParcelFileDescriptor? = null
  private var wakeWrite: ParcelFileDescriptor? = null

  /** Idempotent, nonblocking wake; descriptor publication/close share this lock. */
  fun cancel() {
    synchronized(lock) {
      if (cancelled) return
      cancelled = true
      wakeWrite?.let { writer ->
        try { Os.write(writer.fileDescriptor, byteArrayOf(1), 0, 1) }
        catch (failure: ErrnoException) {
          // A full pipe already signals cancellation. Other errors are defects.
          if (failure.errno != OsConstants.EAGAIN) throw failure
        }
      }
    }
  }

  @Throws(IOException::class, ErrnoException::class)
  fun connect(
    binding: CoreFairwayNetworkBinding,
    peer: InetSocketAddress,
    timeoutMillis: Int,
  ): ParcelFileDescriptor {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Fairway TCP connect requires an IO worker" }
    require(peer.address is Inet4Address && peer.port in 1..65535)
    require(timeoutMillis in 1..30_000)
    synchronized(lock) {
      check(!started) { "Fairway TCP connect is one-shot" }
      started = true
      checkCancelledLocked()
    }
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    var socket: ParcelFileDescriptor? = null
    try {
      publishWakePipe()
      checkCancelled()
      if (!binding.isUsable()) throw IOException("Underlying Fairway network unavailable")
      val raw = Os.socket(OsConstants.AF_INET,
        OsConstants.SOCK_STREAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
        OsConstants.IPPROTO_TCP)
      try { socket = ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
      val owned = checkNotNull(socket)
      binding.bind(owned.fileDescriptor)
      checkCancelled()
      if (!binding.protect(owned.fd)) throw IOException("Fairway TCP protection failed")
      checkCancelled()
      checkDeadline(deadline)
      val pending = try {
        Os.connect(owned.fileDescriptor, peer.address, peer.port)
        false
      } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.EINPROGRESS && failure.errno != OsConstants.EINTR) throw failure
        true
      }
      if (pending) awaitConnected(owned, deadline)
      checkCancelled()
      checkDeadline(deadline)
      val actual = Os.getpeername(owned.fileDescriptor)
      if (actual != peer) throw IOException("Unexpected Fairway TCP peer")
      // Cleanup can fail: retain socket custody until all temporary fds close.
      closeWakePipe()
      synchronized(lock) {
        checkCancelledLocked()
        checkDeadline(deadline)
        // Linearize transfer against cancel; cancellation after this point belongs
        // to the caller's attempt/admission fence, not this connection operation.
        socket = null
      }
      return owned
    } finally {
      try { socket?.close() } finally { closeWakePipe() }
    }
  }

  private fun publishWakePipe() {
    val raw = ParcelFileDescriptor.createPipe()
    var read: ParcelFileDescriptor? = null
    var write: ParcelFileDescriptor? = null
    try {
      for (descriptor in raw) {
        val fd = descriptor.fileDescriptor
        Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
        Os.fcntlInt(fd, OsConstants.F_SETFD, Os.fcntlInt(fd, OsConstants.F_GETFD, 0) or OsConstants.FD_CLOEXEC)
      }
      read = ParcelFileDescriptor.dup(raw[0].fileDescriptor)
      write = ParcelFileDescriptor.dup(raw[1].fileDescriptor)
      synchronized(lock) {
        checkCancelledLocked()
        wakeRead = read
        wakeWrite = write
        read = null
        write = null
      }
    } finally {
      try { read?.close() } finally {
        try { write?.close() } finally {
          try { raw[0].close() } finally { raw[1].close() }
        }
      }
    }
  }

  private fun awaitConnected(socket: ParcelFileDescriptor, deadline: Long) {
    val reader = synchronized(lock) { checkCancelledLocked(); checkNotNull(wakeRead) }
    val network = StructPollfd().apply {
      fd = socket.fileDescriptor
      events = OsConstants.POLLOUT.toShort()
    }
    val cancellation = StructPollfd().apply {
      fd = reader.fileDescriptor
      events = OsConstants.POLLIN.toShort()
    }
    while (true) {
      checkCancelled()
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) throw SocketTimeoutException("Fairway TCP connection deadline")
      val count = try { Os.poll(arrayOf(network, cancellation), remaining.toInt()) }
      catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.EINTR) throw failure
        Thread.yield()
        continue
      }
      checkCancelled()
      if (count == 0) throw SocketTimeoutException("Fairway TCP connection deadline")
      if (cancellation.revents.toInt() != 0) throw CancellationException("Fairway TCP connect cancelled")
      if ((network.revents.toInt() and OsConstants.POLLNVAL) != 0) throw IOException("Invalid Fairway TCP descriptor")
      if (network.revents.toInt() != 0) {
        // Public Android SDK has no getsockoptInt(SO_ERROR). getpeername fails
        // for an unconnected/failed TCP socket; never admit from POLLOUT alone.
        // Native admission must still verify stream/type/peer/flags and SO_ERROR.
        Os.getpeername(socket.fileDescriptor)
        return
      }
    }
  }

  private fun checkCancelled() = synchronized(lock) { checkCancelledLocked() }
  private fun checkDeadline(deadline: Long) {
    if (SystemClock.elapsedRealtime() >= deadline) throw SocketTimeoutException("Fairway TCP connection deadline")
  }
  private fun checkCancelledLocked() {
    if (cancelled) throw CancellationException("Fairway TCP connect cancelled")
  }

  private fun closeWakePipe() {
    synchronized(lock) {
      val read = wakeRead
      val write = wakeWrite
      wakeRead = null
      wakeWrite = null
      try { read?.close() } finally { write?.close() }
    }
  }
}
