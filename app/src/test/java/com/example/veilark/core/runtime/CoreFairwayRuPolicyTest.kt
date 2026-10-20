package com.example.veilark.core.runtime

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Pinned full-policy data checks; not production activation or live Ozon egress. */
class CoreFairwayRuPolicyTest {
  private fun asset(): ByteArray = listOf(File("src/main/assets/${CoreFairwayRuPolicy.ASSET}"),
    File("app/src/main/assets/${CoreFairwayRuPolicy.ASSET}")).first { it.isFile }.readBytes()

  private fun ip(value: String): Long = value.split('.').fold(0L) { n, part -> (n shl 8) or part.toLong() }
  private fun ranges(networks: IntArray, prefixes: IntArray): List<LongRange> = networks.indices.map {
    val first = networks[it].toLong() and 0xffff_ffffL
    first until first + (1L shl (32 - prefixes[it]))
  }
  private fun contains(ranges: List<LongRange>, address: Long): Boolean {
    var low = 0
    var high = ranges.lastIndex
    while (low <= high) {
      val middle = (low + high) ushr 1
      val range = ranges[middle]
      when {
        address < range.first -> high = middle - 1
        address > range.last -> low = middle + 1
        else -> return true
      }
    }
    return false
  }

  @Test fun completePinnedAssetRetainsEveryCanonicalPrefixAndExactAddressTotal() {
    val bytes = asset()
    val policy = CoreFairwayRuPolicy.prepare(bytes)
    val networks = policy.networks
    val prefixes = policy.prefixes
    val lines = bytes.toString(Charsets.US_ASCII).lineSequence().filter(String::isNotEmpty).toList()
    assertEquals(8662, networks.size)
    assertEquals(8662, prefixes.size)
    assertEquals(45_223_232L, policy.directRuIpv4)
    var previousLast = -1L
    var total = 0L
    for (index in networks.indices) {
      val cidr = lines[index].split('/')
      val first = networks[index].toLong() and 0xffff_ffffL
      assertEquals(ip(cidr[0]), first)
      assertEquals(cidr[1].toInt(), prefixes[index])
      val size = 1L shl (32 - prefixes[index])
      assertEquals(0L, first % size)
      assertTrue(first > previousLast)
      previousLast = first + size - 1
      total += size
    }
    assertEquals(policy.directRuIpv4, total)
  }

  @Test fun membershipPreservesEveryBoundaryAndForeignGapsWithoutExpansion() {
    val policy = CoreFairwayRuPolicy.prepare(asset())
    val ranges = ranges(policy.networks, policy.prefixes)
    ranges.forEachIndexed { index, range ->
      assertTrue(contains(ranges, range.first))
      assertTrue(contains(ranges, range.last))
      if (index == 0 || ranges[index - 1].last + 1 < range.first) {
        assertFalse(contains(ranges, range.first - 1))
      }
      if (index == ranges.lastIndex || range.last + 1 < ranges[index + 1].first) {
        assertFalse(contains(ranges, range.last + 1))
      }
    }
    assertTrue(contains(ranges, ip("77.88.8.8")))
    for (foreign in listOf("0.0.0.0", "1.1.1.1", "8.8.8.8", "127.0.0.1", "224.0.0.1", "255.255.255.255")) {
      assertFalse(foreign, contains(ranges, ip(foreign)))
    }
  }

  @Test fun callerMutationsCannotChangePolicyOrSubsequentCacheOutputs() {
    val input = asset()
    val original = input.copyOf()
    val policy = CoreFairwayRuPolicy.prepare(input)
    assertArrayEquals(original, input)
    val expectedNetworks = policy.networks
    val expectedPrefixes = policy.prefixes
    policy.networks.fill(0)
    policy.prefixes.fill(0)
    input.fill(0)
    assertArrayEquals(expectedNetworks, policy.networks)
    assertArrayEquals(expectedPrefixes, policy.prefixes)
    val cached = CoreFairwayRuPolicy.prepare(original)
    assertArrayEquals(expectedNetworks, cached.networks)
    assertArrayEquals(expectedPrefixes, cached.prefixes)
    assertNotSame(cached.networks, cached.networks)
    assertNotSame(cached.prefixes, cached.prefixes)
  }

  @Test fun warmCacheStillRejectsAllInvalidBytesAndNeverFallsBackToSubset() {
    val original = asset()
    CoreFairwayRuPolicy.prepare(original)
    val lines = original.toString(Charsets.US_ASCII).trimEnd('\n').split('\n')
    val malformed = listOf(
      ByteArray(0), ByteArray(200_001),
      original.copyOf().also { it[0] = 0xff.toByte() },
      original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
      lines.take(512).joinToString("\n").toByteArray(),
      (lines[0] + "\n" + lines.joinToString("\n")).toByteArray(),
      lines.reversed().joinToString("\n").toByteArray(),
      ("2.56.24.1/22\n" + lines.drop(1).joinToString("\n")).toByteArray(),
    )
    for (bytes in malformed) {
      assertThrows(IllegalArgumentException::class.java) { CoreFairwayRuPolicy.prepare(bytes) }
    }
    assertEquals(8662, CoreFairwayRuPolicy.prepare(original).networks.size)
  }

  @Test fun concurrentNativeArrayConsumersCannotPoisonPinnedCache() {
    val original = asset()
    val expected = CoreFairwayRuPolicy.prepare(original)
    val expectedNetworks = expected.networks
    val expectedPrefixes = expected.prefixes
    val pool = Executors.newFixedThreadPool(4)
    try {
      val futures = (0 until 12).map { index -> pool.submit(Callable {
        val policy = CoreFairwayRuPolicy.prepare(original)
        val networks = policy.networks
        val prefixes = policy.prefixes
        assertArrayEquals(expectedNetworks, networks)
        assertArrayEquals(expectedPrefixes, prefixes)
        networks.fill(index)
        prefixes.fill(index)
        assertEquals(45_223_232L, policy.directRuIpv4)
      }) }
      futures.forEach { it.get(10, TimeUnit.SECONDS) }
    } finally { pool.shutdownNow() }
    assertArrayEquals(expectedNetworks, CoreFairwayRuPolicy.prepare(original).networks)
    assertArrayEquals(expectedPrefixes, CoreFairwayRuPolicy.prepare(original).prefixes)
  }
}
