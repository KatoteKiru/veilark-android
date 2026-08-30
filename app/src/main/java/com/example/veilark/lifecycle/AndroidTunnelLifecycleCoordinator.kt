package com.example.veilark.lifecycle

import android.content.Context
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.protocol.TrustTunnelManager
import com.example.veilark.vpn.ConnectionState
import com.example.veilark.vpn.VeilarkVpnService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure, deterministic lifecycle model for a future Android tunnel owner.
 *
 * This class deliberately contains no Android, file-descriptor, JNI, coroutine,
 * clock, or network API. A service-side adapter is responsible for serializing
 * inputs and executing the returned [LifecycleAction]. The adapter must echo the
 * supplied [LifecycleAttempt] in every completion it submits back here.
 */
class AndroidTunnelLifecycleCoordinator internal constructor(
  initialEngine: EngineId,
  val engineEpoch: Long,
  initialAttemptId: Long,
) {
  constructor(engineEpoch: Long) : this(EngineId.SingBox, engineEpoch, 1L)

  constructor(initialEngine: EngineId, engineEpoch: Long) : this(initialEngine, engineEpoch, 1L)

  init {
    require(engineEpoch > 0) { "engineEpoch must be positive" }
    require(initialAttemptId > 0) { "initialAttemptId must be positive" }
  }

  private var nextAttemptId: Long? = initialAttemptId

  var state: LifecycleState = LifecycleState.Stopped(initialEngine)
    private set

  /**
   * Serializes one command or completion. It never performs I/O; callers enact
   * [LifecycleDispatch.action] and later submit a correlated completion.
   */
  @Synchronized
  fun dispatch(input: LifecycleInput): LifecycleDispatch {
    val previous = state
    val action = when (input) {
      is LifecycleInput.Connect -> connect(input.engine)
      is LifecycleInput.Reconnect -> reconnect(input.attempt)
      LifecycleInput.Stop -> stop(StopCause.User)
      LifecycleInput.Revoke -> stop(StopCause.Revoked)
      LifecycleInput.Destroy -> stop(StopCause.Destroyed)
      is LifecycleInput.PermissionResult -> permissionResult(input)
      is LifecycleInput.EngineStarted -> engineStarted(input.attempt)
      is LifecycleInput.EngineStopped -> engineStopped(input.stopFence)
      is LifecycleInput.EngineFailed -> engineFailed(input)
      is LifecycleInput.Timeout -> timedOut(input.attempt)
    }
    return LifecycleDispatch(previous = previous, current = state, action = action)
  }

  private fun connect(engine: EngineId): LifecycleAction = when (val current = state) {
    is LifecycleState.Stopped -> beginAttempt(engine)
    is LifecycleState.Failed -> beginAttempt(engine)

    is LifecycleState.AwaitingPermission -> beginAttempt(engine)
    is LifecycleState.Starting -> queueAfterTerminalStop(current.attempt, engine, StopCause.Switch)
    is LifecycleState.Connected -> queueAfterTerminalStop(current.attempt, engine, StopCause.Switch)

    is LifecycleState.Stopping -> queueWhileStopping(current, engine)
  }

  private fun reconnect(originatingAttempt: LifecycleAttempt): LifecycleAction {
    val current = state as? LifecycleState.Connected ?: return ignoredStaleOrIllegal()
    if (originatingAttempt != current.attempt) return ignoredStaleOrIllegal()
    return queueAfterTerminalStop(current.attempt, current.attempt.engine, StopCause.Reconnect)
  }

  private fun beginAttempt(engine: EngineId): LifecycleAction {
    val attempt = allocateAttempt(engine) ?: return LifecycleAction.Ignored(IgnoreReason.AttemptIdExhausted)
    state = LifecycleState.AwaitingPermission(attempt)
    return LifecycleAction.RequestVpnPermission(attempt)
  }

  private fun queueAfterTerminalStop(
    activeAttempt: LifecycleAttempt,
    nextEngine: EngineId,
    cause: StopCause,
  ): LifecycleAction {
    val pending = allocateAttempt(nextEngine)
      ?: return LifecycleAction.Ignored(IgnoreReason.AttemptIdExhausted)
    val stopFence = LifecycleStopFence(activeAttempt)
    state = LifecycleState.Stopping(
      activeAttempt = activeAttempt,
      stopFence = stopFence,
      afterStop = AfterStop.Start(pending),
    )
    return LifecycleAction.StopEngine(stopFence, cause)
  }

  private fun queueWhileStopping(
    current: LifecycleState.Stopping,
    nextEngine: EngineId,
  ): LifecycleAction {
    return when (current.afterStop) {
      is AfterStop.Start -> {
        val pending = allocateAttempt(nextEngine)
          ?: return LifecycleAction.Ignored(IgnoreReason.AttemptIdExhausted)
        state = current.copy(afterStop = AfterStop.Start(pending))
      LifecycleAction.QueuedAfterStop(pending)
      }

      AfterStop.Stop -> ignoredStaleOrIllegal()
      is AfterStop.Fail -> ignoredStaleOrIllegal()
    }
  }

  private fun permissionResult(input: LifecycleInput.PermissionResult): LifecycleAction {
    val current = state as? LifecycleState.AwaitingPermission
      ?: return ignoredStaleOrIllegal()
    if (input.attempt != current.attempt) return ignoredStaleOrIllegal()

    return if (input.granted) {
      state = LifecycleState.Starting(current.attempt)
      LifecycleAction.StartEngine(current.attempt)
    } else {
      state = LifecycleState.Failed(current.attempt, FailureReason.PermissionDenied)
      LifecycleAction.Failed(current.attempt, FailureReason.PermissionDenied)
    }
  }

  private fun engineStarted(attempt: LifecycleAttempt): LifecycleAction {
    val current = state as? LifecycleState.Starting
      ?: return ignoredStaleOrIllegal()
    if (attempt != current.attempt) return ignoredStaleOrIllegal()

    state = LifecycleState.Connected(attempt)
    return LifecycleAction.Connected(attempt)
  }

  private fun engineStopped(stopFence: LifecycleStopFence): LifecycleAction {
    val current = state as? LifecycleState.Stopping
      ?: return ignoredStaleOrIllegal()
    if (stopFence != current.stopFence) return ignoredStaleOrIllegal()

    return when (val afterStop = current.afterStop) {
      AfterStop.Stop -> {
        state = LifecycleState.Stopped(current.activeAttempt.engine)
        LifecycleAction.Stopped(current.activeAttempt)
      }

      is AfterStop.Fail -> {
        state = LifecycleState.Failed(current.activeAttempt, afterStop.reason)
        LifecycleAction.Failed(current.activeAttempt, afterStop.reason)
      }

      is AfterStop.Start -> {
        state = LifecycleState.AwaitingPermission(afterStop.attempt)
        LifecycleAction.RequestVpnPermission(afterStop.attempt)
      }
    }
  }

  private fun engineFailed(input: LifecycleInput.EngineFailed): LifecycleAction {
    return when (val current = state) {
      is LifecycleState.Starting -> {
        if (input.attempt != current.attempt) ignoredStaleOrIllegal()
        else stopForFailure(current.attempt, input.reason)
      }

      is LifecycleState.Connected -> {
        if (input.attempt != current.attempt) ignoredStaleOrIllegal()
        else stopForFailure(current.attempt, input.reason)
      }

      else -> ignoredStaleOrIllegal()
    }
  }

  private fun timedOut(attempt: LifecycleAttempt): LifecycleAction {
    val current = state as? LifecycleState.Starting
      ?: return ignoredStaleOrIllegal()
    if (attempt != current.attempt) return ignoredStaleOrIllegal()
    return stopForFailure(current.attempt, FailureReason.Timeout)
  }

  private fun stop(cause: StopCause): LifecycleAction = when (val current = state) {
    is LifecycleState.Stopped -> LifecycleAction.Ignored(IgnoreReason.AlreadyStopped)
    is LifecycleState.Failed -> {
      state = LifecycleState.Stopped(current.selectedEngine)
      LifecycleAction.Stopped(current.attempt)
    }

    is LifecycleState.AwaitingPermission -> {
      state = terminalStateWithoutEngine(current.attempt, cause)
      terminalAction(current.attempt, cause)
    }

    is LifecycleState.Starting -> queueStop(current.attempt, cause)
    is LifecycleState.Connected -> queueStop(current.attempt, cause)

    is LifecycleState.Stopping -> {
      when (current.afterStop) {
        is AfterStop.Start -> {
          state = current.copy(afterStop = afterStopFor(cause))
          LifecycleAction.PendingStartCancelled(cause)
        }

        AfterStop.Stop -> LifecycleAction.Ignored(IgnoreReason.TerminalAlreadyChosen)
        is AfterStop.Fail -> LifecycleAction.Ignored(IgnoreReason.TerminalAlreadyChosen)
      }
    }
  }

  private fun queueStop(activeAttempt: LifecycleAttempt, cause: StopCause): LifecycleAction {
    val stopFence = LifecycleStopFence(activeAttempt)
    state = LifecycleState.Stopping(
      activeAttempt = activeAttempt,
      stopFence = stopFence,
      afterStop = afterStopFor(cause),
    )
    return LifecycleAction.StopEngine(stopFence, cause)
  }

  private fun stopForFailure(
    activeAttempt: LifecycleAttempt,
    reason: FailureReason,
  ): LifecycleAction {
    val stopFence = LifecycleStopFence(activeAttempt)
    state = LifecycleState.Stopping(
      activeAttempt = activeAttempt,
      stopFence = stopFence,
      afterStop = AfterStop.Fail(reason),
    )
    return LifecycleAction.StopEngine(stopFence, StopCause.Failure)
  }

  private fun afterStopFor(cause: StopCause): AfterStop = when (cause) {
    StopCause.User,
    StopCause.Switch,
    StopCause.Reconnect,
    StopCause.Destroyed,
    -> AfterStop.Stop

    StopCause.Revoked -> AfterStop.Fail(FailureReason.Revoked)
    StopCause.Failure -> AfterStop.Fail(FailureReason.EngineFailure)
  }

  private fun terminalStateWithoutEngine(
    attempt: LifecycleAttempt,
    cause: StopCause,
  ): LifecycleState = when (cause) {
    StopCause.Revoked -> LifecycleState.Failed(attempt, FailureReason.Revoked)
    else -> LifecycleState.Stopped(attempt.engine)
  }

  private fun terminalAction(attempt: LifecycleAttempt, cause: StopCause): LifecycleAction = when (cause) {
    StopCause.Revoked -> LifecycleAction.Failed(attempt, FailureReason.Revoked)
    else -> LifecycleAction.Stopped(attempt)
  }

  private fun allocateAttempt(engine: EngineId): LifecycleAttempt? {
    val attemptId = nextAttemptId ?: return null
    val attempt = LifecycleAttempt(attemptId, engineEpoch, engine)
    nextAttemptId = if (attemptId == Long.MAX_VALUE) null else attemptId + 1L
    return attempt
  }

  private fun ignoredStaleOrIllegal(): LifecycleAction =
    LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent)
}

