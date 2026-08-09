package com.example.veilark.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTunnelLifecycleCoordinatorTest {
  @Test
  fun missingPreferenceDefaultPreservesCurrentSingBoxSelection() {
    val coordinator = coordinator()

    assertEquals(EngineId.SingBox, EngineId.CurrentMissingPreferenceDefault)
    assertEquals(EngineId.SingBox, coordinator.state.selectedEngine)
  }

  @Test
  fun permissionAfterStopIsIgnoredWithoutResurrectingAttempt() {
    val coordinator = coordinator()
    val request = coordinator.connect(EngineId.VeilarkCoreCanary)
    coordinator.dispatch(LifecycleInput.Stop)

    val lateResult = coordinator.dispatch(LifecycleInput.PermissionResult(request, granted = true))

    assertEquals(LifecycleState.Stopped(EngineId.VeilarkCoreCanary), coordinator.state)
    assertEquals(LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent), lateResult.action)
  }

  @Test
  fun secondConnectInvalidatesPendingPermissionAndUsesMonotonicAttempt() {
    val coordinator = coordinator()
    val first = coordinator.connect(EngineId.SingBox)
    val second = coordinator.connect(EngineId.TrustTunnel)

    assertTrue(second.attemptId > first.attemptId)
    assertEquals(EngineId.TrustTunnel, coordinator.state.selectedEngine)
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.PermissionResult(first, granted = true)).action,
    )
    assertEquals(
      LifecycleAction.StartEngine(second),
      coordinator.dispatch(LifecycleInput.PermissionResult(second, granted = true)).action,
    )
  }

  @Test
  fun staleCallbackAndTimeoutCannotChangeNewerConnectedAttempt() {
    val coordinator = coordinator()
    val old = coordinator.connect(EngineId.SingBox)
    val current = coordinator.connect(EngineId.TrustTunnel)
    coordinator.dispatch(LifecycleInput.PermissionResult(current, granted = true))
    coordinator.dispatch(LifecycleInput.EngineStarted(current))

    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineStarted(old)).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.Timeout(old)).action,
    )
    assertEquals(LifecycleState.Connected(current), coordinator.state)
  }

  @Test
  fun switchWaitsForTerminalStopFenceBeforeRequestingPermission() {
    val coordinator = coordinator()
    val active = connectToConnected(coordinator, EngineId.TrustTunnel)

    val switching = coordinator.dispatch(LifecycleInput.Connect(EngineId.VeilarkCoreCanary))
    val stop = requireType<LifecycleAction.StopEngine>(switching.action)
    val stopping = requireType<LifecycleState.Stopping>(coordinator.state)
    val pending = requireType<AfterStop.Start>(stopping.afterStop).attempt

    assertEquals(active, stopping.activeAttempt)
    assertEquals(active, stop.stopFence.activeAttempt)
    assertTrue(pending.attemptId > active.attemptId)
    assertEquals(EngineId.VeilarkCoreCanary, pending.engine)
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineStarted(active)).action,
    )

    assertEquals(
      LifecycleAction.RequestVpnPermission(pending),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
    assertEquals(LifecycleState.AwaitingPermission(pending), coordinator.state)
  }

  @Test
  fun reconnectAlsoUsesStopBarrierAndNeverFallsBack() {
    val coordinator = coordinator()
    val active = connectToConnected(coordinator, EngineId.VeilarkCoreCanary)

    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Reconnect(active)).action)
    val pending = requireType<AfterStop.Start>(requireType<LifecycleState.Stopping>(coordinator.state).afterStop).attempt

    assertEquals(EngineId.VeilarkCoreCanary, pending.engine)
    assertEquals(active, stop.stopFence.activeAttempt)
    coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence))
    val denied = coordinator.dispatch(LifecycleInput.PermissionResult(pending, granted = false))

    assertEquals(LifecycleAction.Failed(pending, FailureReason.PermissionDenied), denied.action)
    assertEquals(LifecycleState.Failed(pending, FailureReason.PermissionDenied), coordinator.state)
  }

  @Test
  fun revokeFencesStartAndPublishesTypedFailureOnlyAfterStopCompletion() {
    val coordinator = coordinator()
    val active = connectToConnected(coordinator, EngineId.SingBox)

    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Revoke).action)
    assertEquals(StopCause.Revoked, stop.cause)
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineFailed(active)).action,
    )
    assertEquals(
      LifecycleAction.Failed(active, FailureReason.Revoked),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun destroyBeforePermissionInvalidatesPermissionResultWithoutEngineStop() {
    val coordinator = coordinator()
    val request = coordinator.connect(EngineId.TrustTunnel)

    assertEquals(LifecycleAction.Stopped(request), coordinator.dispatch(LifecycleInput.Destroy).action)
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.PermissionResult(request, granted = true)).action,
    )
  }

  @Test
  fun epochMismatchIsRejectedEvenWhenAttemptIdMatches() {
    val coordinator = coordinator(epoch = 7)
    val request = coordinator.connect(EngineId.SingBox)
    val foreignEpoch = request.copy(engineEpoch = 8)

    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.PermissionResult(foreignEpoch, granted = true)).action,
    )
    assertEquals(LifecycleState.AwaitingPermission(request), coordinator.state)
  }

  @Test
  fun repeatedStopIsIdempotentAndCannotDoubleStopEngine() {
    val coordinator = coordinator()
    connectToConnected(coordinator, EngineId.TrustTunnel)

    val firstStop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Stop).action)
    val secondStop = coordinator.dispatch(LifecycleInput.Stop)

    assertEquals(LifecycleAction.Ignored(IgnoreReason.TerminalAlreadyChosen), secondStop.action)
    assertEquals(
      LifecycleAction.Stopped(requireType<LifecycleState.Stopping>(coordinator.state).activeAttempt),
      coordinator.dispatch(LifecycleInput.EngineStopped(firstStop.stopFence)).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.AlreadyStopped),
      coordinator.dispatch(LifecycleInput.Stop).action,
    )
  }

  @Test
  fun startingTimeoutRequiresStopFenceBeforeFailure() {
    val coordinator = coordinator()
    val request = coordinator.connect(EngineId.SingBox)
    coordinator.dispatch(LifecycleInput.PermissionResult(request, granted = true))

    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Timeout(request)).action)
    assertEquals(StopCause.Failure, stop.cause)
    assertEquals(
      LifecycleAction.Failed(request, FailureReason.Timeout),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun activeAttemptAtMaxCanStillStopWithoutAllocatingAnotherAttemptId() {
    val coordinator = coordinator(initialAttemptId = Long.MAX_VALUE)
    val active = connectToConnected(coordinator, EngineId.TrustTunnel)

    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Stop).action)

    assertEquals(active, stop.stopFence.activeAttempt)
    assertEquals(
      LifecycleAction.Stopped(active),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun reconnectRequiresTheExactCurrentConnectedAttempt() {
    val coordinator = coordinator()
    val active = connectToConnected(coordinator, EngineId.TrustTunnel)

    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.Reconnect(active.copy(attemptId = active.attemptId + 1L))).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.Reconnect(active.copy(engineEpoch = active.engineEpoch + 1L))).action,
    )
    val stop = requireType<LifecycleAction.StopEngine>(
      coordinator.dispatch(LifecycleInput.Reconnect(active)).action,
    )

    assertEquals(active, stop.stopFence.activeAttempt)
  }

  @Test
  fun terminalCommandsRejectReconnectAndConnectAfterStopDestroyOrRevoke() {
    val cases = listOf(LifecycleInput.Stop, LifecycleInput.Destroy, LifecycleInput.Revoke)

    for (terminal in cases) {
      val coordinator = coordinator()
      val active = connectToConnected(coordinator, EngineId.TrustTunnel)
      coordinator.dispatch(terminal)
      val before = coordinator.state

      assertEquals(
        LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
        coordinator.dispatch(LifecycleInput.Reconnect(active)).action,
      )
      assertEquals(
        LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
        coordinator.dispatch(LifecycleInput.Connect(EngineId.VeilarkCoreCanary)).action,
      )
      assertEquals(before, coordinator.state)
    }
  }

  @Test
  fun maxAttemptCanStopRevokeAndDestroyWithoutNewAttemptAllocation() {
    val cases = listOf(
      LifecycleInput.Stop to LifecycleAction.Stopped::class,
      LifecycleInput.Destroy to LifecycleAction.Stopped::class,
      LifecycleInput.Revoke to LifecycleAction.Failed::class,
    )

    for ((terminal, expectedType) in cases) {
      val coordinator = coordinator(initialAttemptId = Long.MAX_VALUE)
      val active = connectToConnected(coordinator, EngineId.TrustTunnel)
      val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(terminal).action)

      assertEquals(active, stop.stopFence.activeAttempt)
      val completion = coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action
      assertTrue(expectedType.isInstance(completion))
    }
  }

  @Test
  fun firstTerminalCommandCancelsPendingStartAndWinsAllLaterTerminalCommands() {
    val orders = listOf(
      LifecycleInput.Stop to LifecycleInput.Revoke,
      LifecycleInput.Revoke to LifecycleInput.Destroy,
      LifecycleInput.Destroy to LifecycleInput.Stop,
    )

    for ((first, second) in orders) {
      val coordinator = coordinator()
      connectToConnected(coordinator, EngineId.TrustTunnel)
      coordinator.dispatch(LifecycleInput.Connect(EngineId.VeilarkCoreCanary))

      val firstAction = coordinator.dispatch(first).action
      assertTrue(firstAction is LifecycleAction.PendingStartCancelled)
      val chosen = requireType<LifecycleState.Stopping>(coordinator.state).afterStop
      assertEquals(
        LifecycleAction.Ignored(IgnoreReason.TerminalAlreadyChosen),
        coordinator.dispatch(second).action,
      )
      assertEquals(chosen, requireType<LifecycleState.Stopping>(coordinator.state).afterStop)
    }
  }

  @Test
  fun pendingStartCanBeReplacedOnlyBeforeTheFirstTerminalCommand() {
    val coordinator = coordinator()
    connectToConnected(coordinator, EngineId.SingBox)
    coordinator.dispatch(LifecycleInput.Connect(EngineId.TrustTunnel))

    val replacement = requireType<LifecycleAction.QueuedAfterStop>(
      coordinator.dispatch(LifecycleInput.Connect(EngineId.VeilarkCoreCanary)).action,
    )
    assertEquals(EngineId.VeilarkCoreCanary, replacement.attempt.engine)
    assertEquals(
      LifecycleAction.PendingStartCancelled(StopCause.User),
      coordinator.dispatch(LifecycleInput.Stop).action,
    )
    val afterTerminal = coordinator.state
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.Connect(EngineId.SingBox)).action,
    )
    assertEquals(afterTerminal, coordinator.state)
  }

  @Test
  fun wrongStaleAndForeignEpochStopCompletionsAreIgnored() {
    val coordinator = coordinator(epoch = 3)
    val active = connectToConnected(coordinator, EngineId.SingBox)
    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Stop).action)
    val wrong = LifecycleStopFence(active.copy(attemptId = active.attemptId + 1L))
    val foreign = LifecycleStopFence(active.copy(engineEpoch = 4L))

    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineStopped(wrong)).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineStopped(foreign)).action,
    )
    assertEquals(
      LifecycleAction.Stopped(active),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun connectedEngineFailureUsesBoundStopFenceBeforeTypedFailure() {
    val coordinator = coordinator()
    val active = connectToConnected(coordinator, EngineId.VeilarkCoreCanary)

    val stop = requireType<LifecycleAction.StopEngine>(
      coordinator.dispatch(LifecycleInput.EngineFailed(active)).action,
    )

    assertEquals(StopCause.Failure, stop.cause)
    assertEquals(active, stop.stopFence.activeAttempt)
    assertEquals(
      LifecycleAction.Failed(active, FailureReason.EngineFailure),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun maxAttemptConnectedFailureStillStopsAndPublishesTypedFailure() {
    val coordinator = coordinator(initialAttemptId = Long.MAX_VALUE)
    val active = connectToConnected(coordinator, EngineId.TrustTunnel)

    val stop = requireType<LifecycleAction.StopEngine>(
      coordinator.dispatch(LifecycleInput.EngineFailed(active)).action,
    )

    assertEquals(active, stop.stopFence.activeAttempt)
    assertEquals(
      LifecycleAction.Failed(active, FailureReason.EngineFailure),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun maxAttemptStartingTimeoutStillStopsAndPublishesTypedFailure() {
    val coordinator = coordinator(initialAttemptId = Long.MAX_VALUE)
    val active = coordinator.connect(EngineId.SingBox)
    coordinator.dispatch(LifecycleInput.PermissionResult(active, granted = true))

    val stop = requireType<LifecycleAction.StopEngine>(coordinator.dispatch(LifecycleInput.Timeout(active)).action)

    assertEquals(active, stop.stopFence.activeAttempt)
    assertEquals(
      LifecycleAction.Failed(active, FailureReason.Timeout),
      coordinator.dispatch(LifecycleInput.EngineStopped(stop.stopFence)).action,
    )
  }

  @Test
  fun foreignEpochStartFailureAndTimeoutEventsAreIgnored() {
    val coordinator = coordinator(epoch = 11)
    val request = coordinator.connect(EngineId.SingBox)
    coordinator.dispatch(LifecycleInput.PermissionResult(request, granted = true))
    val foreign = request.copy(engineEpoch = 12)

    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineStarted(foreign)).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.EngineFailed(foreign)).action,
    )
    assertEquals(
      LifecycleAction.Ignored(IgnoreReason.StaleOrIllegalEvent),
      coordinator.dispatch(LifecycleInput.Timeout(foreign)).action,
    )
    assertEquals(LifecycleState.Starting(request), coordinator.state)
  }

  private fun coordinator(
    epoch: Long = 1,
    initialAttemptId: Long = 1,
  ) = AndroidTunnelLifecycleCoordinator(
    initialEngine = EngineId.CurrentMissingPreferenceDefault,
    engineEpoch = epoch,
    initialAttemptId = initialAttemptId,
  )

  private fun AndroidTunnelLifecycleCoordinator.connect(engine: EngineId): LifecycleAttempt {
    return requireType<LifecycleAction.RequestVpnPermission>(dispatch(LifecycleInput.Connect(engine)).action).attempt
  }

  private inline fun <reified T> requireType(value: Any): T {
    assertTrue("Expected ${T::class.simpleName}, got ${value::class.simpleName}", value is T)
    @Suppress("UNCHECKED_CAST")
    return value as T
  }

  private fun connectToConnected(
    coordinator: AndroidTunnelLifecycleCoordinator,
    engine: EngineId,
  ): LifecycleAttempt {
    val request = coordinator.connect(engine)
    assertEquals(
      LifecycleAction.StartEngine(request),
      coordinator.dispatch(LifecycleInput.PermissionResult(request, granted = true)).action,
    )
    assertEquals(
      LifecycleAction.Connected(request),
      coordinator.dispatch(LifecycleInput.EngineStarted(request)).action,
    )
    return request
  }
}
