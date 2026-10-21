package com.example.veilark.core.runtime

import com.example.veilark.BuildConfig

/** Product acceptance fixture only; regular debug/release builds stay disabled. */
internal object CoreFairwayIntegration {
  val ENABLED = BuildConfig.DEBUG && BuildConfig.FAIRWAY_LAB_ENABLED
  // The pinned policy cannot change inside an attempt. Native fences both IDs.
  const val POLICY_GENERATION = 20261013L
}

/** Native bridge is admitted only by the separate debuggable acceptance package. */
internal fun coreRuntimeAbiSupported(abis: List<String>, isolatedLab: Boolean =
  BuildConfig.DEBUG && BuildConfig.FAIRWAY_LAB_ENABLED): Boolean =
  abis.firstOrNull() == "arm64-v8a" || (isolatedLab && "arm64-v8a" in abis)