enum class EngineId {
  SingBox,
  TrustTunnel,
  VeilarkCoreCanary,
  ;

  companion object {
    /** Preserves the current missing-preference fallback during the W0O model phase. */
    val CurrentMissingPreferenceDefault: EngineId = SingBox
  }
}

data class LifecycleAttempt(
  val attemptId: Long,
  val engineEpoch: Long,
  val engine: EngineId,
)

/** A terminal-stop authority bound to one already-admitted engine attempt. */
data class LifecycleStopFence(val activeAttempt: LifecycleAttempt)

sealed interface LifecycleState {
  val selectedEngine: EngineId

  data class Stopped(override val selectedEngine: EngineId) : LifecycleState

  data class AwaitingPermission(val attempt: LifecycleAttempt) : LifecycleState {
    override val selectedEngine: EngineId = attempt.engine
  }

  data class Starting(val attempt: LifecycleAttempt) : LifecycleState {
    override val selectedEngine: EngineId = attempt.engine
  }

  data class Connected(val attempt: LifecycleAttempt) : LifecycleState {
    override val selectedEngine: EngineId = attempt.engine
  }

  data class Stopping(
    val activeAttempt: LifecycleAttempt,
    val stopFence: LifecycleStopFence,
    val afterStop: AfterStop,
  ) : LifecycleState {
    override val selectedEngine: EngineId = when (afterStop) {
      is AfterStop.Start -> afterStop.attempt.engine
      else -> activeAttempt.engine
    }
  }

