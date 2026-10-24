package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test

/** Native metadata boundary checks only; these do not execute JNI or socket admission. */
class CoreFairwayRequestCodecTest {
  private fun values(protocol: Long = 6) =
    longArrayOf(1, 2, 3, Long.MAX_VALUE, 0xc000_0201L, 65535, 0xcb00_7101L, 443, protocol)

  private fun rejects(metadata: LongArray) {
    try {
      CoreFairwayRequestCodec.decode(metadata)
      fail("Invalid native request metadata was accepted")
    } catch (_: IllegalArgumentException) { }
  }

  @Test fun tcpAndUdpPreserveUnsignedAddressesAndFullIdentity() {
    for (protocol in listOf(6L, 17L)) {
      val original = values(protocol)
      val request = CoreFairwayRequestCodec.decode(original)
      assertTrue(request.sourceIpv4 < 0)
      assertTrue(request.destinationIpv4 < 0)
      assertEquals(Long.MAX_VALUE, request.flowId)
      assertEquals(protocol.toInt(), request.protocol.number)
      assertArrayEquals(original, CoreFairwayRequestCodec.encode(request))
      assertEquals(request, CoreFairwayRequestCodec.decode(CoreFairwayRequestCodec.encode(request)))
    }
    assertNotEquals(CoreFairwayRequestCodec.decode(values(6)), CoreFairwayRequestCodec.decode(values(17)))
  }

  @Test fun rejectsWrongShapeAndAllNonPositiveIdentityFields() {
    for (length in listOf(0, 8, 10)) rejects(values().copyOf(length))
    for (index in 0..3) {
      for (invalid in listOf(0L, -1L, Long.MIN_VALUE)) {
        rejects(values().also { it[index] = invalid })
      }
    }
  }

  @Test fun rejectsNarrowingOverflowAndNonUnicastAddresses() {
    for (index in listOf(4, 6)) {
      for (invalid in listOf(-1L, Long.MIN_VALUE, 0x1_0000_0000L, Long.MAX_VALUE,
        0L, 0x7f00_0001L, 0xe000_0001L, 0xffff_ffffL)) {
        rejects(values().also { it[index] = invalid })
      }
    }
    for (index in listOf(5, 7)) {
      for (invalid in listOf(0L, -1L, 65536L, 0x1_0000_0001L, Long.MAX_VALUE)) {
        rejects(values().also { it[index] = invalid })
      }
    }
  }

  @Test fun protocolMustMatchExactLongValueWithoutTruncation() {
    for (invalid in listOf(0L, -1L, 1L, 256L, 0x1_0000_0006L, 0x1_0000_0011L,
      Long.MIN_VALUE, Long.MAX_VALUE)) rejects(values(invalid))
  }

  @Test fun pingIdentifierIncludesZeroAndHasNoDestinationPort() {
    for (identifier in listOf(0L, 1L, 65535L)) {
      val input = values(1).also { it[5] = identifier; it[7] = 0 }
      val decoded = CoreFairwayRequestCodec.decode(input)
      assertEquals(CoreFairwaySocketProtocol.ICMP, decoded.protocol)
      assertEquals(identifier.toInt(), decoded.sourcePort)
      assertEquals(0, decoded.destinationPort)
      assertArrayEquals(input, CoreFairwayRequestCodec.encode(decoded))
    }
    for (identifier in listOf(-1L, 65536L, 0x1_0000_0001L, Long.MAX_VALUE)) {
      rejects(values(1).also { it[5] = identifier; it[7] = 0 })
    }
    for (port in listOf(-1L, 1L, 65535L, 0x1_0000_0000L)) {
      rejects(values(1).also { it[5] = 0; it[7] = port })
    }
  }

  @Test fun pingStillRejectsBadIdentityAndAddresses() {
    val ping = values(1).also { it[5] = 0; it[7] = 0 }
    for (index in 0..3) rejects(ping.copyOf().also { it[index] = 0 })
    for (index in listOf(4, 6)) {
      for (address in listOf(0L, 0x7f00_0001L, 0xe000_0001L, 0x1_0000_0000L)) {
        rejects(ping.copyOf().also { it[index] = address })
      }
    }
  }

  @Test fun mutableArraysNeverAliasTheDecodedRequestOrAnotherEncoding() {
    val input = values()
    val saved = input.copyOf()
    val decoded = CoreFairwayRequestCodec.decode(input)
    assertArrayEquals(saved, input)
    input.fill(0)
    val first = CoreFairwayRequestCodec.encode(decoded)
    assertArrayEquals(saved, first)
    val second = CoreFairwayRequestCodec.encode(decoded)
    assertNotSame(first, second)
    first.fill(0)
    assertArrayEquals(saved, second)
    assertArrayEquals(saved, CoreFairwayRequestCodec.encode(decoded))
  }
}
