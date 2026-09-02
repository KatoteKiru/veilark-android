package com.example.veilark

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import java.security.MessageDigest

/** Generates and shares a QR image without putting credentials into app-owned URLs. */
internal object QrCodeShare {
  fun bitmap(payload: String, size: Int = 768): Bitmap {
    require(payload.isNotBlank()) { "QR payload must not be empty" }
    require(size in 128..2048) { "QR size is outside the supported range" }
    val matrix = QRCodeWriter().encode(
      payload,
      BarcodeFormat.QR_CODE,
      size,
      size,
      mapOf(EncodeHintType.MARGIN to 2),
    )
    val pixels = IntArray(size * size) { index ->
      if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
  }

  fun share(context: Context, payload: String, title: String): Boolean = runCatching {
    val directory = File(context.cacheDir, "qr").apply { mkdirs() }
    val output = File(directory, "${digest(payload)}.png")
    output.outputStream().use { stream ->
      check(bitmap(payload).compress(Bitmap.CompressFormat.PNG, 100, stream)) {
        "QR image encoding failed"
      }
    }
    val uri = FileProvider.getUriForFile(
      context,
      "${context.packageName}.qr",
      output,
    )
    val send = Intent(Intent.ACTION_SEND)
      .setType("image/png")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .putExtra(Intent.EXTRA_TEXT, payload)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
    true
  }.getOrDefault(false)

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    .take(24)
}