  data class Failed(
    val attempt: LifecycleAttempt,
    val reason: FailureReason,
  ) : LifecycleState {
    override val selectedEngine: EngineId = attempt.engine
  }
}

sealed interface AfterStop {
  data object Stop : AfterStop

  data class Start(val attempt: LifecycleAttempt) : AfterStop

  data class Fail(val reason: FailureReason) : AfterStop
}

sealed interface LifecycleInput {
  data class Connect(val engine: EngineId) : LifecycleInput

  data class Reconnect(val attempt: LifecycleAttempt) : LifecycleInput

  data object Stop : LifecycleInput

  data object Revoke : LifecycleInput

  data object Destroy : LifecycleInput

  data class PermissionResult(
    val attempt: LifecycleAttempt,
    val granted: Boolean,
  ) : LifecycleInput

  data class EngineStarted(val attempt: LifecycleAttempt) : LifecycleInput

  data class EngineStopped(val stopFence: LifecycleStopFence) : LifecycleInput

  data class EngineFailed(
    val attempt: LifecycleAttempt,
    val reason: FailureReason = FailureReason.EngineFailure,
  ) : LifecycleInput

  data class Timeout(val attempt: LifecycleAttempt) : LifecycleInput
}

data class LifecycleDispatch(
  val previous: LifecycleState,
  val current: LifecycleState,
  val action: LifecycleAction,
)

