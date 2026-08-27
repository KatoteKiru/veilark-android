package com.example.veilark.session

import com.example.veilark.engine.TunnelController
import com.example.veilark.engine.TunnelEngineKind
import com.example.veilark.engine.TunnelStatus
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.profile.SingBoxCatalog
import com.example.veilark.profile.SubscriptionParser
import com.example.veilark.storage.EncryptedStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    delay(50)
    session.disconnect()
    connect.join()

    assertEquals(TunnelStatus.DISCONNECTED, session.status)
    assertTrue(fake.stopCalls >= 2)
    assertTrue(session.logs.any { it.code == "CONNECT_CANCELLED" })
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
    return VeilarkSession(store, fake, ConnectionHealthChecker { health }).also {
      it.switchEngine(TunnelEngineKind.SING_BOX)
    }
  }

  private class FakeController : TunnelController {
    var running = false
    var stopCalls = 0

    override fun installed(): Boolean = true
    override fun enginePresent(kind: TunnelEngineKind): Boolean = true
    override fun install(): Result<Unit> = Result.success(Unit)
    override fun start(kind: TunnelEngineKind, config: String): Result<Unit> {
      running = true
      return Result.success(Unit)
    }
    override fun stop(): Result<Unit> {
      stopCalls += 1
      running = false
      return Result.success(Unit)
    }
    override fun status(): String = if (running) "connected" else "disconnected"
    override fun lastLog(): String = ""
    override fun tunFailed(): Boolean = false
    override fun outboundUnresolved(): Boolean = false
  }

  private companion object {
    const val LINKS = """
      trojan://secret@203.0.113.2:443?security=tls&sni=example.com#NL
      trojan://other@203.0.113.3:443?security=tls&sni=example.com#DE
    """
  }
}
