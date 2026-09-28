package com.example.veilark.session

import com.example.veilark.engine.TunnelController
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.GeoRoutingBundle
import com.example.veilark.profile.GeoRoutingStore
import com.example.veilark.profile.SingBoxCatalog
import com.example.veilark.profile.SubscriptionParser
import com.example.veilark.protocol.TrustTunnelCatalog
import com.example.veilark.protocol.TrustTunnelCatalogEntry
import com.example.veilark.storage.EncryptedStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale

class VeilarkSessionTest {
  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun persistsEngineSelectionProfileAndRouting() {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("secure")) { key }
    val session = VeilarkSession(store, FakeController())
    val parsed = SubscriptionParser().compile(LINKS.toByteArray())
    val entry = SingBoxCatalog.create(
      config = parsed.json,
      nodes = parsed.nodes,
      selectedNodeTag = ProfileSelection.AUTOMATIC_TAG,
      sourceUrl = "https://sub.example.test/path",
      suggestedName = "Example",
    )
    store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(listOf(entry)))

    val restored = VeilarkSession(store, FakeController())
    restored.switchEngine(TunnelEngineKind.SING_BOX)
    restored.selectSingBox(entry.id, parsed.nodes.last().tag)
    restored.updateRouting(ProfileSelection.ROUTING_MANUAL, "example.ru", "youtube.com")

    val reopened = VeilarkSession(store, FakeController())
    assertEquals(TunnelEngineKind.SING_BOX, reopened.engine)
    assertEquals(entry.id, reopened.selectedSingBoxId)
    assertEquals(parsed.nodes.last().tag, reopened.singBoxEntries.single().selectedNodeTag)
    assertEquals(ProfileSelection.ROUTING_MANUAL, reopened.routingMode)
    assertEquals("example.ru", reopened.manualDirectEntries)
    assertEquals("youtube.com", reopened.manualVpnEntries)
  }

  @Test
  fun keepsIndependentRoutingModesWhenSwitchingEngines() {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("routing-per-engine")) { key }
    val parsed = SubscriptionParser().compile(LINKS.toByteArray())
    val entry = SingBoxCatalog.create(
      parsed.json,
      parsed.nodes,
      ProfileSelection.AUTOMATIC_TAG,
      null,
      "Local",
    )
    store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(listOf(entry)))
    val session = VeilarkSession(store, FakeController())

    session.switchEngine(TunnelEngineKind.SING_BOX)
    session.updateRouting(ProfileSelection.ROUTING_MANUAL, "example.ru", "youtube.com")
    session.switchEngine(TunnelEngineKind.TRUST_TUNNEL)
    session.updateRouting(ProfileSelection.ROUTING_RU_DIRECT, "", "")
    session.switchEngine(TunnelEngineKind.SING_BOX)

    assertEquals(ProfileSelection.ROUTING_MANUAL, session.routingMode)
    assertEquals("example.ru", session.manualDirectEntries)
    assertEquals("youtube.com", session.manualVpnEntries)
    val reopened = VeilarkSession(store, FakeController())
    assertEquals(TunnelEngineKind.SING_BOX, reopened.engine)
    assertEquals(ProfileSelection.ROUTING_MANUAL, reopened.routingMode)
  }

  @Test
  fun persistsManualTrustRoutingIndependentlyFromSingBox() {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("trust-manual-routing")) { key }
    val trust = TrustTunnelCatalogEntry("trust", "Trust", TRUST_CONFIG)
    store.save(EncryptedStore.TRUST_TUNNEL_CATALOG, TrustTunnelCatalog.encode(listOf(trust)))
    val session = VeilarkSession(store, FakeController())

    session.switchEngine(TunnelEngineKind.TRUST_TUNNEL)
    session.updateRouting(ProfileSelection.ROUTING_MANUAL, "example.ru", "youtube.com")

    val reopened = VeilarkSession(store, FakeController())
    assertEquals(TunnelEngineKind.TRUST_TUNNEL, reopened.engine)
    assertEquals(ProfileSelection.ROUTING_MANUAL, reopened.routingMode)
    assertEquals("example.ru", reopened.manualDirectEntries)
    assertEquals("youtube.com", reopened.manualVpnEntries)
  }

  @Test
  fun connectStaysConnectedWhenAdvisoryHealthCheckFails() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(false, "public probe blocked"))

    session.connect()

    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertEquals("public probe blocked", session.lastHealthDetail)
    assertTrue(session.logs.any { it.code == "CONNECT_HEALTH_DEGRADED" })
    assertTrue(session.logs.any { it.code == "CONNECT_OK" })
  }

  @Test
  fun queuedDisconnectCancelsAnInFlightConnect() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))

    val connect = launch { session.connect() }
    withTimeout(5_000) { fake.started.await() }
    session.disconnect()
    connect.join()

    assertEquals(TunnelStatus.DISCONNECTED, session.status)
    assertTrue(fake.stopCalls >= 2)
    assertTrue(session.logs.any { it.code == "CONNECT_CANCELLED" })
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
  }

  @Test
  fun completedDisconnectDoesNotCancelTheNextConnect() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()
    session.disconnect()
    session.disconnect()
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertEquals(2, fake.startCalls)
    session.disconnectAwaited()
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
  }

  @Test
  fun idleDisconnectDoesNotCancelFirstConnect() = runBlocking {
    val session = sessionWithSingBox(FakeController(), NetworkHealth(true, "ok"))
    session.disconnect()
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
  }

  @Test
  fun slowHelperDoesNotBlockCallerAndCancellationStillStopsCore() = runBlocking {
    val caller = Thread.currentThread()
    val gate = java.util.concurrent.CountDownLatch(1)
    val fake = FakeController(startGate = gate)
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    val starting = launch { session.connect() }
    try {
      withTimeout(5_000) { fake.startEntered.await() }
      // Explicit barrier, not a sleep-based race against a slow CI machine.
      assertFalse(fake.running)
      assertTrue(fake.startThread !== caller)
      session.disconnect()
    } finally { gate.countDown() }
    starting.join()
    assertEquals(TunnelStatus.DISCONNECTED, session.status)
    assertFalse(fake.running)
  }

  @Test
  fun failedCancellationCleanupNeverClaimsDisconnectedOrStartsAnotherCore() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    val starting = launch { session.connect() }
    withTimeout(5_000) { fake.started.await() }
    fake.failStop = true
    session.disconnect()
    starting.join()
    assertEquals(TunnelStatus.DEGRADED, session.status)
    assertTrue(session.status.isStopAction)
    assertTrue(session.status.blocksOfflineChanges)
    assertFalse(session.recoveryPending)
    org.junit.Assert.assertThrows(IllegalStateException::class.java) {
      session.switchEngine(TunnelEngineKind.TRUST_TUNNEL)
    }
    assertTrue(fake.running)
    val starts = fake.startCalls
    session.connect()
    assertEquals(starts, fake.startCalls)
    assertEquals(TunnelStatus.DEGRADED, session.status)
    fake.failStop = false
    session.disconnectAwaited().getOrThrow()
    assertFalse(fake.running)
  }

  @Test
  fun trustConnectStopConnectPreservesManualRouting() = runBlocking {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("trust-lifecycle")) { key }
    store.save(EncryptedStore.TRUST_TUNNEL_CATALOG,
      TrustTunnelCatalog.encode(listOf(TrustTunnelCatalogEntry("trust", "Trust", TRUST_CONFIG))))
    val fake = FakeController()
    val session = VeilarkSession(store, fake, ConnectionHealthChecker { NetworkHealth(true, "ok") },
      DefaultRouteFingerprintProvider { null })
    session.updateRouting(ProfileSelection.ROUTING_MANUAL, "example.ru", "youtube.com")
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
    session.disconnect()
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertEquals(2, fake.startCalls)
    assertTrue(fake.lastConfig.contains("example.ru"))
  }

  @Test
  fun degradedHandoverRetriesWithBackoffAndStopsAtBudget() = runBlocking {
    var now = 0L
    val fake = FakeController()
    val routes = MutableRouteProvider(DefaultRouteFingerprint("en0", "192.0.2.1"))
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"), routes, clock = { now })
    session.connect()
    fake.failStart = true
    routes.current = DefaultRouteFingerprint("en5", "198.51.100.1")
    assertFalse(session.reconcileDefaultRouteHandover())
    assertEquals(TunnelStatus.DEGRADED, session.status)
    val attempts = fake.startCalls
    assertFalse(session.reconcileDefaultRouteHandover())
    assertEquals(attempts, fake.startCalls)
    repeat(4) {
      now += 60_000
      session.reconcileDefaultRouteHandover()
    }
    assertFalse(session.recoveryPending)
    val exhausted = fake.startCalls
    now += 60_000
    session.reconcileDefaultRouteHandover()
    assertEquals(exhausted, fake.startCalls)
    session.disconnect()
    fake.failStart = false
    session.connect()
    assertEquals(TunnelStatus.CONNECTED, session.status)
  }

  @Test
  fun transientHandoverFailureRecoversAndManualStopCancelsRetries() = runBlocking {
    var now = 0L
    val fake = FakeController()
    val routes = MutableRouteProvider(DefaultRouteFingerprint("en0", "192.0.2.1"))
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"), routes, clock = { now })
    session.connect()
    fake.failStart = true
    routes.current = DefaultRouteFingerprint("en5", "198.51.100.1")
    session.reconcileDefaultRouteHandover()
    fake.failStart = false
    now += 60_000
    assertTrue(session.reconcileDefaultRouteHandover())
    session.disconnect()
    assertFalse(session.recoveryPending)
    assertFalse(session.reconcileDefaultRouteHandover())
  }

  @Test
  fun queuedDisconnectDuringFinalHealthProbeCannotBeLost() = runBlocking {
    val fake = FakeController()
    val healthStarted = CompletableDeferred<Unit>()
    val finishHealth = CompletableDeferred<Unit>()
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("health-cancel-secure")) { key }
    val parsed = SubscriptionParser().compile(LINKS.toByteArray())
    val entry = SingBoxCatalog.create(
      parsed.json,
      parsed.nodes,
      ProfileSelection.AUTOMATIC_TAG,
      null,
      "Local",
    )
    store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(listOf(entry)))
    val session = VeilarkSession(store, fake, ConnectionHealthChecker {
      healthStarted.complete(Unit)
      finishHealth.await()
      NetworkHealth(true, "ok")
    }).also { it.switchEngine(TunnelEngineKind.SING_BOX) }

    val connect = launch { session.connect() }
    withTimeout(5_000) { healthStarted.await() }
    session.disconnect()
    finishHealth.complete(Unit)
    connect.join()

    assertEquals(TunnelStatus.DISCONNECTED, session.status)
    assertFalse(fake.running)
    assertTrue(session.logs.any { it.code == "CONNECT_CANCELLED" })
  }

  @Test
  fun slowGeoRefreshDoesNotBlockTunnelConnection() = runBlocking {
    val refreshStarted = CompletableDeferred<Unit>()
    val finishRefresh = CompletableDeferred<Unit>()
    val placeholder = folder.newFile("geo-placeholder")
    val geoStore = object : GeoRoutingStore {
      override fun currentOrBundled() = GeoRoutingBundle(
        placeholder,
        placeholder,
        placeholder,
        placeholder,
      )

      override suspend fun refreshFromRemote(): GeoRoutingBundle {
        refreshStarted.complete(Unit)
        finishRefresh.await()
        return currentOrBundled()
      }
    }
    val session = sessionWithSingBox(
      FakeController(),
      NetworkHealth(true, "ok"),
      geoStore = geoStore,
    )

    val refresh = async { session.refreshGeoData() }
    withTimeout(1_000) { refreshStarted.await() }
    withTimeout(3_000) { session.connect() }

    assertEquals(TunnelStatus.CONNECTED, session.status)
    finishRefresh.complete(Unit)
    refresh.await()
    assertTrue(session.logs.any { it.code == "GEO_UPDATE_OK" })
  }

  @Test
  fun healthCheckMarksUnexpectedEngineExitWithoutClaimingNetworkFailure() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()
    fake.running = false

    val result = session.checkConnectionHealth()

    assertFalse(result.reachable)
    assertEquals(TunnelStatus.FAILED, session.status)
    assertTrue(session.logs.any { it.code == "ENGINE_EXITED" })
  }

  @Test
  fun transientHelperStatusFailureDoesNotStopLaterRuntimeChecks() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()

    fake.throwStatus = true
    session.reconcileStatus()
    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertTrue(session.logs.any { it.code == "HELPER_STATUS_CHECK_FAILED" })

    fake.throwStatus = false
    fake.running = false
    session.reconcileStatus()
    assertEquals(TunnelStatus.FAILED, session.status)
    assertTrue(session.logs.any { it.code == "ENGINE_EXITED" })
  }

  @Test
  fun uncertainHelperStatusRecoversOnlyAfterConnectedConfirmation() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()

    fake.statusUnknown = true
    session.reconcileStatus()
    assertEquals(TunnelStatus.DEGRADED, session.status)

    fake.statusUnknown = false
    session.reconcileStatus()
    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertTrue(session.logs.any { it.code == "HELPER_STATUS_RECOVERED" })
  }

  @Test
  fun unknownRuntimeStatusRequiresStopButConfirmedExitDoesNot() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()
    fake.statusUnknown = true
    session.reconcileStatus()
    assertEquals(TunnelStatus.DEGRADED, session.status)
    assertTrue(session.status.blocksOfflineChanges)
    assertTrue(session.status.isStopAction)
    assertFalse(session.recoveryPending)
    session.disconnectAwaited().getOrThrow()
    fake.statusUnknown = false
    session.connect()
    fake.running = false
    session.reconcileStatus()
    assertEquals(TunnelStatus.FAILED, session.status)
    assertFalse(session.status.blocksOfflineChanges)
  }

  @Test
  fun explicitHealthActionDoesNotMistakeHelperFailureForEngineExit() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()
    fake.statusUnknown = true
    assertFalse(session.checkConnectionHealth().reachable)
    assertEquals(TunnelStatus.DEGRADED, session.status)
    assertTrue(fake.running)
    assertTrue(session.status.isStopAction)
    assertFalse(session.recoveryPending)
    assertTrue(session.logs.any { it.code == "HELPER_STATUS_UNKNOWN" })
  }

  @Test
  fun defaultRouteChangeFromEn0ToEn5PerformsOneControlledReconnect() = runBlocking {
    val fake = FakeController()
    val routes = MutableRouteProvider(DefaultRouteFingerprint("en0", "192.0.2.1"))
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"), routes)
    session.connect()
    routes.current = DefaultRouteFingerprint("en5", "198.51.100.1")

    assertTrue(session.reconcileDefaultRouteHandover())
    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertEquals(2, fake.startCalls)
    assertEquals(2, fake.stopCalls)
    assertEquals("en5", JSONObject(fake.lastConfig).getJSONObject("route").getString("default_interface"))
    assertTrue(session.logs.any { it.code == "NETWORK_HANDOVER_DETECTED" })
    assertTrue(session.logs.any { it.code == "NETWORK_HANDOVER_RECONNECTING" })
  }

  @Test
  fun unchangedDefaultRouteDoesNotReconnect() = runBlocking {
    val fake = FakeController()
    val routes = MutableRouteProvider(DefaultRouteFingerprint("en0", "192.0.2.1"))
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"), routes)
    session.connect()

    assertFalse(session.reconcileDefaultRouteHandover())
    assertEquals(1, fake.startCalls)
    assertEquals(1, fake.stopCalls)
  }

  @Test
  fun burstRouteEventsAreDeduplicatedWhileReconnectIsSerialized() = runBlocking {
    val fake = FakeController(startDelayMillis = 100)
    val routes = MutableRouteProvider(DefaultRouteFingerprint("en0", "192.0.2.1"))
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"), routes)
    session.connect()
    routes.current = DefaultRouteFingerprint("en5", "198.51.100.1")

    coroutineScope {
      List(8) { async { session.reconcileDefaultRouteHandover() } }.awaitAll()
    }

    assertEquals(TunnelStatus.CONNECTED, session.status)
    assertEquals(2, fake.startCalls)
    assertEquals(2, fake.stopCalls)
  }

  @Test
  fun quitStopFailureIsReturnedAndLeavesTechnicalFailureState() = runBlocking {
    val fake = FakeController()
    val session = sessionWithSingBox(fake, NetworkHealth(true, "ok"))
    session.connect()
    fake.failStop = true

    val result = session.stopForQuit()

    assertTrue(result.isFailure)
    assertEquals(TunnelStatus.DEGRADED, session.status)
    assertTrue(session.status.isStopAction)
    assertTrue(session.status.blocksOfflineChanges)
    assertTrue(fake.running)
    assertTrue(session.statusDetail.isNotBlank())
    assertTrue(session.logs.any { it.code == "DISCONNECT_FAILED" })
  }

  @Test
  fun deletesSelectedSubscriptionAndDoesNotPersistSensitiveLinksInLogs() {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("secure")) { key }
    val parsed = SubscriptionParser().compile(LINKS.toByteArray())
    val entry = SingBoxCatalog.create(
      parsed.json,
      parsed.nodes,
      ProfileSelection.AUTOMATIC_TAG,
      "https://sub.example.test/token/secret",
      "Example",
    )
    store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(listOf(entry)))
    val session = VeilarkSession(store, FakeController())
    session.switchEngine(TunnelEngineKind.SING_BOX)
    session.log("refresh https://sub.example.test/token/secret password=topsecret")
    session.deleteSelectedSubscription()

    assertTrue(session.singBoxEntries.isEmpty())
    assertFalse(session.logs.last().message.contains("topsecret"))
    assertFalse(session.logs.dropLast(1).last().message.contains("sub.example.test"))
    assertTrue(VeilarkSession(store, FakeController()).singBoxEntries.isEmpty())
  }

  @Test
  fun runtimeMessagesFollowEnglishLocaleAndRestoreTheProcessLocale() {
    val previous = Locale.getDefault()
    try {
      Locale.setDefault(Locale.ENGLISH)
      val key = EncryptedStore.ephemeralKey()
      val session = VeilarkSession(
        EncryptedStore(folder.newFolder("english-secure")) { key },
        FakeController(),
      )

      val importFailure = runCatching { runBlocking { session.importText("") } }.exceptionOrNull()
      assertEquals("The import is empty", importFailure?.message)
      assertEquals("The import is empty", session.logs.last().message)

      session.switchEngine(TunnelEngineKind.SING_BOX)
      runBlocking { session.connect() }
      assertEquals("Select a sing-box profile", session.statusDetail)
      assertFalse(session.logs.any { entry -> entry.message.any { it in '\u0400'..'\u04FF' } })
    } finally {
      Locale.setDefault(previous)
    }
    assertEquals(previous, Locale.getDefault())
  }

  private fun sessionWithSingBox(
    fake: FakeController,
    health: NetworkHealth,
    routes: DefaultRouteFingerprintProvider = DefaultRouteFingerprintProvider { null },
    geoStore: GeoRoutingStore? = null,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
  ): VeilarkSession {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("secure")) { key }
    val parsed = SubscriptionParser().compile(LINKS.toByteArray())
    val entry = SingBoxCatalog.create(
      parsed.json,
      parsed.nodes,
      ProfileSelection.AUTOMATIC_TAG,
      null,
      "Local",
    )
    store.save(EncryptedStore.SING_BOX_CATALOG, SingBoxCatalog.encode(listOf(entry)))
    val session = if (geoStore == null) {
      VeilarkSession(store, fake, ConnectionHealthChecker { health }, routes, monotonicMillis = clock)
    } else {
      VeilarkSession(store, fake, ConnectionHealthChecker { health }, routes, geoStore, clock)
    }
    return session.also {
      it.switchEngine(TunnelEngineKind.SING_BOX)
    }
  }

  private class FakeController(private val startDelayMillis: Long = 0,
    private val startGate: java.util.concurrent.CountDownLatch? = null) : TunnelController {
    @Volatile var running = false
    val startEntered = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    @Volatile var startThread: Thread? = null
    var stopCalls = 0
    var failStop = false
    var failStart = false
    @Volatile var statusUnknown = false
    @Volatile var throwStatus = false
    var startCalls = 0
    var lastConfig = ""

    override fun installed(): Boolean = true
    override fun enginePresent(kind: TunnelEngineKind): Boolean = true
    override fun install(): Result<Unit> = Result.success(Unit)
    override fun start(kind: TunnelEngineKind, config: String): Result<Unit> {
      startThread = Thread.currentThread()
      startEntered.complete(Unit)
      if (startGate != null) check(startGate.await(10, java.util.concurrent.TimeUnit.SECONDS))
      if (startDelayMillis > 0) Thread.sleep(startDelayMillis)
      startCalls += 1
      if (failStart) return Result.failure(IllegalStateException("start unavailable"))
      lastConfig = config
      running = true
      started.complete(Unit)
      return Result.success(Unit)
    }
    override fun stop(): Result<Unit> {
      stopCalls += 1
      if (failStop) return Result.failure(IllegalStateException("managed engine did not stop"))
      running = false
      return Result.success(Unit)
    }
    override fun status(): String {
      if (throwStatus) error("temporary helper IPC failure")
      return if (statusUnknown) "unknown" else if (running) "connected" else "disconnected"
    }
    override fun lastLog(): String = ""
    override fun tunFailed(): Boolean = false
    override fun outboundUnresolved(): Boolean = false
  }

  private class MutableRouteProvider(initial: DefaultRouteFingerprint) : DefaultRouteFingerprintProvider {
    @Volatile var current: DefaultRouteFingerprint = initial
    override fun current(): DefaultRouteFingerprint = current
  }

  private companion object {
    const val TRUST_CONFIG = """
      vpn_mode = "general"
      exclusions = []
      [endpoint]
      addresses = ["127.0.0.1:443"]
      has_ipv6 = false
      [listener.tun]
      included_routes = ["0.0.0.0/0"]
      excluded_routes = ["10.0.0.0/8"]
    """
    const val LINKS = """
      trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL
      trojan://other@203.0.113.3:443?security=tls&sni=example.com#DE
    """
  }
}
