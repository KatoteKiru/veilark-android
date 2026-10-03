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
      LightOnTertiary to LightTertiary,
      LightOnTertiaryContainer to LightTertiaryContainer,
      DarkOnTertiary to DarkTertiary,
      DarkOnTertiaryContainer to DarkTertiaryContainer,
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

  @Test
  fun logLevelAccentsAreReadableOnLogCards() {
    // Technical log entries draw the level accent as text on surfaceContainer.
    listOf(
      LightTertiary to LightSurfaceContainer,
      LightPrimary to LightSurfaceContainer,
      DarkTertiary to DarkSurfaceContainer,
      DarkPrimary to DarkSurfaceContainer,
    ).forEach { (accent, card) ->
      assertTrue("Log accent must meet WCAG AA on its card", contrast(accent, card) >= 4.5)
    }
  }

  @Test
  fun warnAccentIsDistinctFromInfoAccent() {
    // WARN uses tertiary, INFO uses primary: they must differ in hue, not only
    // in lightness, so the levels stay distinguishable at a glance.
    listOf(LightTertiary to LightPrimary, DarkTertiary to DarkPrimary).forEach { (warn, info) ->
      val warnSpread = maxOf(warn.red, warn.green, warn.blue) - minOf(warn.red, warn.green, warn.blue)
      assertTrue("Tertiary must carry a visible hue", warnSpread >= 0.25f)
      assertTrue("Tertiary must not equal primary", warn != info)
      val distance = kotlin.math.sqrt(
        (warn.red - info.red).let { it * it } +
          (warn.green - info.green).let { it * it } +
          (warn.blue - info.blue).let { it * it },
      )
      assertTrue("WARN and INFO accents must be clearly apart", distance >= 0.3f)
    }
  }

  private fun contrast(a: Color, b: Color): Double {
    val light = maxOf(a.luminance(), b.luminance()).toDouble()
    val dark = minOf(a.luminance(), b.luminance()).toDouble()
    return (light + 0.05) / (dark + 0.05)
  }
}
