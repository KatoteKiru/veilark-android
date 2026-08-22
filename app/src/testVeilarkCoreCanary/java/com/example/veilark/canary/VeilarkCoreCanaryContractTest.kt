package com.example.veilark.canary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VeilarkCoreCanaryContractTest {
  @Test
  fun enabled_variant_exposes_only_the_inert_canary_contract() {
    assertTrue(VeilarkCoreCanaryContract.isEnabled())
    assertEquals("veilark-core-canary-inert-v1", VeilarkCoreCanaryContract.SEAM_ID)
  }
}
