package com.example.veilark.canary

import com.example.veilark.BuildConfig

/** Inert build marker for the explicitly enabled Veilark Core canary seam. */
object VeilarkCoreCanaryContract {
  const val SEAM_ID = "veilark-core-canary-inert-v1"

  /** Reports the variant-scoped build flag without loading or starting a core. */
  fun isEnabled(): Boolean = BuildConfig.VEILARK_CORE_ENABLED
}
