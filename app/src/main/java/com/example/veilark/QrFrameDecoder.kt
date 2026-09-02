package com.example.veilark

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.PlanarYUVLuminanceSource
import java.nio.ByteBuffer

/** A tightly packed luminance frame that is safe to pass to a pure-Java decoder. */
internal data class QrLuminanceFrame(
  val data: ByteArray,
  val width: Int,
  val height: Int,
)

/**
 * Copies only the Y plane from an ImageProxy. Camera2 is allowed to expose padding and a
 * non-unit pixel stride, so passing the camera buffer directly to ZXing would decode the wrong
 * rows on some devices. The crop is applied before rotation, as required by ImageProxy.
 */
internal object QrLuminance {
  fun fromYPlane(
    buffer: ByteBuffer,
    imageWidth: Int,
    imageHeight: Int,
    rowStride: Int,
    pixelStride: Int,
    cropLeft: Int,
    cropTop: Int,
    cropRight: Int,
    cropBottom: Int,
    rotationDegrees: Int,
  ): QrLuminanceFrame {
    require(imageWidth > 0 && imageHeight > 0) { "image dimensions must be positive" }
    require(rowStride > 0 && pixelStride > 0) { "plane strides must be positive" }
    val left = cropLeft.coerceIn(0, imageWidth)
    val top = cropTop.coerceIn(0, imageHeight)
    val right = cropRight.coerceIn(left, imageWidth)
    val bottom = cropBottom.coerceIn(top, imageHeight)
    require(right > left && bottom > top) { "crop must have positive dimensions" }

    val width = right - left
    val height = bottom - top
    val packed = ByteArray(width * height)
    val source = buffer.duplicate()
    val base = source.position()
    val limit = source.limit()
    for (y in 0 until height) {
      val rowBase = base + (top + y) * rowStride + left * pixelStride
      for (x in 0 until width) {
        val index = rowBase + x * pixelStride
        require(index >= base && index < limit) { "Y plane buffer is shorter than its metadata" }
        packed[y * width + x] = source.get(index)
      }
    }
    return rotate(packed, width, height, rotationDegrees)
  }

  fun rotate(data: ByteArray, width: Int, height: Int, rotationDegrees: Int): QrLuminanceFrame {
    require(width > 0 && height > 0) { "frame dimensions must be positive" }
    require(data.size == width * height) { "frame data does not match dimensions" }
    val rotation = ((rotationDegrees % 360) + 360) % 360
    require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
      "rotation must be a multiple of 90 degrees"
    }
    if (rotation == 0) return QrLuminanceFrame(data, width, height)

    val rotatedWidth = if (rotation == 180) width else height
    val rotatedHeight = if (rotation == 180) height else width
    val rotated = ByteArray(data.size)
    for (y in 0 until rotatedHeight) {
      for (x in 0 until rotatedWidth) {
        val sourceX: Int
        val sourceY: Int
        when (rotation) {
          90 -> {
            sourceX = y
            sourceY = height - 1 - x
          }
          180 -> {
            sourceX = width - 1 - x
            sourceY = height - 1 - y
          }
          else -> {
            sourceX = width - 1 - y
            sourceY = x
          }
        }
        rotated[y * rotatedWidth + x] = data[sourceY * width + sourceX]
      }
    }
    return QrLuminanceFrame(rotated, rotatedWidth, rotatedHeight)
  }
}

/** Stateless-per-frame QR decoding backed only by the ZXing Java core. */
internal class QrFrameDecoder {
  private val reader = MultiFormatReader()
  private val hints = mapOf<DecodeHintType, Any>(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
  )

  fun decode(frame: QrLuminanceFrame): String? {
    reader.reset()
    return try {
      val source = PlanarYUVLuminanceSource(
        frame.data,
        frame.width,
        frame.height,
        0,
        0,
        frame.width,
        frame.height,
        false,
      )
      reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
        .text
        .trim()
        .takeIf(String::isNotEmpty)
    } catch (_: ReaderException) {
      null
    } finally {
      reader.reset()
    }
  }
}
