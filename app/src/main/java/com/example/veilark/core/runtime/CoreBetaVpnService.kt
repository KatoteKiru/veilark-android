package com.example.veilark.core.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import com.example.veilark.MainActivity
import com.example.veilark.R
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.profile.CoreBetaProfile
import com.example.veilark.profile.CoreBetaProfileStore
import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress

/** IPv4-only beta: retained TUN blocks traffic during reconnect, never changes engine. */
class CoreBetaVpnService : VpnService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycle = CoreBetaLifecycle(object : CoreBetaHandleApi {
    override fun status(handle: Long) = VeilarkCoreRuntimeNative.nativeStatus(handle)
    override fun stop(handle: Long) = VeilarkCoreRuntimeNative.nativeStop(handle)
    override fun release(handle: Long) = VeilarkCoreRuntimeNative.nativeRelease(handle)
  })
  private var running = false
  private var attempt: LifecycleAttempt? = null
  @Volatile private var destroyed = false
  @Volatile private var nativeReleased = false
  private var managerReleased = false

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (running) return START_NOT_STICKY
    attempt = CoreBetaManager.attachAttempt(intent?.getLongExtra("attempt", -1) ?: -1,
      intent?.getLongExtra("epoch", -1) ?: -1)
    if (attempt == null) { stopSelf(startId); return START_NOT_STICKY }
    running = true
    try {
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Veilark Core beta", NotificationManager.IMPORTANCE_LOW))
      val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val notice = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_vpn_status).setContentTitle("Veilark Core · beta")
        .setContentText(getString(R.string.core_beta_notification_text)).setContentIntent(open).setOngoing(true).build()
      if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      else startForeground(NOTIFICATION, notice)
    } catch (_: Exception) {
      publish(ConnectionState.Failed, getString(R.string.core_beta_connection_failed))
      nativeReleased = true // No TUN/native worker was started on this path.
      stopSelf()
      return START_NOT_STICKY
    }
    // Even cancellation before dispatch must enter the finally ownership cleanup.
    scope.launch(start = CoroutineStart.ATOMIC) { runTunnel() }
    return START_NOT_STICKY
  }

  private suspend fun runTunnel() {
    var tun: ParcelFileDescriptor? = null
    try {
      currentCoroutineContext().ensureActive()
      check(VeilarkCoreRuntimeNative.loadCandidate())
      val profile = CoreBetaProfileStore.load(this)
      // Android blocks an address family absent from addresses/routes/DNS. Never
      // call allowFamily(AF_INET6), add IPv6 DNS/routes, or allowBypass here.
      val builder = Builder().setSession("Veilark Core beta").setMtu(1280).setBlocking(false)
        .addAddress(profile.clientIpv4, 32).addRoute("0.0.0.0", 0)
      profile.dnsIpv4.forEach { builder.addDnsServer(it) }
      if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
      val activeTun = checkNotNull(builder.establish())
      tun = activeTun
      var consecutiveFailures = 0
      while (true) {
        currentCoroutineContext().ensureActive()
        publish(ConnectionState.Connecting)
        var reachedReady = false
        var readyAtMillis: Long? = null
        var endedAtMillis = 0L
        try {
          startSession(activeTun, profile)
          val deadline = SystemClock.elapsedRealtime() + 15_000
          while (true) {
            currentCoroutineContext().ensureActive()
            val status = lifecycle.status() ?: error("Missing session")
            if (status == 1 && !reachedReady) {
              reachedReady = true
              readyAtMillis = SystemClock.elapsedRealtime()
              publish(ConnectionState.Connected)
            }
            if (status == 3 || status < 0) break
            if (!reachedReady && SystemClock.elapsedRealtime() >= deadline) error("Authentication timeout")
            delay(250)
          }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Safe public error below; no credential-bearing exception text. */ }
        finally {
          endedAtMillis = SystemClock.elapsedRealtime()
          check(lifecycle.stopAndRelease()) { "Core cleanup did not complete" }
        }
        val healthyRotation = CoreBetaReconnectPolicy.isHealthyRotation(readyAtMillis, endedAtMillis, profile.lifetimeSeconds)
        if (healthyRotation) consecutiveFailures = 0 else consecutiveFailures++
        check(consecutiveFailures <= 5) { "Core beta cannot connect" }
        publish(ConnectionState.Connecting)
        delay(if (healthyRotation) 250 else CoreBetaReconnectPolicy.delayMillis(consecutiveFailures))
      }
    } catch (_: CancellationException) {
      // Explicit stop/revoke is completed only after native ownership release.
    } catch (_: Throwable) {
      publish(ConnectionState.Failed, getString(R.string.core_beta_connection_failed))
    } finally {
      val released = runCatching { lifecycle.stopAndRelease() }.getOrDefault(false)
      runCatching { tun?.close() }
      nativeReleased = released
      if (!released) publish(ConnectionState.Failed, getString(R.string.core_beta_cleanup_failed))
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      releaseManagerIfDestroyed()
    }
  }

  private suspend fun startSession(tun: ParcelFileDescriptor, profile: CoreBetaProfile) {
    var socket: ParcelFileDescriptor? = null
    var borrowedTun: ParcelFileDescriptor? = null
    val key = profile.keyCopy()
    val context = profile.contextCopy()
    val carrier = context.copyOfRange(0, 32)
    val initiator = context.copyOfRange(32, 64)
    val responder = context.copyOfRange(64, 96)
    try {
      // Atomic NONBLOCK creation works on minSdk29; public Os.fcntlInt is API30+.
      val raw = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM or OsConstants.SOCK_NONBLOCK, OsConstants.IPPROTO_TCP)
      socket = try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
      val connectedSocket = checkNotNull(socket)
      check(protect(connectedSocket.fd))
      val address = ipv4(profile.endpointIpv4)
      try { Os.connect(connectedSocket.fileDescriptor, address, profile.endpointPort) }
      catch (error: ErrnoException) { if (error.errno != OsConstants.EINPROGRESS) throw error }
      val poll = StructPollfd().apply { fd = connectedSocket.fileDescriptor; events = OsConstants.POLLOUT.toShort() }
      val deadline = SystemClock.elapsedRealtime() + 10_000
      while (Os.poll(arrayOf(poll), 100) == 0) {
        currentCoroutineContext().ensureActive()
        check(SystemClock.elapsedRealtime() < deadline)
      }
      currentCoroutineContext().ensureActive()
      val events = poll.revents.toInt()
      check((events and OsConstants.POLLOUT) != 0 &&
        (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) == 0)
      // getpeername is public Android SDK; failed async connect has no peer.
      val peer = Os.getpeername(connectedSocket.fileDescriptor) as? InetSocketAddress
      check(peer != null && peer.address == address && peer.port == profile.endpointPort)
      borrowedTun = ParcelFileDescriptor.dup(tun.fileDescriptor)
      val source = profile.clientIpv4.split('.').fold(0) { value, octet -> (value shl 8) or octet.toInt() }
      lifecycle.startBorrowed(
        start = { VeilarkCoreRuntimeNative.nativeStartOriginalIpv4BetaV1(
          borrowedTun!!.fd, socket!!.fd, key, carrier, initiator, responder, source, profile.lifetimeSeconds) },
        closeOriginals = { runCatching { borrowedTun?.close() }; borrowedTun = null
          runCatching { socket?.close() }; socket = null },
      )
    } finally {
      key.fill(0); context.fill(0); carrier.fill(0); initiator.fill(0); responder.fill(0)
      runCatching { borrowedTun?.close() }; runCatching { socket?.close() }
    }
  }

  override fun onRevoke() { scope.cancel(); super.onRevoke() }
  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
    destroyed = true
    releaseManagerIfDestroyed()
  }

  /** The owner must never start a replacement in this still-live service instance. */
  @Synchronized private fun releaseManagerIfDestroyed() {
    if (!destroyed || !nativeReleased || managerReleased) return
    val originalAttempt = attempt ?: return
    managerReleased = true
    CoreBetaManager.released(originalAttempt)
  }

  private fun publish(state: ConnectionState, failure: String? = null) {
    attempt?.let { CoreBetaManager.publish(it, state, failure) }
  }

  private fun ipv4(value: String): InetAddress = InetAddress.getByAddress(value.split('.').map { it.toInt().toByte() }.toByteArray())

  companion object {
    private const val CHANNEL = "veilark-core-beta"
    private const val NOTIFICATION = 3103
  }
}

internal object CoreBetaReconnectPolicy {
  fun isHealthyRotation(readyAtMillis: Long?, endedAtMillis: Long, lifetimeSeconds: Int): Boolean =
    readyAtMillis != null && endedAtMillis - readyAtMillis >= minOf(30L, lifetimeSeconds.toLong() - 1) * 1000

  fun delayMillis(consecutiveFailures: Int): Long {
    require(consecutiveFailures in 1..5)
    return (1000L shl (consecutiveFailures - 1)).coerceAtMost(16_000)
  }
}
