package com.example.veilark.vpn

/** Bounds user-requested reachability probes so they cannot become background scans. */
internal object ManualLatencyPolicy {
  const val MAX_TARGETS = 48
  const val DEADLINE_MS = 15_000L

  fun <T> boundedTargets(values: List<T>): List<T> = values.take(MAX_TARGETS)
}
