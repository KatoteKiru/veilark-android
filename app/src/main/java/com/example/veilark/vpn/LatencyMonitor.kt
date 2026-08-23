package com.example.veilark.vpn

import com.example.veilark.diagnostics.TechnicalLogStore
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object LatencyMonitor : CommandClientHandler {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var client: CommandClient? = null
  private var reconnectJob: Job? = null
  private var reconnectAttempts = 0
  private var shouldRun = false
  private val mutableLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
  val latencies = mutableLatencies.asStateFlow()
  private val mutableAutomaticSelection = MutableStateFlow<String?>(null)
  val automaticSelection = mutableAutomaticSelection.asStateFlow()
  private val mutableChecking = MutableStateFlow(false)
  val checking = mutableChecking.asStateFlow()
  private var checkingTimeout: Job? = null

  @Synchronized
  fun start() {
    shouldRun = true
    reconnectJob?.cancel()
    reconnectJob = null
    if (client != null) return
    connect()
  }

  private fun connect() {
    val options = CommandClientOptions().apply {
      addCommand(Libbox.CommandGroup)
      addCommand(Libbox.CommandLog)
    }
    val newClient = CommandClient(this, options)
    client = newClient
    try {
      newClient.connect()
    } catch (failure: Throwable) {
      if (client === newClient) client = null
      throw failure
    }
  }

  @Synchronized
  fun stop() {
    shouldRun = false
    reconnectJob?.cancel()
    reconnectJob = null
    reconnectAttempts = 0
    runCatching { client?.disconnect() }
    client = null
    mutableLatencies.value = emptyMap()
    mutableAutomaticSelection.value = null
    mutableChecking.value = false
    checkingTimeout?.cancel()
    checkingTimeout = null
  }

  fun refresh() {
    mutableChecking.value = true
    runCatching { Libbox.newStandaloneCommandClient().urlTest("auto") }
      .onFailure { mutableChecking.value = false }
    checkingTimeout?.cancel()
    checkingTimeout = scope.launch {
      delay(10_000)
      mutableChecking.value = false
    }
  }

  override fun connected() {
    synchronized(this) {
      reconnectAttempts = 0
      reconnectJob?.cancel()
      reconnectJob = null
    }
    TechnicalLogStore.info("CORE", "sing-box technical log channel connected")
    refresh()
  }

  override fun disconnected(message: String?) {
    if (shouldRun) {
      TechnicalLogStore.warning(
        "CORE",
        "sing-box technical log channel closed: ${message ?: "no reason"}",
      )
    }
    synchronized(this) {
      client = null
      if (!shouldRun || VeilarkVpnService.state.value != ConnectionState.Connected) return
      if (reconnectJob?.isActive == true) return
      val delayMillis = RECONNECT_DELAYS_MS[
        reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
      ]
      reconnectAttempts++
      reconnectJob = scope.launch {
        delay(delayMillis)
        synchronized(this@LatencyMonitor) {
          reconnectJob = null
          if (shouldRun &&
            client == null &&
            VeilarkVpnService.state.value == ConnectionState.Connected
          ) {
            runCatching { connect() }
              .onFailure { disconnected(it.message) }
          }
        }
      }
    }
  }

  override fun writeGroups(message: OutboundGroupIterator?) {
    if (message == null) return
    val delays = mutableMapOf<String, Int>()
    while (message.hasNext()) {
      val group = message.next()
      if (group.tag == "auto") mutableAutomaticSelection.value = group.selected
      val items = group.items
      while (items.hasNext()) {
        val item = items.next()
        if (item.urlTestDelay > 0) delays[item.tag] = item.urlTestDelay
      }
    }
    mutableLatencies.value = delays
    mutableChecking.value = false
    checkingTimeout?.cancel()
    checkingTimeout = null
  }

  override fun clearLogs() = Unit
  override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
  override fun setDefaultLogLevel(level: Int) = Unit
  override fun updateClashMode(newMode: String) = Unit
  override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
  override fun writeLogs(messageList: LogIterator?) {
    if (messageList == null) return
    while (messageList.hasNext()) {
      val entry = messageList.next()
      when (entry.level) {
        0, 1, 2 -> TechnicalLogStore.error("CORE", entry.message)
        3 -> TechnicalLogStore.warning("CORE", entry.message)
        4 -> TechnicalLogStore.info("CORE", entry.message)
        // Debug and trace are intentionally not persisted: they contain high-volume
        // per-packet data and would increase battery and storage usage.
      }
    }
  }
  override fun writeStatus(message: StatusMessage) = Unit

  private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 30_000L)
}
