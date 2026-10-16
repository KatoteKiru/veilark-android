package com.example.veilark.core.runtime

import java.security.MessageDigest
import java.util.Collections

/** Prepared routing only: no Builder changes, downloads, or automatic activation. */
internal class FairwayRuRoutePlan private constructor(
  val included: List<CoreIpv4Route>,
  val excluded: List<CoreIpv4Route>,
  val directRuIpv4: Long,
  val residualRuIpv4: Long,
  val partial: Boolean,
) {
  val directCoveragePercent: Double get() = 100.0 * directRuIpv4 / TOTAL_RU_IPV4

  companion object {
    const val EXACT_ASSET = "fairway/ru-ipv4-20261013.txt"
    const val LEGACY_ASSET = "fairway/ru-ipv4-legacy-20261013.txt"
    private const val EXACT_SHA = "fd0fdb390680c4a6a18a02542205b766e345b6caa34de717a2d9c776dd063cd5"
    private const val LEGACY_SHA = "81d0875333d06aa342a73831d3030b685097d25890fb73ca709447cf7b536055"
    private const val TOTAL_RU_IPV4 = 45_223_232L
    private const val MAX_LEGACY_ROUTES = 4096
    private const val LAST = 0xffff_ffffL
    private data class Range(val first: Long, val last: Long)

    /**
     * Both assets are pinned independently of mutable GEO generations. DNS stays
     * in the VPN by removing its addresses from the direct set before routing.
     * Legacy API support is deliberately partial: residual RU remains in VPN.
     */
    fun prepare(api: Int, exactBytes: ByteArray, legacyBytes: ByteArray, dns: Collection<String>): FairwayRuRoutePlan {
      require(api >= 29) { "Fairway requires Android API 29 or later" }
      require(dns.isNotEmpty() && dns.size <= 3) { "Fairway requires one to three IPv4 DNS servers" }
      val exact = parseAsset(exactBytes, EXACT_SHA, 8662)
      val legacy = parseAsset(legacyBytes, LEGACY_SHA, 1562)
      require(legacy.all(exact.toHashSet()::contains)) { "Fairway legacy routes exceed the exact RU set" }
      val selected = if (api >= 33) exact else legacy
      var direct = selected.map(::range)
      for (host in dns.map(::ipv4).distinct()) {
        direct = direct.flatMap { block ->
          if (host !in block.first..block.last) listOf(block)
          else buildList {
            if (block.first < host) add(Range(block.first, host - 1))
            if (host < block.last) add(Range(host + 1, block.last))
          }
        }
      }
      val covered = direct.sumOf { it.last - it.first + 1 }
      val exclusions = if (api >= 33) cidrs(direct) else emptyList()
      val includes = if (api >= 33) listOf(CoreIpv4Route("0.0.0.0", 0)) else cidrs(complement(direct))
      require(api >= 33 || includes.size <= MAX_LEGACY_ROUTES) { "Fairway legacy route budget exceeded" }
      return FairwayRuRoutePlan(
        immutable(includes), immutable(exclusions), covered, TOTAL_RU_IPV4 - covered, api < 33,
      )
    }

    private fun immutable(routes: List<CoreIpv4Route>): List<CoreIpv4Route> =
      Collections.unmodifiableList(ArrayList(routes))

    private fun parseAsset(bytes: ByteArray, expectedSha: String, count: Int): List<CoreIpv4Route> {
      require(bytes.size <= 200_000 && bytes.all { it.toInt() in 0..127 }) { "Invalid Fairway asset size or encoding" }
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
      require(digest == expectedSha) { "Fairway asset digest mismatch" }
      val lines = bytes.toString(Charsets.US_ASCII).split('\n').filter(String::isNotEmpty)
      require(lines.size == count) { "Fairway asset count mismatch" }
      val parsed = lines.map { line ->
        val parts = line.split('/')
        require(parts.size == 2 && parts[1].matches(Regex("0|[1-9][0-9]?"))) { "Invalid Fairway prefix" }
        val prefix = parts[1].toInt()
        require(prefix in 0..32) { "Invalid Fairway prefix" }
        val first = ipv4(parts[0])
        require(first % (1L shl (32 - prefix)) == 0L) { "Fairway CIDR contains host bits" }
        CoreIpv4Route(parts[0], prefix)
      }
      var previous = -1L
      for (route in parsed) {
        val block = range(route)
        require(block.first > previous) { "Unsorted or overlapping Fairway asset" }
        previous = block.last
      }
      return parsed
    }

    private fun range(route: CoreIpv4Route): Range {
      val first = ipv4(route.address)
      return Range(first, first + (1L shl (32 - route.prefixLength)) - 1)
    }

    private fun complement(direct: List<Range>): List<Range> = buildList {
      var cursor = 0L
      for (block in direct) {
        if (cursor < block.first) add(Range(cursor, block.first - 1))
        cursor = block.last + 1
      }
      if (cursor <= LAST) add(Range(cursor, LAST))
    }

    private fun cidrs(ranges: List<Range>): List<CoreIpv4Route> = buildList {
      for (block in ranges) {
        var cursor = block.first
        while (cursor <= block.last) {
          val alignment = if (cursor == 0L) 32 else java.lang.Long.numberOfTrailingZeros(cursor).coerceAtMost(32)
          val fitting = 63 - java.lang.Long.numberOfLeadingZeros(block.last - cursor + 1)
          val bits = minOf(alignment, fitting)
          add(CoreIpv4Route(address(cursor), 32 - bits))
          cursor += 1L shl bits
        }
      }
    }

    private fun ipv4(text: String): Long {
      val parts = text.split('.')
      require(parts.size == 4) { "Fairway requires literal IPv4 addresses" }
      return parts.fold(0L) { value, part ->
        require(part.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Invalid Fairway IPv4 address" }
        val octet = part.toInt()
        require(octet <= 255) { "Invalid Fairway IPv4 address" }
        (value shl 8) or octet.toLong()
      }
    }

    private fun address(value: Long): String = (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
  }
}
