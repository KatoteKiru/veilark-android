package com.example.veilark.ui

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.nio.charset.StandardCharsets

/** Avoid AWT `DataFlavor.stringFlavor` ("Unicode String") failures on macOS paste. */
object MacClipboard : ClipboardManager {
  override fun getText(): AnnotatedString? =
    readPasteboard()?.let(::AnnotatedString)

  override fun setText(annotatedString: AnnotatedString) {
    writePasteboard(annotatedString.text)
  }

  override fun hasText(): Boolean = !readPasteboard().isNullOrEmpty()

  fun readPasteboard(): String? = runCatching {
    val process = ProcessBuilder("pbpaste", "-Prefer", "txt")
      .redirectErrorStream(true)
      .start()
    val text = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    process.waitFor()
    text.takeIf { it.isNotBlank() }
  }.getOrNull()

  private fun writePasteboard(text: String) {
    runCatching {
      val process = ProcessBuilder("pbcopy").start()
      process.outputStream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
      process.waitFor()
    }
  }
}
