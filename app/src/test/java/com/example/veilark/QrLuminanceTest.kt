package com.example.veilark

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QrLuminanceTest {
  @Test
  fun extractsCropWithRowAndPixelStride() {
    val width = 4
    val height = 3
    val rowStride = 10
    val pixelStride = 2
    val buffer = ByteBuffer.allocate(rowStride * height)
    val pixels = arrayOf(
      intArrayOf(1, 2, 3, 4),
      intArrayOf(11, 12, 13, 14),
      intArrayOf(21, 22, 23, 24),
    )
    for (y in pixels.indices) {
      for (x in pixels[y].indices) {
        buffer.put(y * rowStride + x * pixelStride, pixels[y][x].toByte())
      }
    }

    val frame = QrLuminance.fromYPlane(
      buffer = buffer,
      imageWidth = width,
      imageHeight = height,
      rowStride = rowStride,
      pixelStride = pixelStride,
      cropLeft = 1,
      cropTop = 1,
      cropRight = 4,
      cropBottom = 3,
      rotationDegrees = 0,
    )

    assertEquals(3, frame.width)
    assertEquals(2, frame.height)
    assertArrayEquals(byteArrayOf(12, 13, 14, 22, 23, 24), frame.data)
  }

  @Test
  fun rotatesClockwiseAndPreservesAllPixels() {
    val frame = QrLuminance.rotate(
      data = byteArrayOf(1, 2, 3, 4, 5, 6),
      width = 3,
      height = 2,
      rotationDegrees = 90,
    )

    assertEquals(2, frame.width)
    assertEquals(3, frame.height)
    assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), frame.data)
  }

  @Test
  fun normalizesNegativeRotation() {
    val frame = QrLuminance.rotate(
      data = byteArrayOf(1, 2, 3, 4),
      width = 2,
      height = 2,
      rotationDegrees = -90,
    )

    assertArrayEquals(byteArrayOf(2, 4, 1, 3), frame.data)
  }

  @Test
  fun decodesARealQrFrameAfterCameraRotation() {
    val payload = "https://example.invalid/subscription"
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
    val luminance = ByteArray(matrix.width * matrix.height) { index ->
      val x = index % matrix.width
      val y = index / matrix.width
      if (matrix[x, y]) 0 else 0xff.toByte()
    }
    val rotated = QrLuminance.rotate(luminance, matrix.width, matrix.height, 90)

    assertEquals(payload, QrFrameDecoder().decode(rotated))
  }
}
