package app.veilark.macos

import java.awt.Dimension
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real AppKit/JNI lifecycle test on a Mac runner; never starts a VPN or reads profiles. */
class NativeChromeMacTest {
  private val labels = arrayOf("Overview", "Profiles", "Routing", "Diagnostics", "Settings",
    "macOS client", "Command 1–5", "VPN is off", "Connect")

  private fun frame(title: String, width: Int, height: Int, unified: Boolean): JFrame {
    lateinit var window: JFrame
    SwingUtilities.invokeAndWait {
      window = JFrame(title).apply {
        if (unified) {
          rootPane.putClientProperty("apple.awt.fullWindowContent", true)
          rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        }
        size = Dimension(width, height)
        setLocation(120, 120)
        isVisible = true
      }
    }
    return window
  }

  private fun bounds(window: JFrame): IntArray {
    lateinit var result: IntArray
    SwingUtilities.invokeAndWait { result = window.bounds.let { intArrayOf(it.x, it.y, it.width, it.height) } }
    return result
  }

  private fun install(window: JFrame, title: String, unified: Boolean): Long {
    for (attempt in 0 until 20) {
      val handle = NativeSidebar.install(0L, title, bounds(window), unified, labels)
      if (handle != 0L) return handle
      Thread.sleep(100)
    }
    return 0L
  }

  @Test fun nativeChromeInstallsUpdatesAndRemovesFromTheIntendedWindow() {
    assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
    val title = "Veilark UI test ${UUID.randomUUID()}"
    // A same-titled small window, like the startup window, must never receive the chrome.
    val decoy = frame(title, 420, 220, unified = false)
    val window = frame(title, 960, 640, unified = true)
    var handle = 0L
    try {
      Thread.sleep(300)
      handle = install(window, title, unified = true)
      assertTrue("Native chrome did not attach to the test window", handle != 0L)
      var state = NativeSidebar.state(handle)
      for (attempt in 0 until 20) {
        if ((state?.topInset ?: 0) > 0) break
        Thread.sleep(100)
        state = NativeSidebar.state(handle)
      }
      assertNotNull(state)
      state!!
      println("Native chrome: $state; macOS ${System.getProperty("os.version")}; " +
        "display mask ${MacNativeChrome.observeDisplayPreferences()}")
      assertEquals("Attached to the same-titled startup-sized window", 960, state.width)
      assertEquals(640, state.height)
      assertEquals("Frame match expected when no NSWindow handle is supplied", 2, state.matchedBy)
      assertTrue("Unified toolbar missing", state.toolbar >= 1)
      assertTrue("Full-size content view should put the toolbar over the content", state.topInset > 0)
      assertEquals(state.material, MacNativeChrome.materialMode(handle))
      if (System.getenv("VEILARK_REQUIRE_SYSTEM_GLASS") == "1") {
        assertEquals("Expected real NSGlassEffectView, not an opaque/blur fallback", 2, state.material)
        assertEquals("Expected glass-bezel toolbar buttons", 2, state.toolbar)
      }
      if (System.getenv("VEILARK_EXPECT_FALLBACK_CHROME") == "1") {
        assertEquals("Pre-26 macOS must use the NSVisualEffectView fallback", 1, state.material)
        assertEquals("Pre-26 macOS must use standard toolbar bezels", 1, state.toolbar)
      }
      val mask = MacNativeChrome.observeDisplayPreferences()
      assertTrue("Accessibility display options unavailable: $mask", mask in 0..7)
      assertNotNull(NativeSidebar.displayPreferences())

      NativeSidebar.update(handle, 3, "VPN is off", "Connect", true)
      Thread.sleep(600)
      if (System.getenv("VEILARK_CAPTURE_NATIVE_UI") == "1") {
        val image = Robot().createScreenCapture(window.bounds)
        val output = File("build/reports/native-chrome.png")
        output.parentFile.mkdirs()
        check(ImageIO.write(image, "png", output))
      }

      NativeSidebar.remove(handle)
      assertEquals(-1, MacNativeChrome.materialMode(handle))
      assertNull(NativeSidebar.state(handle))
      // Queued updates and confirmations for a removed handle are harmless and report
      // "not shown" so the Compose dialog takes over.
      NativeSidebar.update(handle, 4, "VPN is off", "Connect", false)
      val answered = CountDownLatch(1)
      val answer = AtomicInteger(99)
      val shown = NativeSidebar.confirm(handle, "Delete", "Remove?", "Delete", "Cancel", true,
        onUnavailable = { answer.set(-1); answered.countDown() }) { confirmed ->
        answer.set(if (confirmed) 1 else 0); answered.countDown()
      }
      assertTrue(shown)
      assertTrue("Detached confirmation was not answered", answered.await(3, TimeUnit.SECONDS))
      assertEquals(-1, answer.get())
      assertEquals(0L, NativeSidebar.install(0L, "missing-${UUID.randomUUID()}", intArrayOf(0, 0, 10, 10), true, labels))
    } finally {
      NativeSidebar.remove(handle)
      SwingUtilities.invokeAndWait {
        window.dispose()
        decoy.dispose()
      }
    }
  }
}
