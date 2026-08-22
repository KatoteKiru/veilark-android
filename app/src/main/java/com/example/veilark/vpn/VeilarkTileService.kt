package com.example.veilark.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.veilark.MainActivity
import com.example.veilark.lifecycle.AndroidTunnelLifecycleOwner
import com.example.veilark.profile.SecureProfileStore
import com.example.veilark.protocol.ProfileEngine
import com.example.veilark.protocol.TrustTunnelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VeilarkTileService : TileService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var stateJob: Job? = null

  override fun onStartListening() {
    super.onStartListening()
    updateTile()
    stateJob?.cancel()
    stateJob = scope.launch {
      combine(VeilarkVpnService.state, TrustTunnelManager.state) { singBox, trust ->
        singBox to trust
      }.collect {
        updateTile()
      }
    }
  }

  override fun onStopListening() {
    stateJob?.cancel()
    stateJob = null
    super.onStopListening()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onClick() {
    super.onClick()
    unlockAndRun {
      runCatching { toggleConnection() }
        .onFailure {
          updateTile(Tile.STATE_INACTIVE, "Откройте приложение")
          openApp(connect = false)
        }
    }
  }

  private fun toggleConnection() {
    val singBoxState = VeilarkVpnService.state.value
    val trustState = TrustTunnelManager.state.value
    if (singBoxState.isRunning() || trustState.isRunning()) {
      AndroidTunnelLifecycleOwner.stop(this)
      updateTile(Tile.STATE_INACTIVE, "Отключено")
      return
    }

    val preferences = getSharedPreferences("profile_meta", MODE_PRIVATE)
    val engine = preferences.getString("profile_engine", ProfileEngine.SING_BOX)
      ?: ProfileEngine.SING_BOX
    val profileId = if (engine == ProfileEngine.TRUST_TUNNEL) {
      SecureProfileStore.TRUST_TUNNEL
    } else {
      SecureProfileStore.SING_BOX
    }
    if (!SecureProfileStore.exists(this, profileId)) {
      openApp(connect = false)
      return
    }

    if (android.net.VpnService.prepare(this) != null) {
      openApp(connect = true)
      return
    }

    updateTile(Tile.STATE_ACTIVE, "Подключение…")
    if (engine == ProfileEngine.TRUST_TUNNEL) {
      TrustTunnelManager.start(this, SecureProfileStore.load(this, profileId))
    } else {
      VeilarkVpnService.start(this, profileId)
    }
  }

  private fun updateTile() {
    val states = listOf(VeilarkVpnService.state.value, TrustTunnelManager.state.value)
    when {
      states.any { it == ConnectionState.Connected } ->
        updateTile(Tile.STATE_ACTIVE, "Защищено")
      states.any { it == ConnectionState.Connecting } ->
        updateTile(Tile.STATE_ACTIVE, "Подключение…")
      else ->
        updateTile(Tile.STATE_INACTIVE, "Отключено")
    }
  }

  private fun updateTile(state: Int, subtitle: String) {
    qsTile?.apply {
      label = "Veilark"
      this.state = state
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        this.subtitle = subtitle
      }
      updateTile()
    }
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  @Suppress("DEPRECATION")
  private fun openApp(connect: Boolean) {
    val intent = Intent(this, MainActivity::class.java)
      .setAction(if (connect) MainActivity.ACTION_CONNECT_FROM_TILE else Intent.ACTION_MAIN)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pendingIntent = PendingIntent.getActivity(
        this,
        if (connect) 1 else 0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      startActivityAndCollapse(pendingIntent)
    } else {
      startActivityAndCollapse(intent)
    }
  }

  private fun ConnectionState.isRunning(): Boolean =
    this == ConnectionState.Connected || this == ConnectionState.Connecting
}
