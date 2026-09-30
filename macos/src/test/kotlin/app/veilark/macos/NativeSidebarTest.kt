package app.veilark.macos

import org.junit.Assert.*
import org.junit.Test
import com.example.veilark.engine.TunnelStatus

class NativeSidebarTest {
  @Test fun navigationOnlyAcceptsKnownSections() {
    val received = mutableListOf<Int>()
    MacNativeChrome.setNavigationHandler { received += it }
    try {
      listOf(-1, 0, 4, 5, Int.MAX_VALUE).forEach(MacNativeChrome::onNavigate)
      assertEquals(listOf(0, 4), received)
    } finally {
      MacNativeChrome.setNavigationHandler(null)
    }
    MacNativeChrome.onNavigate(1)
    assertEquals(listOf(0, 4), received)
  }

  @Test fun statusLabelsArePublicNonEmptyText() {
    TunnelStatus.entries.forEach { assertTrue(connectionStatusText(it).isNotBlank()) }
    assertNotEquals(connectionStatusText(TunnelStatus.FAILED), connectionStatusText(TunnelStatus.CONNECTED))
  }

  @Test fun invalidInstallationAndZeroHandleAreSafe() {
    assertEquals(0L, NativeSidebar.install(0L, "Veilark", intArrayOf(0, 0, 0, 0), true, emptyArray()))
    assertEquals(0L, NativeSidebar.install(0L, "Veilark", intArrayOf(), true,
      Array(NativeSidebar.LABEL_COUNT) { "x" }))
    NativeSidebar.update(0L, 0, "VPN is off", "Connect", true)
    assertNull(NativeSidebar.state(0L))
    NativeSidebar.remove(0L)
    var fallback = false
    assertFalse(NativeSidebar.confirm(0L, "t", "m", "ok", "cancel", true, { fallback = true }) { fail() })
    assertFalse("Fallback is signalled by the return value, not the callback", fallback)
  }

  @Test fun toolbarOnlyAcceptsTheConnectionAction() {
    val received = mutableListOf<Int>()
    MacNativeChrome.setToolbarHandler { received += it }
    try {
      listOf(-1, 0, 1, 7).forEach(MacNativeChrome::onToolbarAction)
      assertEquals(listOf(MacNativeChrome.TOOLBAR_TOGGLE_CONNECTION), received)
    } finally {
      MacNativeChrome.setToolbarHandler(null)
    }
    MacNativeChrome.onToolbarAction(0)
    assertEquals(1, received.size)
  }

  @Test fun confirmationAnswersAreOneShotAndValidated() {
    val answers = mutableListOf<Int>()
    val id = MacNativeChrome.registerConfirmation { answers += it }
    MacNativeChrome.onConfirmResult(id, 5)
    MacNativeChrome.onConfirmResult(id + 1_000, 1)
    assertTrue(answers.isEmpty())
    MacNativeChrome.onConfirmResult(id, 1)
    MacNativeChrome.onConfirmResult(id, 0)
    assertEquals(listOf(1), answers)
    val cancelled = MacNativeChrome.registerConfirmation { answers += it }
    MacNativeChrome.cancelConfirmation(cancelled)
    MacNativeChrome.onConfirmResult(cancelled, 0)
    assertEquals(listOf(1), answers)
    assertNotEquals(id, cancelled)
  }

  @Test fun displayPreferenceMaskIsDecodedAndRangeChecked() {
    assertNull(visualPreferencesFromMask(-1))
    assertNull(visualPreferencesFromMask(8))
    assertEquals(VisualPreferences(false, false, false), visualPreferencesFromMask(0))
    assertEquals(VisualPreferences(true, false, false), visualPreferencesFromMask(1))
    assertEquals(VisualPreferences(false, true, true), visualPreferencesFromMask(6))
    val seen = mutableListOf<Int>()
    MacNativeChrome.setDisplayHandler { seen += it }
    try {
      MacNativeChrome.onDisplayPreferencesChanged(9)
      MacNativeChrome.onDisplayPreferencesChanged(3)
    } finally {
      MacNativeChrome.setDisplayHandler(null)
    }
    assertEquals(listOf(3), seen)
    assertEquals(3, MacNativeChrome.lastDisplayMask())
  }

  @Test fun nativeStateRequiresAllFields() {
    assertNull(NativeChromeState.from(null))
    assertNull(NativeChromeState.from(intArrayOf(2, 2, 1)))
    assertNull(NativeChromeState.from(intArrayOf(-1, 0, 0, 0, 0, 0)))
    assertEquals(NativeChromeState(2, 2, 1, 52, 1040, 720), NativeChromeState.from(intArrayOf(2, 2, 1, 52, 1040, 720)))
  }

  @Test fun toolbarActionMirrorsTrayConnectionSemantics() {
    assertEquals(connectionActionText(TunnelStatus.CONNECTED), connectionActionText(TunnelStatus.DEGRADED))
    assertNotEquals(connectionActionText(TunnelStatus.CONNECTED), connectionActionText(TunnelStatus.DISCONNECTED))
    assertTrue(connectionActionEnabled(busy = true, status = TunnelStatus.CONNECTING))
    assertFalse(connectionActionEnabled(busy = true, status = TunnelStatus.CONNECTED))
    assertTrue(connectionActionEnabled(busy = false, status = TunnelStatus.DISCONNECTED))
  }

  @Test fun packedSdkVersionsAreFormattedLikeDyld() {
    assertNull(formatPackedVersion(-1))
    assertNull(formatPackedVersion(0))
    assertEquals("26.5", formatPackedVersion((26 shl 16) or (5 shl 8)))
    assertEquals("14.2.1", formatPackedVersion((14 shl 16) or (2 shl 8) or 1))
  }

  @Test fun chromeReportIsKeyValueAndSafeWhenDetached() {
    assertEquals("programSdk=unknown\nmaterial=-1\ntoolbar=-1\nmatchedBy=0\ntopInset=0\n",
      nativeChromeReport(null, null))
    assertTrue(nativeChromeReport("26.5", NativeChromeState(2, 2, 1, 52, 1040, 720))
      .contains("programSdk=26.5\nmaterial=2\ntoolbar=2\nmatchedBy=1\n"))
  }
}
