package com.example.veilark.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The system typeface retains Android's font scaling and stays crisp at every
// density. Material 3 Expressive adds *Emphasized roles: heavier variants used
// for the primary VPN state, hero actions and selected choices.
private val HeadlineMedium = TextStyle(
  fontWeight = FontWeight.SemiBold,
  fontSize = 30.sp,
  lineHeight = 36.sp,
  letterSpacing = (-0.35f).sp,
)
private val HeadlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp)
private val TitleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
private val TitleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
private val TitleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
private val BodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val BodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
private val BodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
private val LabelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
private val LabelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)

val Typography = Typography(
  headlineMedium = HeadlineMedium,
  headlineSmall = HeadlineSmall,
  titleLarge = TitleLarge,
  titleMedium = TitleMedium,
  titleSmall = TitleSmall,
  bodyLarge = BodyLarge,
  bodyMedium = BodyMedium,
  bodySmall = BodySmall,
  labelLarge = LabelLarge,
  labelMedium = LabelMedium,
  headlineLargeEmphasized = TextStyle(
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 40.sp,
    letterSpacing = (-0.4f).sp,
  ),
  headlineMediumEmphasized = HeadlineMedium.copy(fontWeight = FontWeight.Bold),
  headlineSmallEmphasized = HeadlineSmall.copy(fontWeight = FontWeight.Bold),
  titleLargeEmphasized = TitleLarge.copy(fontWeight = FontWeight.Bold),
  titleMediumEmphasized = TitleMedium.copy(fontWeight = FontWeight.SemiBold),
  titleSmallEmphasized = TitleSmall.copy(fontWeight = FontWeight.SemiBold),
  bodyLargeEmphasized = BodyLarge.copy(fontWeight = FontWeight.Medium),
  bodyMediumEmphasized = BodyMedium.copy(fontWeight = FontWeight.Medium),
  bodySmallEmphasized = BodySmall.copy(fontWeight = FontWeight.Medium),
  labelLargeEmphasized = LabelLarge.copy(fontWeight = FontWeight.Bold),
  labelMediumEmphasized = LabelMedium.copy(fontWeight = FontWeight.SemiBold),
)
