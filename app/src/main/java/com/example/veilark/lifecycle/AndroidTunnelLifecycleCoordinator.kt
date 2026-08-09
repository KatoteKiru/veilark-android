package com.example.veilark.lifecycle

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
