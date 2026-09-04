package com.example.veilark.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
  @Test
  fun normalTextAndPrimaryActionsMeetContrastInBothThemes() {
    val pairs = listOf(
      LightOnPrimary to LightPrimary,
      LightOnPrimaryContainer to LightPrimaryContainer,
      LightOnSurface to LightSurface,
      LightOnSurfaceVariant to LightSurface,
      LightOnSecondaryContainer to LightSecondaryContainer,
      DarkOnPrimary to DarkPrimary,
      DarkOnPrimaryContainer to DarkPrimaryContainer,
      DarkOnSurface to DarkSurface,
      DarkOnSurfaceVariant to DarkSurface,
      DarkOnSecondaryContainer to DarkSecondaryContainer,
    )
    pairs.forEach { (foreground, background) ->
      assertTrue("Text pair must meet WCAG AA", contrast(foreground, background) >= 4.5)
    }
  }

  @Test
  fun brandPaletteRemainsNeutral() {
    listOf(LightPrimary, DarkPrimary, LightPrimaryContainer, DarkPrimaryContainer).forEach {
      val spread = maxOf(it.red, it.green, it.blue) - minOf(it.red, it.green, it.blue)
      assertTrue("Brand surfaces must not become colored status decoration", spread <= 0.05f)
    }
  }

  private fun contrast(a: Color, b: Color): Double {
    val light = maxOf(a.luminance(), b.luminance()).toDouble()
    val dark = minOf(a.luminance(), b.luminance()).toDouble()
    return (light + 0.05) / (dark + 0.05)
  }
}
