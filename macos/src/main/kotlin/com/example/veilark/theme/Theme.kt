package com.example.veilark.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPrimary = Color(0xFF275D8C)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD2E4FF)
private val LightOnPrimaryContainer = Color(0xFF0B446F)
private val LightSecondaryContainer = Color(0xFFDCE3EA)
private val LightOnSecondaryContainer = Color(0xFF27323B)
private val LightBackground = Color(0xFFF9F9FC)
private val LightOnBackground = Color(0xFF191C20)
private val LightSurface = Color(0xFFF9F9FC)
private val LightOnSurface = Color(0xFF191C20)
private val LightSurfaceVariant = Color(0xFFE0E3E8)
private val LightOnSurfaceVariant = Color(0xFF43474E)

private val DarkPrimary = Color(0xFFA1CAFC)
private val DarkOnPrimary = Color(0xFF003257)
private val DarkPrimaryContainer = Color(0xFF064A75)
private val DarkOnPrimaryContainer = Color(0xFFD2E4FF)
private val DarkSecondaryContainer = Color(0xFF3C4852)
private val DarkOnSecondaryContainer = Color(0xFFDCE3EA)
private val DarkBackground = Color(0xFF111317)
private val DarkOnBackground = Color(0xFFE2E2E7)
private val DarkSurface = Color(0xFF111317)
private val DarkOnSurface = Color(0xFFE2E2E7)
private val DarkSurfaceVariant = Color(0xFF43474D)
private val DarkOnSurfaceVariant = Color(0xFFC3C7CF)

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
)

@Composable
fun VeilarkTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = if (darkTheme) DarkColors else LightColors,
    content = content,
  )
}
