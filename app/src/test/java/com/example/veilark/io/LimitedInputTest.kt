package com.example.veilark.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class LimitedInputTest {
  @Test
  fun returnsTheWholeInputWhenItFits() {
    val source = "veilark".toByteArray()
    assertArrayEquals(source, ByteArrayInputStream(source).readAtMost(64))
  }

  @Test
  fun stopsAtTheConfiguredLimit() {
    val source = ByteArray(32) { it.toByte() }
    assertEquals(9, ByteArrayInputStream(source).readAtMost(9).size)
  }
}