sealed interface LifecycleAction {
  data class RequestVpnPermission(val attempt: LifecycleAttempt) : LifecycleAction

  data class StartEngine(val attempt: LifecycleAttempt) : LifecycleAction

  data class StopEngine(
    val stopFence: LifecycleStopFence,
    val cause: StopCause,
  ) : LifecycleAction

  data class QueuedAfterStop(val attempt: LifecycleAttempt) : LifecycleAction

  data class PendingStartCancelled(val cause: StopCause) : LifecycleAction

  data class Connected(val attempt: LifecycleAttempt) : LifecycleAction

  data class Stopped(val attempt: LifecycleAttempt) : LifecycleAction

  data class Failed(
    val attempt: LifecycleAttempt,
    val reason: FailureReason,
  ) : LifecycleAction

  data class Ignored(val reason: IgnoreReason) : LifecycleAction
}

enum class StopCause {
  User,
  Switch,
  Reconnect,
  Revoked,
  Destroyed,
  Failure,
}

enum class FailureReason {
  PermissionDenied,
  EngineFailure,
  Timeout,
  Revoked,
}

enum class IgnoreReason {
  AlreadyStopped,
  StopAlreadyFenced,
  TerminalAlreadyChosen,
  StaleOrIllegalEvent,
  AttemptIdExhausted,
}

/** Pure fence used immediately before every sing-box startup side effect. */
internal fun startupSideEffectAllowed(
  expectedStartupAttempt: Int,
  currentStartupAttempt: Int,
  expectedLifecycleAttempt: LifecycleAttempt?,
  activeLifecycleAttempt: LifecycleAttempt?,
): Boolean =
  expectedStartupAttempt == currentStartupAttempt &&
    expectedLifecycleAttempt != null &&
    expectedLifecycleAttempt == activeLifecycleAttempt

/**
 * Process-wide Android adapter for [AndroidTunnelLifecycleCoordinator].
 *
 * Android permits only one app VPN owner at a time. All UI, tile and service
 * requests enter this serialized owner; a later request supersedes an earlier
 * one, but cannot start until the previous engine has completed teardown.
 */
