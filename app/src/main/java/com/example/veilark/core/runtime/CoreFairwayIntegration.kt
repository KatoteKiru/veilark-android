package com.example.veilark.core.runtime

/** Source integration only. Enable only with a matched, accepted native artifact. */
internal object CoreFairwayIntegration {
  const val ENABLED = false
  // The pinned policy cannot change inside an attempt. Native fences both IDs.
  const val POLICY_GENERATION = 20261013L
}
