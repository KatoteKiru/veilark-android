package app.veilark.macos

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BrandIconTest {
  @Test fun markIsBundledAndVectorPathsAreComplete() {
    assertEquals(2, BrandIcon.paths().size)
    assertTrue(BrandIcon.paths().all { it.startsWith("M") && it.endsWith("Z") })
    assertEquals("Veilark", VeilarkMark.name)
  }

  @Test fun iconsRenderAtEveryPackagedResolution() {
    for (size in listOf(16, 32, 64, 128, 256, 512, 1024)) {
      val image = brandBitmap(size)
      assertEquals(size, image.width)
      assertEquals(size, image.height)
      assertEquals(0, image.getRGB(0, 0) ushr 24)
      assertTrue(image.getRGB(size / 2, size / 2) ushr 24 > 0)
    }
  }

  @Test fun themeChangesInkAndTrayHasNoOpaqueTile() {
    val light = brandBitmap(64, darkTheme = false, tray = true)
    val dark = brandBitmap(64, darkTheme = true, tray = true)
    assertEquals(0, light.getRGB(0, 0) ushr 24)
    var differences = 0
    for (y in 0 until 64) for (x in 0 until 64) if (light.getRGB(x, y) != dark.getRGB(x, y)) differences++
    assertTrue(differences > 500)
  }

  @Test fun launcherPackagingAndAccessibilityFallbackAreExplicit() {
    val build = File("build.gradle.kts").readText()
    assertTrue(build.contains("iconFile.set(layout.buildDirectory.file(\"branding/Veilark.icns\"))"))
    assertTrue(VisualPreferences().reduceMotion)
    assertTrue(VisualPreferences().reduceTransparency)
    assertFalse(File("src/main/kotlin/app/veilark/macos/Main.kt").readText().contains("Icons.Outlined.Shield"))
  }
}
