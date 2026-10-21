package com.example.veilark.core.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.net.VpnService
import android.net.ConnectivityManager
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
import com.example.veilark.diagnostics.TechnicalLogStore
import com.example.veilark.BuildConfig
import com.example.veilark.lifecycle.LifecycleAttempt
import com.example.veilark.profile.CoreBetaProfile
import com.example.veilark.profile.CoreArgonProfile
import com.example.veilark.profile.CoreArgonHandshakeProfile
import com.example.veilark.profile.CoreArgonProfileStore
import com.example.veilark.profile.CoreProfileKind
import com.example.veilark.profile.CoreProfileStore
import com.example.veilark.profile.ApplicationRoutingPolicy
import com.example.veilark.profile.ApplicationRoutingUnavailableException
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.CoreBetaProfileStore
import com.example.veilark.vpn.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/** IPv4-only beta: retained TUN blocks traffic during reconnect, never changes engine. */
class CoreBetaVpnService : VpnService() {
  private class Session(val attempt: LifecycleAttempt) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val lifecycle = CoreBetaLifecycle(object : CoreBetaHandleApi {
      override fun status(handle: Long) = VeilarkCoreRuntimeNative.nativeStatus(handle)
      override fun failure(handle: Long) = try { VeilarkCoreRuntimeNative.nativeFailureV1(handle) }
        catch (_: UnsatisfiedLinkError) { 0 } // Preparation remains compatible with beta2's native ABI.
      override fun progress(handle: Long, field: Int) = try { VeilarkCoreRuntimeNative.nativeProgressV1(handle, field) }
        catch (_: UnsatisfiedLinkError) { -4L }
      override fun stop(handle: Long) = VeilarkCoreRuntimeNative.nativeStop(handle)
      override fun release(handle: Long) = VeilarkCoreRuntimeNative.nativeRelease(handle)
    })
    val argonLifecycle = CoreArgonLifecycle(object : CoreArgonHandleApi {
      override fun awaitAuthenticated(handle: Long, output: IntArray) = VeilarkArgonNative.nativeAwaitAuthenticated(handle, output)
      override fun awaitAuthenticatedV2(handle: Long, output: IntArray) =
        if (BuildConfig.ARGON_ACTIVE_FEEDBACK_ENABLED) VeilarkArgonNative.nativeAwaitAuthenticatedV2(handle, output) else -3
      override fun attachTun(handle: Long, borrowedTunFd: Int) = VeilarkArgonNative.nativeAttachTun(handle, borrowedTunFd)
      override fun attachRuDns(handle: Long, borrowedResolverFd: Int) = VeilarkArgonNative.nativeAttachRuDns(handle, borrowedResolverFd)
      override fun awaitRunning(handle: Long) = VeilarkArgonNative.nativeAwaitRunning(handle)
      override fun awaitTerminal(handle: Long) = VeilarkArgonNative.nativeAwaitTerminal(handle)
      override fun status(handle: Long) = VeilarkArgonNative.nativeStatus(handle)
      override fun stop(handle: Long) = VeilarkArgonNative.nativeStop(handle)
      override fun join(handle: Long) = VeilarkArgonNative.nativeJoin(handle)
      override fun terminalOutcome(handle: Long): Int? =
        if (BuildConfig.ARGON_TERMINAL_OUTCOME_ENABLED) VeilarkArgonNative.nativeTerminalOutcome(handle) else null
      override fun release(handle: Long) = VeilarkArgonNative.nativeRelease(handle)
    })
    var diagnosticStage = "SERVICE"
    var lastFailureCode: String? = null
    @Volatile var networkDiagnostics: CoreNetworkDiagnostics? = null
  }
  private val sessions = CoreBetaSessionGate<Session>()
  @Volatile private var destroyed = false

  private fun coreInfo(message: String) =
    TechnicalLogStore.info("CORE", "${getString(R.string.core_beta_title)} $message")

  private fun coreError(message: String) =
    TechnicalLogStore.error("CORE", "${getString(R.string.core_beta_title)} $message")

  private fun stage(session: Session, value: String) {
    session.diagnosticStage = value // Only fixed local stage names, never profile or exception text.
    coreInfo("stage=$value")
  }

  private fun recordFailure(session: Session, failure: Throwable) {
    val detail = when (failure) {
      is CoreBetaStartFailure -> " start_code=${failure.code}"
      is ErrnoException -> " errno=${failure.errno}"
      else -> " class=${failure.javaClass.simpleName}"
    }
    session.lastFailureCode = "CORE-${session.diagnosticStage}"
    coreError("stage=${session.diagnosticStage}$detail")
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      val stopping = sessions.current()
      val accepted = handleCoreStopCommand(stopping?.attempt,
        intent.getLongExtra("attempt", -1), intent.getLongExtra("epoch", -1)) {
          coreInfo("stop_command_received")
          // Do not wait for bound-service destruction to cancel its worker.
          stopping?.scope?.cancel()
        }
      if (!accepted && sessions.current() == null && !CoreBetaManager.hasPendingStart()) {
        stopSelf(startId)
      }
      return START_NOT_STICKY
    }
    if (destroyed || sessions.current() != null) return START_NOT_STICKY
    val attempt = CoreBetaManager.attachAttempt(intent?.getLongExtra("attempt", -1) ?: -1,
      intent?.getLongExtra("epoch", -1) ?: -1)
    if (attempt == null) { stopSelf(startId); return START_NOT_STICKY }
    val session = Session(attempt)
    check(sessions.admit(session))
    try {
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.core_beta_title), NotificationManager.IMPORTANCE_LOW))
      val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val notice = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_vpn_status).setContentTitle(getString(R.string.core_beta_title))
        .setContentText(getString(R.string.core_beta_notification_text)).setContentIntent(open).setOngoing(true).build()
      if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      else startForeground(NOTIFICATION, notice)
    } catch (failure: Exception) {
      recordFailure(session, failure)
      publish(session, ConnectionState.Failed, getString(R.string.core_beta_connection_failed) + " [CORE-SERVICE]")
      session.scope.cancel()
      sessions.resources(session, native = true, tun = true)
      sessions.terminal(session) // Startup failed before any worker or descriptor existed.
      finishServiceEffects(session)
      completeSession(session)
      return START_NOT_STICKY
    }
    // Atomic entry guarantees cleanup even when cancellation precedes dispatch.
    val worker = session.scope.launch(start = CoroutineStart.ATOMIC) { runTunnel(session) }
    worker.invokeOnCompletion {
      session.scope.cancel()
      sessions.terminal(session)
      completeSession(session)
    }
    return START_NOT_STICKY
  }

  private suspend fun runTunnel(session: Session) {
    val selected: CoreProfileKind
    val routingSnapshot: Map<String, *>
    try {
      currentCoroutineContext().ensureActive()
      val diagnostics = CoreNetworkDiagnostics(
        getSystemService(ConnectivityManager::class.java), ::coreInfo,
      )
      session.networkDiagnostics = diagnostics
      // If destruction raced publication of the observer, scope completion still
      // closes it. Registration itself also compensates for concurrent close.
      session.scope.coroutineContext[Job]?.invokeOnCompletion { diagnostics.close() }
      diagnostics.start()
      stage(session, "PROFILE_SELECTION")
      // The selection facade serializes import/selection on this same monitor.
      // Keep the support preflight and dispatched identity in one admission boundary.
      val admission = synchronized(CoreProfileStore) {
        CoreBetaManager.requireProfileSupported(this)
        val snapshot = getSharedPreferences("profile_meta", MODE_PRIVATE).all.mapValues { (_, value) ->
          if (value is Set<*>) value.toSet() else value
        }
        val kind = checkNotNull(CoreProfileKind.resolve(snapshot["core_profile_kind"],
          CoreBetaProfileStore.exists(this), CoreArgonProfileStore.exists(this)))
        kind to snapshot
      }
      selected = admission.first
      routingSnapshot = admission.second
      currentCoroutineContext().ensureActive()
    } catch (failure: Throwable) {
      if (failure !is CancellationException) {
        recordFailure(session, failure)
        publish(session, ConnectionState.Failed, getString(R.string.core_beta_connection_failed) + " [CORE-PROFILE_SELECTION]")
      }
      // Neither engine owns resources yet; the worker completion marks terminal.
      sessions.resources(session, native = true, tun = true)
      finishServiceEffects(session)
      return
    }
    when (selected) {
      CoreProfileKind.LEGACY_V1 -> runLegacyTunnel(session, routingSnapshot)
      CoreProfileKind.ARGON_V1 -> runArgonTunnel(session, routingSnapshot)
    }
  }

  private suspend fun runLegacyTunnel(session: Session, routingSnapshot: Map<String, *>) {
    var tun: ParcelFileDescriptor? = null
    var loadedProfile: CoreBetaProfile? = null
    try {
      currentCoroutineContext().ensureActive()
      stage(session, "NATIVE_LOAD")
      check(VeilarkCoreRuntimeNative.loadCandidate())
      stage(session, "PROFILE_STORE")
      val profile = CoreBetaProfileStore.load(this)
      loadedProfile = profile
      currentCoroutineContext().ensureActive()
      val routePlan = prepareRouting(routingSnapshot, CoreL3DnsPolicy.effective(profile.dnsIpv4).resolvers)
      val builder = tunBuilder(session, profile.clientIpv4, 1280, profile.dnsIpv4, routingSnapshot, routePlan)
      stage(session, "TUN_ESTABLISH")
      currentCoroutineContext().ensureActive()
      val activeTun = checkNotNull(builder.establish())
      tun = activeTun
      var consecutiveFailures = 0
      while (true) {
        currentCoroutineContext().ensureActive()
        publish(session, ConnectionState.Connecting)
        var reachedReady = false
        var readyAtMillis: Long? = null
        var plannedRotation = false
        var lastProgressAt = 0L
        try {
          startSession(session, activeTun, profile)
          val deadline = SystemClock.elapsedRealtime() + 15_000
          while (true) {
            currentCoroutineContext().ensureActive()
            val status = session.lifecycle.status() ?: error("Missing session")
            if (status == 1 && !reachedReady) {
              reachedReady = true
              readyAtMillis = SystemClock.elapsedRealtime()
              stage(session, "AUTHENTICATED")
              publish(session, ConnectionState.Connected)
            }
            val now = SystemClock.elapsedRealtime()
            if (reachedReady && (lastProgressAt == 0L || now - lastProgressAt >= 5_000)) {
              recordProgress(session)
              lastProgressAt = now
            }
            if (status == 3 || status < 0) {
              recordProgress(session)
              if (status < 0) {
                val reason = session.lifecycle.failure() ?: 0
                session.lastFailureCode = "CORE-NATIVE($status/$reason)"
                val detail = if (reason == 11) session.lifecycle.preAuthFailureDetail() else null
                val detailText = detail?.let { " preauth_detail=${it.code} preauth_kind=${it.name}" }.orEmpty()
                coreError("native_status=$status native_reason=$reason ready=$reachedReady$detailText")
              }
              break
            }
            // Only our own timer initiates a planned rotation. A native timeout,
            // EOF or failed session never becomes healthy because it lived long.
            if (CoreBetaReconnectPolicy.shouldRotate(status, readyAtMillis, now, profile.lifetimeSeconds)) {
              plannedRotation = true
              coreInfo("rotation=scheduled")
              break
            }
            if (!reachedReady && SystemClock.elapsedRealtime() >= deadline) error("Authentication timeout")
            delay(250)
          }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { recordFailure(session, failure) }
        finally {
          if (plannedRotation) {
            val outcome = session.lifecycle.stopAndReleaseForRotation()
            check(outcome.released) { "Core cleanup did not complete" }
            plannedRotation = outcome.allowsPlannedRotation
            if (!plannedRotation) {
              session.lastFailureCode = "CORE-NATIVE(${outcome.terminalStatus}/${outcome.terminalReason})"
              coreError("rotation=not_confirmed native_status=${outcome.terminalStatus} " +
                "native_reason=${outcome.terminalReason}")
            }
          } else {
            check(session.lifecycle.stopAndRelease()) { "Core cleanup did not complete" }
          }
        }
        currentCoroutineContext().ensureActive()
        // Keep routes while recovering, but do not trap a failed connection in
        // endless retries. Terminal failure is visible and cleanup is fenced.
        consecutiveFailures = CoreBetaReconnectPolicy.nextFailureCount(consecutiveFailures, plannedRotation)
        if (!plannedRotation) coreInfo("reconnect_backoff_ms=${CoreBetaReconnectPolicy.delayMillis(consecutiveFailures)}")
        publish(session, ConnectionState.Connecting)
        delay(if (plannedRotation) 250 else CoreBetaReconnectPolicy.delayMillis(consecutiveFailures))
      }
    } catch (_: CancellationException) {
      // Explicit stop/revoke is completed only after native ownership release.
    } catch (failure: Throwable) {
      if (session.lastFailureCode == null) recordFailure(session, failure)
      val message = if (failure is ApplicationRoutingUnavailableException)
        getString(R.string.core_beta_apps_unavailable) else getString(R.string.core_beta_connection_failed)
      publish(session, ConnectionState.Failed, message + " [${session.lastFailureCode ?: "CORE-UNKNOWN"}]")
    } finally {
      val released = runCatching { session.lifecycle.stopAndRelease() }.getOrDefault(false)
      runCatching { loadedProfile?.close() }
      val tunClosed = runCatching { tun?.close(); true }.getOrDefault(false)
      sessions.resources(session, native = released, tun = tunClosed)
      if (!released || !tunClosed) publish(session, ConnectionState.Failed, getString(R.string.core_beta_cleanup_failed))
      if (released && tunClosed) finishServiceEffects(session)
      else {
        CoreBetaLateCleanup(
          cleanup = {
            val native = session.lifecycle.stopAndRelease()
            val closed = runCatching { tun?.close(); true }.getOrDefault(false)
            sessions.resources(session, native, closed)
            native && closed
          },
          onReleased = { finishServiceEffects(session); completeSession(session) },
          event = { coreInfo(it) },
        ).launchOnce()
      }

    }
  }

  private fun recordArgonFailure(session: Session, failure: Throwable) {
    session.lastFailureCode = "CORE-${session.diagnosticStage}"
    val detail = when (failure) {
      is CoreArgonFailure -> " core_code=${failure.code}"
      is ErrnoException -> " errno=${failure.errno}"
      else -> " failure=1"
    }
    coreError("stage=${session.diagnosticStage}$detail")
  }

  private suspend fun runArgonTunnel(session: Session, routingSnapshot: Map<String, *>) {
    var loadedProfile: CoreArgonProfile? = null
    val originals = mutableListOf<ParcelFileDescriptor>()
    val tunnels = mutableListOf<ParcelFileDescriptor>()
    var activeTun: ParcelFileDescriptor? = null
    var networkOwner: CoreFairwayNetworkOwner? = null
    try {
      currentCoroutineContext().ensureActive()
      stage(session, "CORE_NATIVE_LOAD")
      check(VeilarkArgonNative.isAbiAvailable())
      stage(session, "CORE_PROFILE_STORE")
      val profile = CoreArgonProfileStore.load(this)
      loadedProfile = profile
      // Defence in depth for direct service entry; the manager checks this
      // before handing over an existing VPN. A v2 profile cannot downgrade.
      check(VeilarkArgonNative.isProfileAbiAvailable(profile.handshakeProfile))
      // Validate policy before UDP admission. This does not establish a TUN or
      // infer its address; only authenticated native output may supply that.
      stage(session, "ROUTING_RULES")
      val dns = CoreL3DnsPolicy.effective(profile.dnsIpv4)
      val routePlan = prepareRouting(routingSnapshot, dns.resolvers, CoreFairwayIntegration.ENABLED)
      val nativeRoutingMode = routePlan.nativeMode
      val fairwayPolicy = routePlan.fairwayPolicy
      if (fairwayPolicy != null) {
        networkOwner = CoreFairwayNetworkOwner(getSystemService(ConnectivityManager::class.java), ::protect)
        networkOwner.start()
      }
      val peer = ipv4(profile.endpointIpv4) as Inet4Address
      var consecutiveFailures = 0
      while (true) {
        currentCoroutineContext().ensureActive()
        publish(session, ConnectionState.Connecting)
        var ticket: CoreArgonHandleTicket? = null
        val networkStopSucceeded = AtomicBoolean(false)
        val callbackFailed = AtomicBoolean(false)
        var reachedRunning = false
        var terminalStatus: Int? = null
        var attemptFailure: Throwable? = null
        try {
          val networks = networkOwner
          val capture = if (networks != null) {
            stage(session, "CORE_AWAIT_NETWORK")
            networks.captures.filterNotNull().first()
          } else null
          stage(session, "CORE_UDP_OPEN")
          val socket = CoreProtectedUdp.open(peer, profile.endpointPort, ::protect, capture?.binding)
          originals += socket
          var secret: ByteArray? = null
          var pin: ByteArray? = null
          try {
            secret = profile.clientStaticPrivateCopy()
            pin = profile.serverStaticPublicCopy()
            val privateKey = checkNotNull(secret)
            val publicPin = checkNotNull(pin)
            stage(session, "CORE_BEGIN")
            ticket = session.argonLifecycle.startBorrowedWithProfile(
              profile = profile.handshakeProfile,
              begin = {
                when (profile.handshakeProfile) {
                  CoreArgonHandshakeProfile.LEGACY -> VeilarkArgonNative.nativeBegin(
                    socket.fd, peer.address, profile.endpointPort, profile.mtu,
                    privateKey, publicPin, nativeRoutingMode)
                  CoreArgonHandshakeProfile.CARRIER2_LAB1 -> VeilarkArgonNative.nativeBeginV2(
                    socket.fd, peer.address, profile.endpointPort, profile.mtu,
                    privateKey, publicPin, nativeRoutingMode,
                    profile.handshakeProfile.wireProfile, profile.handshakeProfile.feedbackVersion)
                }
              },
              closeOriginals = { closeTracked(originals, socket) },
            )
          } finally {
            secret?.fill(0); pin?.fill(0)
          }
          val admitted = checkNotNull(ticket)
          if (capture != null && networks != null) {
            session.argonLifecycle.startCallbacks(admitted, session.scope) {
              // A lease check alone cannot wake a parked native authentication,
              // observer or admission ACK. Actively stop this immutable attempt.
              networks.captures.first { it?.epoch != capture.epoch }
              val code = VeilarkArgonNative.nativeStop(admitted.handle)
              if (code == 0) networkStopSucceeded.set(true)
              if (code != 0) {
                callbackFailed.set(true)
                recordArgonFailure(session, CoreArgonFailure("network_stop", code.toLong()))
                session.scope.cancel() // Main owner performs cleanup; this worker never joins itself.
              }
            }
          }
          stage(session, "CORE_AUTHENTICATING")
          val authenticated = session.argonLifecycle.awaitAuthenticated(admitted)
          currentCoroutineContext().ensureActive()
          if (fairwayPolicy != null) {
            stage(session, "FAIRWAY_CONFIGURE")
            val owner = checkNotNull(networks)
            val host = CoreFairwayNativeHost(CoreFairwayArgonApi, admitted.handle,
              checkNotNull(capture), CoreFairwayIntegration.POLICY_GENERATION, owner::isCurrent)
            host.configure(fairwayPolicy.networks, fairwayPolicy.prefixes)
            session.argonLifecycle.startCallbacks(admitted, session.scope) {
              try {
                CoreFairwaySocketBroker(opener = CoreFairwaySocketFactory()).run(host)
              } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Throwable) {
                callbackFailed.set(true)
                recordArgonFailure(session, failure)
              }
              finally { host.stopRequests() }
            }
          } else if (nativeRoutingMode == 1) {
            stage(session, "FAIRWAY_DNS_OPEN")
            val resolver = CoreProtectedUdp.open(ipv4("77.88.8.8") as Inet4Address, 53, ::protect)
            originals += resolver
            stage(session, "FAIRWAY_DNS_ATTACH")
            session.argonLifecycle.attachRuDnsBorrowed(admitted, resolver.fd) { closeTracked(originals, resolver) }
            currentCoroutineContext().ensureActive()
          }
          val builder = tunBuilder(session, authenticated.ipv4, authenticated.mtu, profile.dnsIpv4, routingSnapshot, routePlan)
          if (capture != null) checkNotNull(networks).applyUnderlying(capture, builder)
          stage(session, "CORE_TUN_ESTABLISH")
          currentCoroutineContext().ensureActive()
          // Retain the previous Java TUN until the replacement has been established.
          // Across transient failures its routes remain in place with no native reader.
          val replacement = checkNotNull(builder.establish())
          tunnels += replacement
          val previous = activeTun
          activeTun = replacement
          if (previous != null) closeTracked(tunnels, previous)
          currentCoroutineContext().ensureActive()
          stage(session, "CORE_TUN_ATTACH")
          val borrowed = ParcelFileDescriptor.dup(replacement.fileDescriptor)
          originals += borrowed
          session.argonLifecycle.attachBorrowed(admitted, borrowed.fd) { closeTracked(originals, borrowed) }
          stage(session, "CORE_AWAIT_RUNNING")
          session.argonLifecycle.awaitRunning(admitted)
          currentCoroutineContext().ensureActive()
          reachedRunning = true
          stage(session, "CORE_RUNNING")
          publish(session, ConnectionState.Connected)
          val terminal = session.argonLifecycle.awaitTerminal(admitted)
          terminalStatus = terminal
          currentCoroutineContext().ensureActive()
          // A terminal result in a live owner means the connection ended. User
          // Stop cancels this owner and is handled by the cancellation path.
          throw CoreArgonFailure("status", terminal.toLong())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
          // Saved application-policy/configuration errors cannot be repaired by
          // reconnecting. Match the legacy builder's immediate failure behavior.
          if (failure is ApplicationRoutingUnavailableException || failure is IllegalArgumentException) throw failure
          attemptFailure = failure
        }
        finally {
          // Cleanup must succeed before another native owner can be admitted.
          val released = runCatching { session.argonLifecycle.stopAndRelease(ticket) }.getOrDefault(false)
          val closed = closeTracked(originals)
          // A failed replacement close retains more than one Java TUN. End
          // this worker and hand every descriptor to final/late cleanup.
          if (!released || !closed || tunnels.size > 1) {
            attemptFailure?.let { recordArgonFailure(session, it) }
          }
          check(released && closed && tunnels.size <= 1) { "Veilark Core cleanup did not complete" }
        }
        currentCoroutineContext().ensureActive()
        val plannedNetworkRotation = CoreArgonReconnectPolicy.isPlannedNetworkRotation(
          reachedRunning, networkStopSucceeded.get(), callbackFailed.get(), terminalStatus,
          ticket?.releasedTerminalOutcome,
        )
        if (!plannedNetworkRotation) attemptFailure?.let { recordArgonFailure(session, it) }
        val retry = CoreArgonReconnectPolicy.next(consecutiveFailures, plannedNetworkRotation)
        consecutiveFailures = retry.failureCount
        coreInfo(if (plannedNetworkRotation) "network_rotation=confirmed" else "reconnect_backoff_ms=${retry.delayMillis}")
        publish(session, ConnectionState.Connecting)
        delay(retry.delayMillis)
      }
    } catch (_: CancellationException) {
      // Stop/revoke completes only after native and Java descriptor cleanup.
    } catch (failure: Throwable) {
      if (session.lastFailureCode == null) recordArgonFailure(session, failure)
      val message = if (failure is ApplicationRoutingUnavailableException)
        getString(R.string.core_beta_apps_unavailable) else getString(R.string.core_beta_connection_failed)
      publish(session, ConnectionState.Failed, message + " [${session.lastFailureCode ?: "CORE-UNKNOWN"}]")
    } finally {
      runCatching { loadedProfile?.close() }
      suspend fun cleanup(): Boolean {
        // Invalidating the captured Network also wakes its watcher while native
        // ownership still exists. Failed callback unregistration retains custody.
        val networkClosed = runCatching { networkOwner?.close(); true }.getOrDefault(false)
        val native = runCatching { session.argonLifecycle.stopAndRelease() }.getOrDefault(false) && networkClosed
        val originalsClosed = closeTracked(originals)
        val tunnelsClosed = closeTracked(tunnels)
        val descriptorsClosed = originalsClosed && tunnelsClosed
        sessions.resources(session, native, descriptorsClosed)
        return native && descriptorsClosed
      }
      val released = cleanup()
      if (!released) publish(session, ConnectionState.Failed, getString(R.string.core_beta_cleanup_failed))
      if (released) finishServiceEffects(session)
      else CoreBetaLateCleanup(
        cleanup = { cleanup() },
        onReleased = { finishServiceEffects(session); completeSession(session) },
        event = { coreInfo(it) },
      ).launchOnce()
    }
  }

  /** Failed closes retain Java custody for the fenced late-cleanup worker. */
  private fun closeTracked(descriptors: MutableList<ParcelFileDescriptor>, descriptor: ParcelFileDescriptor) {
    descriptor.close()
    descriptors.remove(descriptor)
  }

  private fun closeTracked(descriptors: MutableList<ParcelFileDescriptor>): Boolean {
    descriptors.toList().forEach { descriptor -> runCatching { closeTracked(descriptors, descriptor) } }
    return descriptors.isEmpty()
  }

  private fun prepareRouting(
    snapshot: Map<String, *>, dns: List<String>, commonFairway: Boolean = false,
  ): CoreRoutingPlan =
    CoreRoutingPlan.prepareIntegrated(
      preferenceString(snapshot, "core_routing_mode", ProfileSelection.ROUTING_ALL),
      preferenceString(snapshot, "core_direct_routes", ""),
      preferenceString(snapshot, "core_vpn_routes", ""), dns, Build.VERSION.SDK_INT,
      commonFairway,
    ) { path -> assets.open(path).use { it.readBytes() } }

  /** Apply the same preflight snapshot; do not reload assets during reconnect. */
  private fun tunBuilder(
    session: Session, clientIpv4: String, mtu: Int, dnsIpv4: List<String>, routingSnapshot: Map<String, *>,
    routePlan: CoreRoutingPlan,
  ): Builder {
    stage(session, "ROUTING_RULES")
    val routingMode = preferenceString(routingSnapshot, "core_routing_mode", ProfileSelection.ROUTING_ALL)
    val effectiveDns = CoreL3DnsPolicy.effective(dnsIpv4)
    // Android blocks an address family absent from addresses/routes/DNS. Never
    // call allowFamily(AF_INET6), add IPv6 DNS/routes, or allowBypass here.
    val builder = Builder().setSession(getString(R.string.core_beta_title)).setMtu(mtu).setBlocking(false)
      .addAddress(clientIpv4, 32)
    routePlan.included.forEach { builder.addRoute(it.address, it.prefixLength) }
    if (Build.VERSION.SDK_INT >= 33) {
      routePlan.excluded.forEach {
        builder.excludeRoute(android.net.IpPrefix(ipv4(it.address), it.prefixLength))
      }
    } else {
      check(routePlan.excluded.isEmpty()) { "Fairway route exclusions require Android 13" }
    }
    coreInfo("routing_mode=$routingMode route_count=${routePlan.included.size} " +
      "excluded_count=${routePlan.excluded.size} partial_ru=${routePlan.partialRu} " +
      "direct_ru_ipv4=${routePlan.directRuIpv4}")
    effectiveDns.resolvers.forEach { builder.addDnsServer(it) }
    if (effectiveDns.removed > 0) {
      coreInfo("dns_migration_removed_count=${effectiveDns.removed} " +
        "effective_dns_count=${effectiveDns.resolvers.size}")
    }
    stage(session, "APPLICATION_RULES")
    val savedPackages = routingSnapshot["selected_applications"]
    require(savedPackages == null || (savedPackages is Set<*> && savedPackages.all { it is String }))
    val applicationPolicy = ApplicationRoutingPolicy.fromPreferences(
      preferenceString(routingSnapshot, "application_mode", ProfileSelection.APPS_ALL),
      savedPackages?.filterIsInstance<String>()?.toSet().orEmpty(),
    )
    val applicationCounts = applicationPolicy.forAndroidVpn(packageName).applyInstalled(
      vpnPackage = packageName,
      addIncluded = { app ->
        try { builder.addAllowedApplication(app); true }
        catch (_: NameNotFoundException) { false }
      },
      addExcluded = { app ->
        try { builder.addDisallowedApplication(app); true }
        catch (_: NameNotFoundException) { false }
      },
    )
    coreInfo("application_mode=${applicationPolicy.mode.preferenceValue} " +
      "included_count=${applicationCounts.acceptedIncluded} excluded_count=${applicationCounts.acceptedExcluded} " +
      "missing_count=${applicationCounts.missing}")
    if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
    return builder
  }


  private suspend fun recordProgress(session: Session) {
    val counts = session.lifecycle.progress() ?: return
    // Rejection fields are independent snapshots of the last observed rejection,
    // not an atomic packet record or proof of the terminal failure's direction.
    val rejection = if (counts.size >= 10) " local_foreign_ipv4_dropped=${counts[5]} " +
      "last_reject_direction=${counts[6]} last_reject_family=${counts[7]} " +
      "last_reject_protocol=${counts[8]} last_reject_source_class=${counts[9]}" else ""
    coreInfo("tun_read_packets=${counts[0]} tun_read_bytes=${counts[1]} " +
      "tun_write_packets=${counts[2]} tun_write_bytes=${counts[3]} local_ipv6_dropped=${counts[4]}$rejection")
  }

  private suspend fun startSession(session: Session, tun: ParcelFileDescriptor, profile: CoreBetaProfile) {
    var socket: ParcelFileDescriptor? = null
    var borrowedTun: ParcelFileDescriptor? = null
    val key = profile.keyCopy()
    val context = profile.contextCopy()
    val carrier = context.copyOfRange(0, 32)
    val initiator = context.copyOfRange(32, 64)
    val responder = context.copyOfRange(64, 96)
    try {
      stage(session, "SOCKET_CREATE")
      // Atomic NONBLOCK creation works on minSdk29; public Os.fcntlInt is API30+.
      val raw = Os.socket(OsConstants.AF_INET,
        OsConstants.SOCK_STREAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
        OsConstants.IPPROTO_TCP)
      // Record ownership before closing raw: if close throws, outer finally
      // must still close the successfully duplicated descriptor.
      try { socket = ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
      val connectedSocket = checkNotNull(socket)
      stage(session, "SOCKET_PROTECT")
      check(protect(connectedSocket.fd))
      val address = ipv4(profile.endpointIpv4)
      stage(session, "TCP_CONNECT")
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
      stage(session, "NATIVE_START")
      borrowedTun = ParcelFileDescriptor.dup(tun.fileDescriptor)
      val source = profile.clientIpv4.split('.').fold(0) { value, octet -> (value shl 8) or octet.toInt() }
      session.lifecycle.startBorrowed(
        start = { VeilarkCoreRuntimeNative.nativeStartOriginalIpv4BetaV1(
          borrowedTun!!.fd, socket!!.fd, key, carrier, initiator, responder, source, profile.lifetimeSeconds) },
        closeOriginals = { runCatching { borrowedTun?.close() }; borrowedTun = null
          runCatching { socket?.close() }; socket = null },
      )
      stage(session, "AUTHENTICATING")
    } finally {
      key.fill(0); context.fill(0); carrier.fill(0); initiator.fill(0); responder.fill(0)
      runCatching { borrowedTun?.close() }; runCatching { socket?.close() }
    }
  }

  override fun onRevoke() {
    coreInfo("service_event=revoke")
    sessions.current()?.scope?.cancel()
    super.onRevoke()
  }
  override fun onDestroy() {
    destroyed = true
    coreInfo("service_event=destroy")
    sessions.current()?.let { it.networkDiagnostics?.close(); it.scope.cancel() }
    super.onDestroy()
  }

  private fun finishServiceEffects(session: Session) {
    sessions.finishServiceEffects(session) {
      session.networkDiagnostics?.close()
      coreInfo("service_event=terminal_cleanup")
      // No global service mutation is allowed after admission becomes possible.
      try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
    }
  }

  private fun completeSession(session: Session) {
    if (sessions.takeReleased(session)) CoreBetaManager.released(session.attempt)
  }

  private fun publish(session: Session, state: ConnectionState, failure: String? = null) {
    CoreBetaManager.publish(session.attempt, state, failure)
  }

  private fun ipv4(value: String): InetAddress = InetAddress.getByAddress(value.split('.').map { it.toInt().toByte() }.toByteArray())

  private fun preferenceString(snapshot: Map<String, *>, key: String, default: String): String {
    val value = snapshot[key] ?: return default
    return value as? String ?: throw IllegalArgumentException("Invalid Core routing setting")
  }

  companion object {
    internal const val ACTION_STOP = "uk.senyasenyavski.veilark.CORE_BETA_STOP"
    private const val CHANNEL = "veilark-core-beta"
    private const val NOTIFICATION = 3103
  }
}

