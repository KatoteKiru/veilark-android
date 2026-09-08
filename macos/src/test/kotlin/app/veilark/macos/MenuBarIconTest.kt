package app.veilark.macos

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class MenuBarIconTest {
  private fun image() = MenuBarIcon().toAwtImage(Density(1f), LayoutDirection.Ltr, Size(22f, 22f))

  @Test fun awtReceivesNativeResolutionVariantsFromVector() {
    val image = image()
    assertTrue(image is MultiResolutionImage)
    for (pixels in listOf(22, 44, 66)) {
      val variant = (image as MultiResolutionImage).getResolutionVariant(pixels.toDouble(), pixels.toDouble())
      assertEquals(pixels, variant.getWidth(null))
      assertEquals(pixels, variant.getHeight(null))
      val bitmap = buffered(variant)
      var visible = 0
      for (y in 0 until pixels) for (x in 0 until pixels) {
        val argb = bitmap.getRGB(x, y)
        if (argb ushr 24 > 0) {
          visible++
          assertEquals("Template must contain black ink only", 0, argb and 0xffffff)
        }
        if (x == 0 || y == 0 || x == pixels - 1 || y == pixels - 1) {
          assertEquals("Keep clear space around the silhouette", 0, argb ushr 24)
        }
      }
      assertTrue(visible > pixels * pixels / 5)
      assertTrue(visible < pixels * pixels * 3 / 5)
    }
  }

  @Test fun retinaIsNotAnUpscaledLowResolutionBitmap() {
    val image = image() as MultiResolutionImage
    val low = image.getResolutionVariant(22.0, 22.0)
    val retina = buffered(image.getResolutionVariant(44.0, 44.0))
    val upscaled = BufferedImage(44, 44, BufferedImage.TYPE_INT_ARGB)
    upscaled.createGraphics().apply {
      drawImage(low, 0, 0, 44, 44, null)
      dispose()
    }
    var different = 0
    for (y in 0 until 44) for (x in 0 until 44) {
      if (retina.getRGB(x, y) != upscaled.getRGB(x, y)) different++
    }
    assertTrue("Retina must be independently rasterized", different > 100)
  }

  @Test fun templateModeIsEnabledBeforeAwtAndInPackagedLauncher() {
    val main = File("src/main/kotlin/app/veilark/macos/Main.kt").readText().substringAfter("fun main() {")
    assertTrue(main.indexOf("apple.awt.enableTemplateImages") < main.indexOf("StartupDiagnostics.install()"))
    assertTrue(main.contains("val trayIcon = remember { MenuBarIcon() }"))
    assertTrue(File("build.gradle.kts").readText().contains("-Dapple.awt.enableTemplateImages=true"))
  }

  @Test fun exportRasterPreviewForVisualInspection() {
    val image = image() as MultiResolutionImage
    val sheet = BufferedImage(480, 128, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    for (dark in listOf(false, true)) {
      val top = if (dark) 64 else 0
      g.color = java.awt.Color(if (dark) 0x242427 else 0xf2f2f4)
      g.fillRect(0, top, 480, 64)
      for ((column, pixels) in listOf(22, 44).withIndex()) {
        val mask = buffered(image.getResolutionVariant(pixels.toDouble(), pixels.toDouble()))
        if (dark) for (y in 0 until pixels) for (x in 0 until pixels) {
          mask.setRGB(x, y, mask.getRGB(x, y) or 0xffffff)
        }
        g.drawImage(mask, 16 + column * 60, top + (64 - pixels) / 2, null)
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(mask, 170 + column * 150, top + 5, 55, 55, null)
      }
    }
    g.dispose()
    val target = File("build/reports/ui/menu-bar-icon.png")
    target.parentFile.mkdirs()
    ImageIO.write(sheet, "png", target)
  }

  private fun buffered(image: java.awt.Image): BufferedImage =
    BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also {
      it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    }
}
