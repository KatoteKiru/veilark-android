package com.example.veilark.core.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class FairwayRuRoutePlanTest {
  private val exact = asset(FairwayRuRoutePlan.EXACT_ASSET)
  private val legacy = asset(FairwayRuRoutePlan.LEGACY_ASSET)
  private val exactRanges = parseRanges(exact)
  // Deliberately inside three different selected RU blocks, not public DNS.
  private val dns = parseRanges(legacy).take(3).map { address(it.first + 1) }

  @Test fun api37UsesBoundedPartialPolicyWithoutForeignDirectExpansion() {
    val plan = FairwayRuRoutePlan.prepare(37, exact, legacy, dns)
    assertTrue(plan.partial)
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), plan.included)
    assertEquals(1609, plan.included.size + plan.excluded.size)
    assertCanonicalDisjoint(plan.excluded)
    val direct = ranges(plan.excluded)
    val vpn = gaps(direct)
    assertSubsetOfExact(direct)
    assertEquals(36_345_853L, plan.directRuIpv4)
    assertEquals(8_877_379L, plan.residualRuIpv4)
    dns.forEach { assertTrue(contains(vpn, ip(it))) }
    assertExactSubsetPolicyAtEveryBoundary(vpn)
  }

  @Test fun everySupportedApiUsesSameBoundedSubsetIncludingThreeDnsOverrides() {
    for (api in listOf(29, 32, 33, 37)) {
      val plan = FairwayRuRoutePlan.prepare(api, exact, legacy, dns)
      assertTrue(plan.partial)
      val direct: List<LongRange>
      val vpn: List<LongRange>
      if (api >= 33) {
        assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), plan.included)
        assertEquals(1609, plan.included.size + plan.excluded.size)
        assertCanonicalDisjoint(plan.excluded)
        direct = ranges(plan.excluded)
        vpn = gaps(direct)
      } else {
        assertTrue(plan.excluded.isEmpty())
        assertEquals(4096, plan.included.size)
        assertCanonicalDisjoint(plan.included)
        vpn = ranges(plan.included)
        direct = gaps(vpn)
      }
      assertSubsetOfExact(direct)
      assertEquals(36_345_853L, size(direct))
      assertEquals(size(direct), plan.directRuIpv4)
      assertEquals(8_877_379L, plan.residualRuIpv4)
      assertEquals(1L shl 32, size(vpn) + size(direct))
      dns.forEach { assertTrue(contains(vpn, ip(it))) }
    }
  }

  @Test fun assetTamperingAndInvalidResolverFailBeforeProducingPlan() {
    val tampered = exact.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertThrows(IllegalArgumentException::class.java) { FairwayRuRoutePlan.prepare(33, tampered, legacy, dns) }
    assertThrows(IllegalArgumentException::class.java) { FairwayRuRoutePlan.prepare(33, exact, legacy, listOf("::1")) }
    assertThrows(IllegalArgumentException::class.java) { FairwayRuRoutePlan.prepare(33, exact, legacy, emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { FairwayRuRoutePlan.prepare(28, exact, legacy, dns) }
  }

  @Test fun outputIsImmutableAndInputMutationDoesNotChangePreparedPlan() {
    val input = dns.toMutableList()
    val plan = FairwayRuRoutePlan.prepare(33, exact, legacy, input)
    val snapshot = plan.excluded.toList()
    input.clear()
    assertEquals(snapshot, plan.excluded)
    assertThrows(UnsupportedOperationException::class.java) { (plan.excluded as MutableList<CoreIpv4Route>).clear() }
    assertThrows(UnsupportedOperationException::class.java) { (plan.included as MutableList<CoreIpv4Route>).clear() }
  }

  private fun asset(path: String): ByteArray {
    val candidates = listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
    return candidates.first { it.isFile }.readBytes()
  }

  private fun ip(value: String): Long = value.split('.').fold(0L) { n, p -> (n shl 8) or p.toLong() }
  private fun address(value: Long): String = (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
  private fun ranges(routes: List<CoreIpv4Route>): List<LongRange> = routes.map {
    val first = ip(it.address)
    first until first + (1L shl (32 - it.prefixLength))
  }
  private fun parseRanges(bytes: ByteArray): List<LongRange> = ranges(bytes.toString(Charsets.US_ASCII)
    .lineSequence().filter(String::isNotEmpty).map { line ->
      val parts = line.split('/')
      CoreIpv4Route(parts[0], parts[1].toInt())
    }.toList())
  private fun size(ranges: List<LongRange>): Long = ranges.sumOf { it.last - it.first + 1 }
  private fun contains(ranges: List<LongRange>, address: Long): Boolean = ranges.any { address in it }
  private fun assertCanonicalDisjoint(routes: List<CoreIpv4Route>) {
    var previous = -1L
    for (route in routes) {
      val first = ip(route.address)
      assertEquals(0L, first % (1L shl (32 - route.prefixLength)))
      assertTrue(first > previous)
      previous = first + (1L shl (32 - route.prefixLength)) - 1
    }
  }
  private fun assertSubsetOfExact(direct: List<LongRange>) {
    var index = 0
    for (block in direct) {
      while (index < exactRanges.size && exactRanges[index].last < block.first) index++
      var cursor = block.first
      while (cursor <= block.last) {
        assertTrue(index < exactRanges.size)
        val exact = exactRanges[index]
        assertTrue("Non-RU gap at $cursor", cursor >= exact.first && cursor <= exact.last)
        cursor = minOf(block.last, exact.last) + 1
        if (cursor <= block.last) index++
      }
    }
  }
  // Every input/output CIDR and DNS boundary partitions IPv4 into intervals
  // where both policies are constant. Checking both endpoints is exhaustive,
  // without enumerating the four-billion-address space.
  private fun assertExactSubsetPolicyAtEveryBoundary(vpn: List<LongRange>) {
    val selected = parseRanges(legacy)
    val dnsHosts = dns.map(::ip).toSet()
    val boundaries = sortedSetOf(0L, 1L shl 32)
    (selected + vpn).forEach { boundaries.add(it.first); boundaries.add(it.last + 1) }
    dnsHosts.forEach { boundaries.add(it); boundaries.add(it + 1) }
    var selectedIndex = 0
    var vpnIndex = 0
    for ((first, limit) in boundaries.zipWithNext()) {
      for (host in setOf(first, limit - 1)) {
        while (selectedIndex < selected.size && selected[selectedIndex].last < host) selectedIndex++
        while (vpnIndex < vpn.size && vpn[vpnIndex].last < host) vpnIndex++
        val expectedDirect = selectedIndex < selected.size && host in selected[selectedIndex] && host !in dnsHosts
        val actualVpn = vpnIndex < vpn.size && host in vpn[vpnIndex]
        assertEquals("Routing mismatch at $host", !expectedDirect, actualVpn)
      }
    }
  }

  private fun gaps(vpn: List<LongRange>): List<LongRange> = buildList {
    var cursor = 0L
    for (range in vpn) {
      if (cursor < range.first) add(cursor until range.first)
      cursor = range.last + 1
    }
    if (cursor <= 0xffff_ffffL) add(cursor..0xffff_ffffL)
  }
}
