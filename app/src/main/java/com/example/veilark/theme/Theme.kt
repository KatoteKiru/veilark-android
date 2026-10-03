package com.example.veilark.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Material 3 Expressive shape scale, including the larger "increased" tokens
// used by hero surfaces (connection card, full-screen dialogs). Values keep the
// restrained Veilark rhythm while following the expressive corner ladder.
// Material 3 1.5.0-alpha18 still marks the expressive Shapes constructor and
// MaterialExpressiveTheme as library-group restricted; both became public API in
// later alphas, which need compileSdk 37 (see gradle/libs.versions.toml).
@SuppressLint("RestrictedApi")
private val VeilarkShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(16.dp),
  medium = RoundedCornerShape(22.dp),
  large = RoundedCornerShape(28.dp),
  extraLarge = RoundedCornerShape(36.dp),
  largeIncreased = RoundedCornerShape(32.dp),
  extraLargeIncreased = RoundedCornerShape(40.dp),
  extraExtraLarge = RoundedCornerShape(48.dp),
)

private val LightColors = lightColorScheme(
  primary = LightPrimary,
  onPrimary = LightOnPrimary,
  primaryContainer = LightPrimaryContainer,
  onPrimaryContainer = LightOnPrimaryContainer,
  secondaryContainer = LightSecondaryContainer,
  onSecondaryContainer = LightOnSecondaryContainer,
  tertiary = LightTertiary,
  onTertiary = LightOnTertiary,
  tertiaryContainer = LightTertiaryContainer,
  onTertiaryContainer = LightOnTertiaryContainer,
  background = LightBackground,
  onBackground = LightOnBackground,
  surface = LightSurface,
  onSurface = LightOnSurface,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = LightOnSurfaceVariant,
  secondary = LightOnSurfaceVariant,
  surfaceTint = LightPrimary,
  surfaceContainerLowest = LightOnPrimary,
  surfaceContainerLow = LightBackground,
  surfaceContainer = LightSurfaceContainer,
  surfaceContainerHigh = LightSurfaceContainerHigh,
  surfaceContainerHighest = LightSurfaceVariant,
  outline = Color(0xFF797A83),
  outlineVariant = Color(0xFFD0D0D8),
)

private val DarkColors = darkColorScheme(
  primary = DarkPrimary,
  onPrimary = DarkOnPrimary,
  primaryContainer = DarkPrimaryContainer,
  onPrimaryContainer = DarkOnPrimaryContainer,
  secondaryContainer = DarkSecondaryContainer,
  onSecondaryContainer = DarkOnSecondaryContainer,
  tertiary = DarkTertiary,
  onTertiary = DarkOnTertiary,
  tertiaryContainer = DarkTertiaryContainer,
  onTertiaryContainer = DarkOnTertiaryContainer,
  background = DarkBackground,
  onBackground = DarkOnBackground,
  surface = DarkSurface,
  onSurface = DarkOnSurface,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = DarkOnSurfaceVariant,
  secondary = DarkOnSurfaceVariant,
  surfaceTint = DarkPrimary,
  surfaceContainerLowest = Color(0xFF0B0C10),
  surfaceContainerLow = Color(0xFF191A20),
  surfaceContainer = DarkSurfaceContainer,
  surfaceContainerHigh = DarkSurfaceContainerHigh,
  surfaceContainerHighest = Color(0xFF34353E),
  outline = Color(0xFF93939F),
  outlineVariant = Color(0xFF454650),
)

/**
 * Veilark's Material 3 Expressive theme: brand palette, expressive motion
 * (spring-based spatial and effects specs), the expressive shape ladder and a
 * typography scale with emphasized roles.
 */
@SuppressLint("RestrictedApi")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VeilarkTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val colors = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
      dynamicDarkColorScheme(context)
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
      dynamicLightColorScheme(context)
    darkTheme -> DarkColors
    else -> LightColors
  }
  MaterialExpressiveTheme(
    colorScheme = colors,
    motionScheme = MotionScheme.expressive(),
    shapes = VeilarkShapes,
    typography = Typography,
    content = content,
  )
}