object AndroidTunnelLifecycleOwner {
  private const val STOP_TIMEOUT_MS = 8_000L
  private const val START_TIMEOUT_MS = 48_000L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val mutex = Mutex()
  private val commandSequence = AtomicLong(0L)
  private val commandVersion = MutableStateFlow(0L)
  private val coordinator = AndroidTunnelLifecycleCoordinator(
    engineEpoch = (System.nanoTime() and Long.MAX_VALUE).coerceAtLeast(1L),
  )

  fun startSingBox(context: Context, configPath: String) {
    submit(
      context = context,
      command = OwnerCommand.Start(EngineId.SingBox, configPath),
    )
  }

  fun startTrustTunnel(context: Context, config: String) {
    submit(
      context = context,
      command = OwnerCommand.Start(EngineId.TrustTunnel, config),
    )
  }

  fun stop(context: Context) {
    submit(context, OwnerCommand.Stop)
  }

  private fun submit(context: Context, command: OwnerCommand) {
    val sequence = commandSequence.incrementAndGet()
    commandVersion.value = sequence
    val appContext = context.applicationContext
    scope.launch {
      mutex.withLock {
        if (sequence != commandSequence.get()) return@withLock
        when (command) {
          is OwnerCommand.Start -> executeStart(appContext, command, sequence)
          OwnerCommand.Stop -> executeStop(appContext, sequence)
        }
      }
    }
  }

  private suspend fun executeStart(
    context: Context,
    command: OwnerCommand.Start,
    sequence: Long,
  ) {
    val stopping = coordinator.state as? LifecycleState.Stopping
    if (stopping != null) {
      stopEngine(context, stopping.activeAttempt)
      if (!awaitStopped(stopping.activeAttempt.engine)) return
      coordinator.dispatch(LifecycleInput.EngineStopped(stopping.stopFence))
      // The recovered after-stop intent may belong to an older command. Admit
      // the latest command again so its engine and payload always stay paired.
      executeAction(
        context,
        command,
        sequence,
        coordinator.dispatch(LifecycleInput.Connect(command.engine)).action,
      )
      return
    }
    val dispatch = coordinator.dispatch(LifecycleInput.Connect(command.engine))
    executeAction(context, command, sequence, dispatch.action)
  }

  private suspend fun executeStop(context: Context, sequence: Long) {
    val action = coordinator.dispatch(LifecycleInput.Stop).action
    executeAction(context, command = null, sequence = sequence, action = action)
    // Reconcile a service restored by Android after this process lost its model.
    stopForeignOrUntracked(context, keep = null)
  }

  private suspend fun executeAction(
    context: Context,
    command: OwnerCommand.Start?,
    sequence: Long,
    action: LifecycleAction,
  ) {
    when (action) {
      is LifecycleAction.RequestVpnPermission -> {
        // Activity/Tile already completed VpnService.prepare before entering here.
        executeAction(
          context,
          command,
          sequence,
          coordinator.dispatch(
            LifecycleInput.PermissionResult(action.attempt, granted = true),
          ).action,
        )
      }

      is LifecycleAction.StartEngine -> {
        if (command == null || sequence != commandSequence.get()) return
        if (!stopForeignOrUntracked(context, keep = action.attempt.engine)) return
        if (sequence != commandSequence.get()) {
          cancelSupersededAttempt(context, sequence)
          return
        }
        val launched = runCatching {
          when (action.attempt.engine) {
            EngineId.SingBox -> VeilarkVpnService.startEngine(
              context,
              command.payload,
              action.attempt,
            )
            EngineId.TrustTunnel -> TrustTunnelManager.startEngine(
              context,
              command.payload,
              action.attempt,
            )
            EngineId.VeilarkCoreCanary -> error("Veilark Core canary is not an Android engine")
          }
        }
        if (launched.isFailure) {
          TechnicalLogStore.error(
            "LIFECYCLE",
            "Android did not start ${action.attempt.engine.name}: " +
              launched.exceptionOrNull()?.javaClass?.simpleName,
          )
          executeAction(
            context,
            command = null,
            sequence = sequence,
            action = coordinator.dispatch(LifecycleInput.EngineFailed(action.attempt)).action,
          )
          return
        }
        awaitStartedOrSuperseded(context, action.attempt, sequence)
      }

      is LifecycleAction.StopEngine -> {
        stopEngine(context, action.stopFence.activeAttempt)
        if (!awaitStopped(action.stopFence.activeAttempt.engine)) return
        executeAction(
          context,
          command,
          sequence,
          coordinator.dispatch(LifecycleInput.EngineStopped(action.stopFence)).action,
        )
      }

      is LifecycleAction.QueuedAfterStop,
      is LifecycleAction.PendingStartCancelled,
      is LifecycleAction.Connected,
      is LifecycleAction.Stopped,
      is LifecycleAction.Failed,
      is LifecycleAction.Ignored,
      -> Unit
    }
  }

