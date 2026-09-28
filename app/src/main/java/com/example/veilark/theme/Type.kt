package com.example.veilark.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The system typeface retains Android's font scaling and stays crisp at every
// density; expressive hierarchy is reserved for the primary VPN state.
val Typography = Typography(
  headlineMedium = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 30.sp,
    lineHeight = 36.sp,
    letterSpacing = (-0.35f).sp,
  ),
  titleLarge = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 22.sp,
    lineHeight = 28.sp,
  ),
  labelLarge = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 20.sp,
  ),
)
