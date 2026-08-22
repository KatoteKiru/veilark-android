package com.example.veilark.vpn

internal object NetworkHandoverPolicy {
  const val PRIORITY_OTHER = 0
  const val PRIORITY_CELLULAR = 1
  const val PRIORITY_WIFI = 2
  const val PRIORITY_ETHERNET = 3

  fun shouldAdopt(
    currentUsable: Boolean,
    currentPriority: Int,
    candidateValidated: Boolean,
    candidatePriority: Int,
    candidateIsActive: Boolean,
  ): Boolean =
    candidateValidated && (
      !currentUsable ||
        candidateIsActive ||
        candidatePriority > currentPriority
      )

  fun <T> selectReplacement(
    active: T?,
    candidates: List<T>,
    excluding: T?,
    isUsable: (T) -> Boolean,
    priority: (T) -> Int,
  ): T? {
    active
      ?.takeIf { it != excluding && isUsable(it) }
      ?.let { return it }
    return candidates
      .asSequence()
      .filter { it != excluding && isUsable(it) }
      .maxByOrNull(priority)
  }
}
