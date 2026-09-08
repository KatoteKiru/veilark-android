package app.veilark.macos

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser

/** Alpha-only template artwork, rasterized at the resolution requested by AWT. */
internal class MenuBarIcon : Painter() {
  private val paths = BrandIcon.paths().map { data ->
    PathParser().parsePathString(data).toPath().apply { fillType = PathFillType.EvenOdd }
  }

  override val intrinsicSize = Size(22f, 22f)

  override fun DrawScope.onDraw() {
    // Keep the canonical silhouette and its clear space; do not enlarge a pre-rendered bitmap.
    val extent = minOf(size.width, size.height)
    withTransform({
      translate((size.width - extent) / 2f, (size.height - extent) / 2f)
      scale(extent / 432f, extent / 432f, pivot = androidx.compose.ui.geometry.Offset.Zero)
    }) {
      paths.forEach { drawPath(it, Color.Black) }
    }
  }
}
