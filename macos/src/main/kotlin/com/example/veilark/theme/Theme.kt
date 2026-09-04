package com.example.veilark.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPrimary = Color(0xFF303238)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFE6E7EA)
private val LightOnPrimaryContainer = Color(0xFF24262B)
private val LightSecondaryContainer = Color(0xFFE3E4E7)
private val LightOnSecondaryContainer = Color(0xFF282A30)
private val LightBackground = Color(0xFFF7F7F8)
private val LightOnBackground = Color(0xFF1B1C20)
private val LightSurface = Color(0xFFF7F7F8)
private val LightOnSurface = Color(0xFF1B1C20)
private val LightSurfaceVariant = Color(0xFFE8E8EC)
private val LightOnSurfaceVariant = Color(0xFF565860)

private val DarkPrimary = Color(0xFFE5E5EA)
private val DarkOnPrimary = Color(0xFF202126)
private val DarkPrimaryContainer = Color(0xFF34353C)
private val DarkOnPrimaryContainer = Color(0xFFE6E7EA)
private val DarkSecondaryContainer = Color(0xFF35363D)
private val DarkOnSecondaryContainer = Color(0xFFE3E4E7)
private val DarkBackground = Color(0xFF17181C)
private val DarkOnBackground = Color(0xFFECECF0)
private val DarkSurface = Color(0xFF17181C)
private val DarkOnSurface = Color(0xFFECECF0)
private val DarkSurfaceVariant = Color(0xFF38393F)
private val DarkOnSurfaceVariant = Color(0xFFBBBCC5)

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
