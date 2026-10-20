package com.example.veilark.core.runtime

import java.security.MessageDigest

/** Complete pinned RU destination-IP policy for native Fairway, never Android routes.
 * No prefix truncation, DNS exception, foreign supernet expansion or fallback asset.
 */
internal class CoreFairwayRuPolicy private constructor(
  networks: IntArray,
  prefixes: IntArray,
  val directRuIpv4: Long,
) {
  private val storedNetworks = networks.copyOf()
  private val storedPrefixes = prefixes.copyOf()
  val networks: IntArray get() = storedNetworks.copyOf()
  val prefixes: IntArray get() = storedPrefixes.copyOf()

  companion object {
    const val ASSET = "fairway/ru-ipv4-20261013.txt"
    private const val SHA256 = "fd0fdb390680c4a6a18a02542205b766e345b6caa34de717a2d9c776dd063cd5"
    private const val COUNT = 8662
    private const val TOTAL = 45_223_232L
    private val cacheLock = Any()
    @Volatile private var cached: CoreFairwayRuPolicy? = null

    fun prepare(bytes: ByteArray): CoreFairwayRuPolicy {
      require(bytes.size in 1..200_000) { "Invalid Fairway RU asset size" }
      // Hash and parse the same detached bytes, including on a warm cache lookup.
      val snapshot = bytes.copyOf()
      require(snapshot.all { it.toInt() in 0..127 }) { "Invalid Fairway RU asset encoding" }
      val sha = MessageDigest.getInstance("SHA-256").digest(snapshot)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
      require(sha == SHA256) { "Fairway RU asset digest mismatch" }
      cached?.let { return it }
      return synchronized(cacheLock) {
        cached ?: parse(snapshot).also { cached = it }
      }
    }

    private fun parse(bytes: ByteArray): CoreFairwayRuPolicy {
      val lines = bytes.toString(Charsets.US_ASCII).removeSuffix("\n").split('\n')
      require(lines.size == COUNT) { "Fairway RU prefix count mismatch" }
      val networks = IntArray(COUNT)
      val prefixes = IntArray(COUNT)
      var previousLast = -1L
      var total = 0L
      lines.forEachIndexed { index, line ->
        require(line.length in 3..18) { "Invalid Fairway RU CIDR" }
        val cidr = line.split('/')
        require(cidr.size == 2) { "Invalid Fairway RU CIDR" }
        val prefix = cidr[1].toIntOrNull()
        require(prefix != null && prefix in 0..32 && prefix.toString() == cidr[1]) {
          "Invalid Fairway RU prefix"
        }
        val octets = cidr[0].split('.')
        require(octets.size == 4) { "Invalid Fairway RU IPv4" }
        val first = octets.fold(0L) { address, octet ->
          val number = octet.toIntOrNull()
          require(number != null && number in 0..255 && number.toString() == octet) {
            "Invalid Fairway RU IPv4 octet"
          }
          (address shl 8) or number.toLong()
        }
        val size = 1L shl (32 - prefix)
        require(first % size == 0L) { "Fairway RU CIDR has host bits" }
        val last = first + size - 1
        require(first > previousLast && last <= 0xffff_ffffL) {
          "Fairway RU CIDRs must be sorted and disjoint"
        }
        networks[index] = first.toInt()
        prefixes[index] = prefix
        total += size
        previousLast = last
      }
      require(total == TOTAL) { "Fairway RU address total mismatch" }
      return CoreFairwayRuPolicy(networks, prefixes, total)
    }
  }
}
