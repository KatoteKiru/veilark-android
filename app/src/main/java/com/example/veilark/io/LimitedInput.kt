package com.example.veilark.io

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

fun InputStream.readAtMost(maxBytes: Int): ByteArray {
  require(maxBytes >= 0)
  val output = ByteArrayOutputStream(min(maxBytes, DEFAULT_BUFFER_SIZE))
  val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
  var remaining = maxBytes
  while (remaining > 0) {
    val count = read(buffer, 0, min(buffer.size, remaining))
    if (count < 0) break
    output.write(buffer, 0, count)
    remaining -= count
  }
  return output.toByteArray()
}
