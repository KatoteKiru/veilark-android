package com.example.veilark.core.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import com.example.veilark.profile.ProfileSelection

class CoreL3RoutesTest {
  private fun compile(direct: List<String> = emptyList(), vpn: List<String> = emptyList(), dns: List<String> = emptyList()) =
    CoreL3Routes.compile(direct, vpn, dns)

  @Test fun allIgnoresInactiveManualTextWhileStillCapturingDns() {
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), CoreL3Routes.fromPreferences(
      ProfileSelection.ROUTING_ALL, "geoip:ru", "x".repeat(70_000), listOf("1.1.1.1")))
  }

  @Test fun manualParsesSeparatorsAndGivesVpnAndDnsPrecedence() {
    val routes = CoreL3Routes.fromPreferences(ProfileSelection.ROUTING_MANUAL,
      " 0.0.0.0/0;\n", "10.0.0.0/25, 10.0.0.128/25\t", listOf("1.1.1.1"))
    assertEquals(listOf(CoreIpv4Route("1.1.1.1", 32), CoreIpv4Route("10.0.0.0", 24)), routes)
  }

  @Test fun unknownModesAndUnsupportedManualRulesFailClosed() {
    for (mode in listOf("", "unknown", ProfileSelection.ROUTING_RU_DIRECT)) {
      try { CoreL3Routes.fromPreferences(mode, "", "", listOf("1.1.1.1")); fail("Accepted mode") }
      catch (_: IllegalArgumentException) { }
    }
    for (text in listOf("geoip:ru", "example.com", "::/0", "10.0.0.1/8")) {
      try { CoreL3Routes.fromPreferences(ProfileSelection.ROUTING_MANUAL, text, "", listOf("1.1.1.1")); fail("Accepted rule") }
      catch (_: IllegalArgumentException) { }
    }
  }

  @Test(expected = IllegalArgumentException::class) fun combinedManualTextIsBoundedBeforeTokenization() {
    CoreL3Routes.fromPreferences(ProfileSelection.ROUTING_MANUAL, " ".repeat(40_000), " ".repeat(40_000), listOf("1.1.1.1"))
  }

  @Test fun fullRangeAndUnsignedEndpointsAreHandledWithoutOverflow() {
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), compile())
    val routes = compile(listOf("0.0.0.0/32", "255.255.255.255/32"))
    assertFalse(contains(routes, 0)); assertFalse(contains(routes, 0xffff_ffffL))
    assertTrue(contains(routes, 1)); assertTrue(contains(routes, 0xffff_fffeL))
    assertCanonicalAndDisjoint(routes)
  }

  @Test fun nestedOverlappingAndAdjacentDirectRangesCollapse() {
    assertEquals(compile(listOf("10.0.0.0/8")), compile(listOf(
      "10.0.0.0/9", "10.128.0.0/9", "10.1.0.0/16", "10.0.0.0/8")))
  }

  @Test fun explicitVpnOverridesDirectAndAdjacentOverridesMerge() {
    val routes = compile(listOf("0.0.0.0/0"), listOf("10.0.0.0/25", "10.0.0.128/25"))
    assertEquals(listOf(CoreIpv4Route("10.0.0.0", 24)), routes)
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), compile(listOf("0.0.0.0/0"), listOf("0.0.0.0/0")))
  }

  @Test fun dnsOverridesDirectEvenWhenEverythingElseIsDirect() {
    assertEquals(listOf(CoreIpv4Route("1.1.1.1", 32), CoreIpv4Route("77.88.8.8", 32)),
      compile(listOf("0.0.0.0/0"), dns = listOf("77.88.8.8", "1.1.1.1")))
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 32), CoreIpv4Route("255.255.255.255", 32)),
      compile(listOf("0.0.0.0/0"), listOf("0.0.0.0/32", "255.255.255.255/32")))
  }

  @Test(expected = IllegalArgumentException::class) fun emptyEffectiveVpnIsRejected() {
    compile(listOf("0.0.0.0/0"))
  }

  @Test fun invalidAddressesAndNonCanonicalCidrsAreRejected() {
    for (bad in listOf("example.com/32", "::/0", "geoip:ru", "1.2.3.4", "10.1.0.1/16",
      "0.0.0.0/33", "0.0.0.0/-1", "0.0.0.0/00", "01.2.3.4/32", "256.1.2.3/32", " 0.0.0.0/0", "0.0.0.0/0/1")) {
      try { compile(listOf(bad)); fail("Accepted invalid CIDR: $bad") } catch (_: IllegalArgumentException) { }
      try { compile(vpn = listOf(bad)); fail("Accepted invalid override: $bad") } catch (_: IllegalArgumentException) { }
    }
    for (bad in listOf("dns.example", "::1", "1.1.1.1/32", "1.01.1.1")) {
      try { compile(dns = listOf(bad)); fail("Accepted invalid resolver") } catch (_: IllegalArgumentException) { }
    }
  }

  @Test fun inputAndOutputBoundsFailClosedAndInputsRemainUnchanged() {
    val direct = mutableListOf("10.0.0.0/8")
    val vpn = mutableListOf("10.1.0.0/16")
    val dns = mutableListOf("1.1.1.1")
    compile(direct, vpn, dns)
    assertEquals(listOf("10.0.0.0/8"), direct); assertEquals(listOf("10.1.0.0/16"), vpn)
    assertEquals(listOf("1.1.1.1"), dns)
    try { compile(List(1025) { "1.1.1.1/32" }); fail("Input limit") } catch (_: IllegalArgumentException) { }
    // Sparse single-host exclusions require thousands of complement routes.
    val sparse = (0 until 1024).map { address(it * 1048573L) + "/32" }
    try { compile(sparse); fail("Output limit") } catch (_: IllegalStateException) { }
  }

  @Test fun seededAddressMembershipMatchesDirectMinusVpnAndDnsPolicy() {
    val random = Random(1741)
    repeat(20) {
      val a = random.nextInt(256); val b = random.nextInt(256)
      val start = (a.toLong() shl 24) or (b.toLong() shl 16)
      val direct = listOf("$a.$b.0.0/16")
      val override = start + 256
      val resolver = start + 700
      val routes = compile(direct, listOf(address(override) + "/32"), listOf(address(resolver)))
      val samples = listOf(start, start + 1, start + 65535, override, resolver, 0L, 0xffff_ffffL) +
        List(100) { random.nextInt().toLong() and 0xffff_ffffL }
      samples.forEach { ip ->
        val expected = ip !in start..(start + 65535) || ip == override || ip == resolver
        assertEquals(expected, contains(routes, ip))
      }
      assertCanonicalAndDisjoint(routes)
    }
  }

  private fun contains(routes: List<CoreIpv4Route>, ip: Long): Boolean = routes.any {
    val first = number(it.address); val length = 1L shl (32 - it.prefixLength)
    ip >= first && ip < first + length
  }
  private fun assertCanonicalAndDisjoint(routes: List<CoreIpv4Route>) {
    var previousEnd = -1L
    routes.forEach {
      val first = number(it.address); val length = 1L shl (32 - it.prefixLength)
      assertEquals(0L, first % length)
      assertTrue(first > previousEnd)
      previousEnd = first + length - 1
      assertTrue(previousEnd <= 0xffff_ffffL)
    }
  }
  private fun number(value: String): Long = value.split('.').fold(0L) { out, octet -> (out shl 8) or octet.toLong() }
  private fun address(value: Long): String = (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
}
