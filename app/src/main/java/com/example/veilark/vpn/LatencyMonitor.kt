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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object LatencyMonitor {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var client: CommandClient? = null
  private var connectJob: Job? = null
  private var connectTimeoutJob: Job? = null
  private var reconnectJob: Job? = null
  private var standaloneClient: CommandClient? = null
  private var standaloneJob: Job? = null
  private var checkingTimeout: Job? = null
  private var reconnectAttempts = 0
  private var clientGeneration = 0L
  private var clientConnected = false
  private var connectTimedOut = false
  private var refreshGeneration = 0L
  private var shouldRun = false
  private var initialRefreshPending = false
  private val mutableLatencies = MutableStateFlow<Map<String, Int>>(emptyMap())
  val latencies = mutableLatencies.asStateFlow()
  private val mutableAutomaticSelection = MutableStateFlow<String?>(null)
  val automaticSelection = mutableAutomaticSelection.asStateFlow()
  private val mutableChecking = MutableStateFlow(false)
  val checking = mutableChecking.asStateFlow()

  fun start() {
    val shouldConnect = synchronized(this) {
      shouldRun = true
      initialRefreshPending = true
      reconnectJob?.cancel()
      reconnectJob = null
      client == null && connectJob?.isActive != true
    }
    if (shouldConnect) requestConnect()
  }

  fun stop() {
    val clientsToClose = synchronized(this) {
      shouldRun = false
      initialRefreshPending = false
      clientGeneration += 1L
      clientConnected = false
      connectTimedOut = false
      refreshGeneration += 1L
      connectJob?.cancel()
      connectJob = null
      connectTimeoutJob?.cancel()
      connectTimeoutJob = null
      reconnectJob?.cancel()
      reconnectJob = null
      standaloneJob?.cancel()
      standaloneJob = null
      checkingTimeout?.cancel()
      checkingTimeout = null
      reconnectAttempts = 0
      mutableLatencies.value = emptyMap()
      mutableAutomaticSelection.value = null
      mutableChecking.value = false
      listOfNotNull(client, standaloneClient).distinct().also {
        client = null
        standaloneClient = null
      }
    }
    closeClientsAsync(clientsToClose)
  }

  fun refresh() {
    val request = synchronized(this) {
      if (!shouldRun) return
      val oldClient = standaloneClient
      standaloneClient = null
      standaloneJob?.cancel()
      standaloneJob = null
      checkingTimeout?.cancel()
      checkingTimeout = null
      mutableChecking.value = true
      (++refreshGeneration) to oldClient
    }
    val generation = request.first
    val oldClient = request.second
    oldClient?.let { closeClientsAsync(listOf(it)) }
    val job = scope.launch(start = CoroutineStart.LAZY) {
      runStandaloneRefresh(generation)
    }
    val admitted = synchronized(this) {
      if (!latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) false
      else {
        standaloneJob = job
        true
      }
    }
    if (admitted) job.start() else job.cancel()
  }

  private fun requestConnect() {
    val generation = synchronized(this) {
      if (!shouldRun || client != null || connectJob?.isActive == true) return
      clientConnected = false
      connectTimedOut = false
      ++clientGeneration
    }
    val job = scope.launch(start = CoroutineStart.LAZY) {
      connect(generation)
    }
    val admitted = synchronized(this) {
      if (!latencyCallbackAccepted(generation, clientGeneration, shouldRun) || client != null) {
        false
      } else {
        connectJob = job
        true
      }
    }
    if (admitted) job.start() else job.cancel()
  }

  private suspend fun connect(generation: Long) {
    val options = CommandClientOptions().apply {
      addCommand(Libbox.CommandGroup)
      addCommand(Libbox.CommandLog)
    }
    val newClient = CommandClient(GenerationHandler(generation), options)
    val admitted = synchronized(this) {
      if (!latencyCallbackAccepted(generation, clientGeneration, shouldRun) || client != null) false
      else {
        client = newClient
        true
      }
    }
    if (!admitted) {
      closeClientsAsync(listOf(newClient))
      return
    }

    val timeout = scope.launch {
      delay(CONNECT_TIMEOUT_MS)
      val stillConnecting = synchronized(this@LatencyMonitor) {
        commandChannelConnectTimedOut(
          generation = generation,
          activeGeneration = clientGeneration,
          shouldRun = shouldRun,
          clientIsCurrent = client === newClient,
          connected = clientConnected,
        ).also { if (it) connectTimedOut = true }
      }
      if (stillConnecting) {
        runCatching { newClient.disconnect() }
        handleDisconnected(generation, "command channel connect timeout")
      }
    }
    synchronized(this) {
      if (latencyCallbackAccepted(generation, clientGeneration, shouldRun) && !clientConnected) {
        connectTimeoutJob = timeout
      } else {
        timeout.cancel()
      }
    }
    try {
      newClient.connect()
    } catch (failure: Throwable) {
      handleDisconnected(generation, failure.message)
      closeClientsAsync(listOf(newClient))
    } finally {
      synchronized(this) {
        if (latencyCallbackAccepted(generation, clientGeneration, shouldRun)) {
          connectJob = null
        }
      }
    }
  }

  private suspend fun runStandaloneRefresh(generation: Long) {
    val newClient = Libbox.newStandaloneCommandClient()
    val admitted = synchronized(this) {
      if (!latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) false
      else {
        standaloneClient = newClient
        true
      }
    }
    if (!admitted) {
      closeClientsAsync(listOf(newClient))
      return
    }
    val timeout = scope.launch {
      delay(REFRESH_TIMEOUT_MS)
      val stillActive = synchronized(this@LatencyMonitor) {
        latencyCallbackAccepted(generation, refreshGeneration, shouldRun) &&
          standaloneClient === newClient
      }
      if (stillActive) {
        runCatching { newClient.disconnect() }
        synchronized(this@LatencyMonitor) {
          if (latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) {
            mutableChecking.value = false
          }
        }
      }
    }
    synchronized(this) {
      if (latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) {
        checkingTimeout = timeout
      } else {
        timeout.cancel()
      }
    }
    try {
      newClient.urlTest("auto")
    } catch (_: Throwable) {
      synchronized(this) {
        if (latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) {
          mutableChecking.value = false
        }
      }
    } finally {
      timeout.cancel()
      runCatching { newClient.disconnect() }
      synchronized(this) {
        if (latencyCallbackAccepted(generation, refreshGeneration, shouldRun)) {
          standaloneClient = null
          standaloneJob = null
          checkingTimeout = null
        }
      }
    }
  }

  private fun handleConnected(generation: Long) {
    val shouldRefresh = synchronized(this) {
      if (!commandChannelCallbackAccepted(
          generation, clientGeneration, shouldRun, clientPresent = client != null,
        ) || connectTimedOut) return
      reconnectAttempts = 0
      clientConnected = true
      connectTimeoutJob?.cancel()
      connectTimeoutJob = null
      reconnectJob?.cancel()
      reconnectJob = null
      initialRefreshPending.also { initialRefreshPending = false }
    }
    TechnicalLogStore.info("CORE", "sing-box technical log channel connected")
    // The command channel is auxiliary. Reconnecting it must not repeatedly
    // run an all-node url-test or add avoidable radio/CPU work to a healthy VPN.
    if (shouldRefresh) refresh()
  }

  private fun handleDisconnected(generation: Long, message: String?) {
    val reconnectDelay = synchronized(this) {
      if (!commandChannelCallbackAccepted(
          generation, clientGeneration, shouldRun, clientPresent = client != null,
        )) return
      client = null
      clientConnected = false
      connectJob = null
      connectTimeoutJob?.cancel()
      connectTimeoutJob = null
      if (VeilarkVpnService.state.value != ConnectionState.Connected) return
      if (reconnectJob?.isActive == true) return
      RECONNECT_DELAYS_MS[
        reconnectAttempts.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
      ].also { reconnectAttempts++ }
    }
    TechnicalLogStore.warning(
      "CORE",
      "sing-box technical log channel closed: ${message ?: "no reason"}",
    )
    val job = scope.launch(start = CoroutineStart.LAZY) {
      delay(reconnectDelay)
      val reconnect = synchronized(this@LatencyMonitor) {
        reconnectJob = null
        shouldRun && client == null &&
          VeilarkVpnService.state.value == ConnectionState.Connected
      }
      if (reconnect) requestConnect()
    }
    val admitted = synchronized(this) {
      if (!shouldRun || client != null || reconnectJob?.isActive == true) false
      else {
        reconnectJob = job
        true
      }
    }
    if (admitted) job.start() else job.cancel()
  }

  private fun handleGroups(generation: Long, message: OutboundGroupIterator?) {
    if (message == null) return
    val delays = mutableMapOf<String, Int>()
    var automaticSelection: String? = null
    while (message.hasNext()) {
      val group = message.next()
      if (group.tag == "auto") automaticSelection = group.selected
      val items = group.items
      while (items.hasNext()) {
        val item = items.next()
        if (item.urlTestDelay > 0) delays[item.tag] = item.urlTestDelay
      }
    }
    synchronized(this) {
      if (!latencyCallbackAccepted(generation, clientGeneration, shouldRun)) return
      automaticSelection?.let { mutableAutomaticSelection.value = it }
      mutableLatencies.value = delays
      mutableChecking.value = false
      checkingTimeout?.cancel()
      checkingTimeout = null
    }
  }

  private fun handleLogs(generation: Long, messageList: LogIterator?) {
    if (messageList == null) return
    while (messageList.hasNext()) {
      val entry = messageList.next()
      val accepted = synchronized(this) {
        latencyCallbackAccepted(generation, clientGeneration, shouldRun)
      }
      if (!accepted) return
      when (entry.level) {
        0, 1, 2 -> TechnicalLogStore.error("CORE", entry.message)
        3 -> TechnicalLogStore.warning("CORE", entry.message)
        4 -> TechnicalLogStore.info("CORE", entry.message)
        // Debug and trace are intentionally not persisted: they contain high-volume
        // per-packet data and would increase battery and storage usage.
      }
    }
  }

  private fun closeClientsAsync(clients: List<CommandClient>) {
    if (clients.isEmpty()) return
    scope.launch {
      clients.forEach { runCatching { it.disconnect() } }
    }
  }

  private class GenerationHandler(private val generation: Long) : CommandClientHandler {
    override fun connected() = handleConnected(generation)
    override fun disconnected(message: String?) = handleDisconnected(generation, message)
    override fun writeGroups(message: OutboundGroupIterator?) = handleGroups(generation, message)
    override fun writeLogs(messageList: LogIterator?) = handleLogs(generation, messageList)
    override fun clearLogs() = Unit
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun updateClashMode(newMode: String) = Unit
    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    override fun writeStatus(message: StatusMessage) = Unit
  }

  private val RECONNECT_DELAYS_MS = longArrayOf(1_500L, 3_000L, 6_000L, 12_000L, 30_000L)
  private const val CONNECT_TIMEOUT_MS = 8_000L
  private const val REFRESH_TIMEOUT_MS = 10_000L
}

internal fun latencyCallbackAccepted(
  callbackGeneration: Long,
  activeGeneration: Long,
  shouldRun: Boolean,
): Boolean = shouldRun && callbackGeneration == activeGeneration

internal fun commandChannelCallbackAccepted(
  generation: Long,
  activeGeneration: Long,
  shouldRun: Boolean,
  clientPresent: Boolean,
): Boolean = latencyCallbackAccepted(generation, activeGeneration, shouldRun) && clientPresent

internal fun commandChannelConnectTimedOut(
  generation: Long,
  activeGeneration: Long,
  shouldRun: Boolean,
  clientIsCurrent: Boolean,
  connected: Boolean,
): Boolean =
  latencyCallbackAccepted(generation, activeGeneration, shouldRun) &&
    clientIsCurrent && !connected
