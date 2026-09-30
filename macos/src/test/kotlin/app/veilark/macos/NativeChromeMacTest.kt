package app.veilark.macos

import java.awt.Dimension
import java.awt.Frame
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO
import java.util.UUID
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real AppKit/JNI lifecycle test on a Mac runner; never starts a VPN or reads profiles. */
class NativeChromeMacTest {
  @Test fun nativeSidebarInstallsUpdatesAndRemovesFromAnAwtWindow() {
    assumeTrue(System.getProperty("os.name").startsWith("Mac", ignoreCase = true))
    val title = "Veilark UI test ${UUID.randomUUID()}"
    lateinit var window: Frame
    SwingUtilities.invokeAndWait {
      window = Frame(title).apply { size = Dimension(960, 640); isVisible = true }
    }
    var handle = 0L
    try {
      val labels = arrayOf("Overview", "Profiles", "Routing", "Diagnostics", "Settings",
        "macOS client", "Command 1–5", "VPN is off")
      for (attempt in 0 until 20) {
        handle = NativeSidebar.install(title, labels)
        if (handle != 0L) break
        Thread.sleep(100)
      }
      assertTrue("Native sidebar did not attach to the test window", handle != 0L)
      NativeSidebar.update(handle, 3, "VPN is off")
      Thread.sleep(600)
      if (System.getenv("VEILARK_CAPTURE_NATIVE_UI") == "1") {
        val image = Robot().createScreenCapture(window.bounds)
        val output = File("build/reports/native-chrome.png")
        output.parentFile.mkdirs()
        check(ImageIO.write(image, "png", output))
      }
      NativeSidebar.remove(handle)
      // Queued updates to a removed handle are intentionally harmless.
      NativeSidebar.update(handle, 4, "VPN is off")
      assertEquals(0L, NativeSidebar.install("missing-${UUID.randomUUID()}", labels))
    } finally {
      NativeSidebar.remove(handle)
      SwingUtilities.invokeAndWait { window.dispose() }
    }
  }
}