internal fun coreStopCommandMatches(attempt: LifecycleAttempt?, id: Long, epoch: Long): Boolean =
  attempt != null && attempt.attemptId == id && attempt.engineEpoch == epoch

internal fun handleCoreStopCommand(
  attempt: LifecycleAttempt?, id: Long, epoch: Long, cancelWorker: () -> Unit,
): Boolean {
  if (!coreStopCommandMatches(attempt, id, epoch)) return false
  cancelWorker()
  return true
}

internal object CoreBetaReconnectPolicy {
  fun nextFailureCount(previous: Int, plannedRotation: Boolean): Int {
    require(previous in 0..5)
    check(plannedRotation || previous < 5) { "Core beta cannot connect" }
    return if (plannedRotation) 0 else previous + 1
  }

  /** Initiate before the native lifetime expires; never infer success from failure12. */
  fun shouldRotate(status: Int, readyAtMillis: Long?, nowMillis: Long, lifetimeSeconds: Int): Boolean {
    require(lifetimeSeconds in 10..3595)
    return status == 1 && readyAtMillis != null && nowMillis >= readyAtMillis &&
      nowMillis - readyAtMillis >= (lifetimeSeconds.toLong() - 1) * 1000
  }

  fun delayMillis(consecutiveFailures: Int): Long {
    require(consecutiveFailures in 1..5)
    return (1000L shl (consecutiveFailures - 1)).coerceAtMost(16_000)
  }
}
