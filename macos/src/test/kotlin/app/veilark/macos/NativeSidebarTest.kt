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
    assertEquals(0L, NativeSidebar.install("Veilark", emptyArray()))
    NativeSidebar.update(0L, 0, "VPN is off")
    NativeSidebar.remove(0L)
  }
}
