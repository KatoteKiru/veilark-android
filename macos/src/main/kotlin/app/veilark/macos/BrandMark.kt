package app.veilark.macos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp

/** Canonical monochrome artwork shared by every Veilark client. */
internal val VeilarkMark: ImageVector = ImageVector.Builder(
  name = "Veilark", defaultWidth = 32.dp, defaultHeight = 32.dp,
  viewportWidth = 432f, viewportHeight = 432f,
).apply {
  BrandIcon.paths().forEach { data ->
    addPath(pathData = PathParser().parsePathString(data).toNodes(),
      fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
  }
}.build()
