package com.example.veilark.ui

import java.nio.charset.StandardCharsets

/** Avoid AWT `DataFlavor.stringFlavor` ("Unicode String") failures on macOS paste. */
object MacClipboard {
  fun readPasteboard(): String? = runCatching {
    val process = ProcessBuilder("pbpaste", "-Prefer", "txt")
      .redirectErrorStream(true)
      .start()
    val text = process.inputStream.readBytes().toString(StandardCharsets.UTF_8)
    process.waitFor()
    text.takeIf { it.isNotBlank() }
  }.getOrNull()

  fun writePasteboard(text: String) {
    runCatching {
      val process = ProcessBuilder("pbcopy").start()
      process.outputStream.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
      process.waitFor()
    }
  }
}
