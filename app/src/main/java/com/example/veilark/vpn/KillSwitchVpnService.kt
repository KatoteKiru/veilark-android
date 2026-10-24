package com.example.veilark.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.veilark.BuildConfig
import com.example.veilark.MainActivity
import com.example.veilark.R
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.lifecycle.TunnelBlockFence

/**
 * Kill-switch interface and Always-on VPN entry point.
 *
 * While no engine protects the device, this service holds a TUN that captures
 * every IPv4 and IPv6 route and is never read, so other applications' traffic
 * is dropped. Veilark itself is excluded and can still reconnect and update.
 * An engine's own establish() replaces the interface; the descriptor here is
 * closed only after that, so the routes never fall back to the bare network.
 *
 * The block lives in its own service so that it never shares attempt or
 * session state with the engines, one of which is a vendored library service.
 * It is also the highest-priority `android.net.VpnService` handler, which makes
 * it the component Android starts for Always-on VPN.
 */
class KillSwitchVpnService : VpnService() {
  private val mainHandler = Handler(Looper.getMainLooper())
  // Accessed on the main thread only.
  private var descriptor: ParcelFileDescriptor? = null

  override fun onCreate() {
    super.onCreate()
    running = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_HOLD -> {
        // Started with startForegroundService(), so a stale request must
        // still enter the foreground before it stops.
        enterForeground()
        establish(intent.getLongExtra(EXTRA_GENERATION, 0L))
      }

      ACTION_RELEASE -> if (!fence.hasRequest()) closeBlock()
      ACTION_DISCONNECT -> AndroidTunnelLifecycleOwner.stop(this)
      // Always-on VPN starts the service with SERVICE_INTERFACE; a sticky
      // restart after process death delivers a null intent.
      else -> startFromSystem()
    }
    return settle(startId)
  }

  private fun startFromSystem() {
    TechnicalLogStore.info("KILL-SWITCH", "Android started the VPN; restoring the selected profile")
    // Keeps the process at foreground priority while the engine is started;
    // settle() removes the notification again if no block is held.
    enterForeground()
    if (isEnabled(this)) fence.request()?.let(::establish)
    AndroidTunnelLifecycleOwner.startStoredProfile(this)
  }

  private fun establish(generation: Long) {
    val replacement = runCatching {
      fence.establishIfCurrent(generation) { blockingInterface().establish() }
    }.onFailure {
      TechnicalLogStore.error("KILL-SWITCH", "Android rejected the blocking interface: ${it.javaClass.simpleName}")
    }.getOrNull() ?: return
    val previous = descriptor
    descriptor = replacement
    // The earlier interface is closed only after its replacement owns the routes.
    runCatching { previous?.close() }
    if (previous == null) {
      TechnicalLogStore.warning("KILL-SWITCH", "No protected connection; traffic is blocked")
    }
  }

  private fun blockingInterface(): Builder {
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    // No DNS server and no bypass; packets routed here are never read.
    return Builder()
      .setSession(getString(R.string.kill_switch))
      .setConfigureIntent(open)
      .addAddress(BLOCK_ADDRESS_V4, 32)
      .addAddress(BLOCK_ADDRESS_V6, 128)
      .addRoute("0.0.0.0", 0)
      .addRoute("::", 0)
      .addDisallowedApplication(packageName)
  }

  private fun closeBlock() {
    val held = descriptor ?: return
    descriptor = null
    runCatching { held.close() }
    TechnicalLogStore.info("KILL-SWITCH", "Blocking interface released")
  }

  private fun settle(startId: Int): Int {
    if (descriptor != null) return START_STICKY
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf(startId)
    return START_NOT_STICKY
  }

  override fun onRevoke() {
    // Another VPN app or the user took the VPN; the block must not compete.
    mainHandler.post {
      fence.cancel()
      closeBlock()
    }
    super.onRevoke()
  }

  override fun onDestroy() {
    running = false
    closeBlock()
    super.onDestroy()
  }

  private fun enterForeground() {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.kill_switch),
        NotificationManager.IMPORTANCE_LOW,
      ).apply { setShowBadge(false) },
    )
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val disconnect = PendingIntent.getService(
      this,
      1,
      Intent(this, KillSwitchVpnService::class.java).setAction(ACTION_DISCONNECT),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_vpn_status)
      .setContentTitle("Veilark · ${getString(R.string.kill_switch)}")
      .setContentText(getString(R.string.kill_switch_blocking))
      .setContentIntent(open)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .addAction(0, getString(R.string.notification_disconnect), disconnect)
      .build()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (failure: Exception) {
      // The interface still blocks; only the notification and priority are lost.
      TechnicalLogStore.warning("KILL-SWITCH", "Foreground start refused: ${failure.javaClass.simpleName}")
    }
  }

  companion object {
    private val ACTION_HOLD = "${BuildConfig.APPLICATION_ID}.KILL_SWITCH_HOLD"
    private val ACTION_RELEASE = "${BuildConfig.APPLICATION_ID}.KILL_SWITCH_RELEASE"
    private val ACTION_DISCONNECT = "${BuildConfig.APPLICATION_ID}.KILL_SWITCH_DISCONNECT"
    private const val EXTRA_GENERATION = "block_generation"
    private const val PREFERENCE = "kill_switch"
    private const val CHANNEL_ID = "veilark_kill_switch"
    private const val NOTIFICATION_ID = 1007
    private const val BLOCK_ADDRESS_V4 = "172.31.255.1"
    private const val BLOCK_ADDRESS_V6 = "fdfd:ffff::1"

    private val fence = TunnelBlockFence()
    @Volatile
    private var running = false

    fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE).getBoolean(PREFERENCE, true)

    fun setEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREFERENCE, enabled)
        .apply()
      if (!enabled) release(context)
    }

    /** Called by the lifecycle owner before an engine may call establish(). */
    internal fun admitEngine() = fence.admitEngine()

    /** Called by the lifecycle owner once the admitted engine's teardown is confirmed. */
    internal fun engineReleased() = fence.engineReleased()

    internal fun hold(context: Context) {
      val generation = fence.request() ?: return
      try {
        ContextCompat.startForegroundService(
          context,
          Intent(context, KillSwitchVpnService::class.java)
            .setAction(ACTION_HOLD)
            .putExtra(EXTRA_GENERATION, generation),
        )
      } catch (failure: Exception) {
        TechnicalLogStore.error("KILL-SWITCH", "Android did not start the blocking interface: ${failure.javaClass.simpleName}")
      }
    }

    internal fun release(context: Context) {
      fence.cancel()
      if (!running) return
      runCatching {
        context.startService(Intent(context, KillSwitchVpnService::class.java).setAction(ACTION_RELEASE))
      }.onFailure {
        TechnicalLogStore.warning("KILL-SWITCH", "Blocking interface release was not delivered")
      }
    }
  }
}
