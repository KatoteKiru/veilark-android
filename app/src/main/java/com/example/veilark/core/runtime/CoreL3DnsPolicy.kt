package com.example.veilark.core.runtime

/** Runtime-only migration of the retired lab resolver; stored credentials stay unchanged. */
internal object CoreL3DnsPolicy {
  fun effective(configured: List<String>): EffectiveCoreDns {
    require(configured.isNotEmpty()) { "Core DNS configuration is empty" }
    val retained = configured.filterNot { it == "10.0.2.3" }
    return EffectiveCoreDns(
      resolvers = retained.ifEmpty { listOf("1.1.1.1", "77.88.8.8") },
      removed = configured.size - retained.size,
    )
  }
}

internal data class EffectiveCoreDns(val resolvers: List<String>, val removed: Int)