  private suspend fun awaitStartedOrSuperseded(
    context: Context,
    attempt: LifecycleAttempt,
    sequence: Long,
  ) {
    val result = withTimeoutOrNull(START_TIMEOUT_MS) {
      combine(stateFor(attempt.engine), commandVersion) { state, version -> state to version }
        .first { (state, version) ->
          version != sequence || state == ConnectionState.Connected ||
            state == ConnectionState.Failed || state == ConnectionState.Disconnected
        }
    }
    val superseded = commandSequence.get() != sequence
    val state = result?.first
    val action = when {
      superseded -> coordinator.dispatch(LifecycleInput.Stop).action
      state == ConnectionState.Connected ->
        coordinator.dispatch(LifecycleInput.EngineStarted(attempt)).action
      state == ConnectionState.Failed || state == ConnectionState.Disconnected ->
        coordinator.dispatch(LifecycleInput.EngineFailed(attempt)).action
      else -> coordinator.dispatch(LifecycleInput.Timeout(attempt)).action
    }
    executeAction(
      context = context,
      command = null,
      sequence = sequence,
      action = action,
    )
  }

  private suspend fun cancelSupersededAttempt(
    context: Context,
    sequence: Long,
  ) {
    val action = coordinator.dispatch(LifecycleInput.Stop).action
    executeAction(context, null, sequence, action)
  }

  private suspend fun stopForeignOrUntracked(
    context: Context,
    keep: EngineId?,
  ): Boolean {
    if (keep != EngineId.SingBox && !VeilarkVpnService.stopped.value) {
      VeilarkVpnService.stopEngine(context)
      if (!awaitStopped(EngineId.SingBox)) return false
    }
    if (keep != EngineId.TrustTunnel && !TrustTunnelManager.stopped.value) {
      TrustTunnelManager.stopEngine(context)
      if (!awaitStopped(EngineId.TrustTunnel)) return false
    }
    return true
  }

  private fun stopEngine(context: Context, attempt: LifecycleAttempt) {
    when (attempt.engine) {
      EngineId.SingBox -> VeilarkVpnService.stopEngine(context, attempt)
      EngineId.TrustTunnel -> TrustTunnelManager.stopEngine(context, attempt)
      EngineId.VeilarkCoreCanary -> Unit
    }
  }

  private suspend fun awaitStopped(engine: EngineId): Boolean {
    val stopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
      when (engine) {
        EngineId.SingBox -> VeilarkVpnService.stopped.first { it }
        EngineId.TrustTunnel -> TrustTunnelManager.stopped.first { it }
        EngineId.VeilarkCoreCanary -> stateFor(engine).first { state -> !state.isActive() }
      }
      true
    } ?: false
    if (!stopped) {
      TechnicalLogStore.warning(
        "LIFECYCLE",
        "${engine.name} stop timed out; starting a new tunnel is blocked",
      )
    }
    return stopped
  }

  private fun stateFor(engine: EngineId) = when (engine) {
    EngineId.SingBox -> VeilarkVpnService.state
    EngineId.TrustTunnel -> TrustTunnelManager.state
    EngineId.VeilarkCoreCanary -> VeilarkVpnService.state
  }

  private fun ConnectionState.isActive(): Boolean =
    this == ConnectionState.Connecting || this == ConnectionState.Connected

  private sealed interface OwnerCommand {
    data class Start(val engine: EngineId, val payload: String) : OwnerCommand
    data object Stop : OwnerCommand
  }
}
