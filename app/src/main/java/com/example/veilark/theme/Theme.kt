package com.example.veilark.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
  primary = LightPrimary,
  onPrimary = LightOnPrimary,
  primaryContainer = LightPrimaryContainer,
  onPrimaryContainer = LightOnPrimaryContainer,
  secondaryContainer = LightSecondaryContainer,
  onSecondaryContainer = LightOnSecondaryContainer,
  background = LightBackground,
  onBackground = LightOnBackground,
  surface = LightSurface,
  onSurface = LightOnSurface,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = LightOnSurfaceVariant,
  secondary = LightOnSurfaceVariant,
  tertiary = LightPrimary,
  surfaceTint = LightPrimary,
  surfaceContainerLowest = LightOnPrimary,
  surfaceContainerLow = LightBackground,
  surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF0F0F4),
  surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFEAEAF0),
  surfaceContainerHighest = LightSurfaceVariant,
  outline = androidx.compose.ui.graphics.Color(0xFF797A83),
  outlineVariant = androidx.compose.ui.graphics.Color(0xFFD0D0D8),
)

private val DarkColors = darkColorScheme(
  primary = DarkPrimary,
  onPrimary = DarkOnPrimary,
  primaryContainer = DarkPrimaryContainer,
  onPrimaryContainer = DarkOnPrimaryContainer,
  secondaryContainer = DarkSecondaryContainer,
  onSecondaryContainer = DarkOnSecondaryContainer,
  background = DarkBackground,
  onBackground = DarkOnBackground,
  surface = DarkSurface,
  onSurface = DarkOnSurface,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = DarkOnSurfaceVariant,
  secondary = DarkOnSurfaceVariant,
  tertiary = DarkPrimary,
  surfaceTint = DarkPrimary,
  surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF0B0C10),
  surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF191A20),
  surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1F2027),
  surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF292A32),
  surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF34353E),
  outline = androidx.compose.ui.graphics.Color(0xFF93939F),
  outlineVariant = androidx.compose.ui.graphics.Color(0xFF454650),
)

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
  MaterialTheme(
    colorScheme = colors,
    typography = Typography,
    content = content,
  )
}
