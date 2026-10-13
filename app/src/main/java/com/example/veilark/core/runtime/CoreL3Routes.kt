package com.example.veilark.core.runtime

import com.example.veilark.profile.ProfileSelection

/** Android Builder.addRoute inputs, generated solely from validated IPv4 policy. */
internal data class CoreIpv4Route(val address: String, val prefixLength: Int) {
  override fun toString(): String = "$address/$prefixLength"
}

/** Pure L3 policy: full IPv4 minus direct ranges, with explicit VPN/DNS precedence. */
internal object CoreL3Routes {
  const val MAX_INPUT = 1024
  const val MAX_OUTPUT = 4096
  private const val LAST = 0xffff_ffffL
  private data class Interval(val first: Long, val last: Long)

  /** Core owns only All/manual IPv4 policy; inactive text is ignored in All. */
  fun fromPreferences(mode: String, directText: String, vpnText: String, dns: Collection<String>): List<CoreIpv4Route> =
    when (mode) {
      ProfileSelection.ROUTING_ALL -> compile(emptyList(), emptyList(), dns)
      ProfileSelection.ROUTING_MANUAL -> {
        require(directText.length.toLong() + vpnText.length <= 64 * 1024) { "Core routing text is too large" }
        require(directText.toByteArray(Charsets.UTF_8).size.toLong() + vpnText.toByteArray(Charsets.UTF_8).size <= 64 * 1024) {
          "Core routing text is too large"
        }
        fun tokens(value: String): List<String> = value.split(Regex("[\\s,;]+"))
          .filter(String::isNotEmpty)
        compile(tokens(directText), tokens(vpnText), dns)
      }
      else -> throw IllegalArgumentException("Unsupported Core IPv4 routing mode")
    }

  fun compile(
    directCidrs: Collection<String>,
    vpnCidrs: Collection<String>,
    dnsIpv4: Collection<String>,
  ): List<CoreIpv4Route> {
    require(directCidrs.size.toLong() + vpnCidrs.size + dnsIpv4.size <= MAX_INPUT) {
      "Too many Core IPv4 routing inputs"
    }
    val direct = merge(directCidrs.map(::cidr))
    val overrides = vpnCidrs.map(::cidr) + dnsIpv4.map { address ->
      val ip = ipv4(address)
      Interval(ip, ip)
    }
    val captured = mutableListOf<Interval>()
    var cursor = 0L
    for (range in direct) {
      if (cursor < range.first) captured += Interval(cursor, range.first - 1)
      cursor = range.last + 1
    }
    if (cursor <= LAST) captured += Interval(cursor, LAST)
    val effective = merge(captured + overrides)
    require(effective.isNotEmpty()) { "Core IPv4 routing captures no traffic" }
    val output = mutableListOf<CoreIpv4Route>()
    for (range in effective) {
      cursor = range.first
      while (cursor <= range.last) {
        val alignment = if (cursor == 0L) 32 else java.lang.Long.numberOfTrailingZeros(cursor).coerceAtMost(32)
        val remaining = range.last - cursor + 1
        val fitting = 63 - java.lang.Long.numberOfLeadingZeros(remaining)
        val bits = minOf(alignment, fitting)
        check(output.size < MAX_OUTPUT) { "Core IPv4 routing exceeds the route limit" }
        output += CoreIpv4Route(address(cursor), 32 - bits)
        cursor += 1L shl bits
      }
    }
    return output.toList()
  }

  private fun cidr(value: String): Interval {
    val parts = value.split('/')
    require(parts.size == 2 && parts[1].matches(Regex("0|[1-9][0-9]?"))) {
      "Core routing requires canonical IPv4 CIDRs"
    }
    val prefix = parts[1].toInt()
    require(prefix in 0..32) { "Invalid Core IPv4 prefix" }
    val first = ipv4(parts[0])
    val block = 1L shl (32 - prefix)
    require(first % block == 0L) { "Core IPv4 CIDR contains host bits" }
    return Interval(first, first + block - 1)
  }

  private fun ipv4(value: String): Long {
    val octets = value.split('.')
    require(octets.size == 4) { "Core routing requires a literal IPv4 address" }
    return octets.fold(0L) { accumulated, octet ->
      require(octet.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Invalid Core IPv4 address" }
      val number = octet.toInt()
      require(number <= 255) { "Invalid Core IPv4 address" }
      (accumulated shl 8) or number.toLong()
    }
  }

  private fun address(value: Long): String = (3 downTo 0)
    .joinToString(".") { shift -> ((value ushr (shift * 8)) and 255).toString() }

  private fun merge(input: List<Interval>): List<Interval> {
    val result = mutableListOf<Interval>()
    for (range in input.sortedWith(compareBy<Interval> { it.first }.thenBy { it.last })) {
      val previous = result.lastOrNull()
      if (previous != null && range.first <= previous.last + 1) {
        result[result.lastIndex] = Interval(previous.first, maxOf(previous.last, range.last))
      } else result += range
    }
    return result
  }
}
