package com.example.veilark.core.runtime

import com.example.veilark.profile.ProfileSelection
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CoreRoutingPlanTest {
  @Test fun ordinaryModesNeverLoadGeoAndKeepDnsInVpn() {
    val unusedAsset: (String) -> ByteArray = { error("Unexpected GEO read") }
    val all = CoreRoutingPlan.prepare(ProfileSelection.ROUTING_ALL, "geoip:ru", "",
      listOf("1.1.1.1"), 29, unusedAsset)
    assertEquals(0, all.nativeMode)
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), all.included)
    val manual = CoreRoutingPlan.prepare(ProfileSelection.ROUTING_MANUAL, "1.1.1.0/24", "",
      listOf("1.1.1.1"), 33, unusedAsset)
    assertEquals(2, manual.nativeMode)
    assertTrue(manual.excluded.isEmpty())
    assertTrue(manual.included.contains(CoreIpv4Route("1.1.1.1", 32)))
  }

  @Test fun ruPlanLoadsEachPinnedAssetOnceAndIgnoresInactiveManualRules() {
    for (api in listOf(29, 33, 37)) {
      val reads = mutableListOf<String>()
      val plan = CoreRoutingPlan.prepare(ProfileSelection.ROUTING_RU_DIRECT, "invalid", "invalid",
        listOf("1.1.1.1"), api) { path ->
        reads += path
        listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
          .first { it.isFile }.readBytes()
      }
      assertEquals(listOf(FairwayRuRoutePlan.EXACT_ASSET, FairwayRuRoutePlan.LEGACY_ASSET), reads)
      assertEquals(1, plan.nativeMode)
      assertTrue(plan.partialRu)
      assertTrue(plan.directRuIpv4 > 0)
      assertEquals(api >= 33, plan.excluded.isNotEmpty())
    }
  }

  @Test fun commonRuCapturesAllIpv4AndLoadsOnlyCompleteNativePolicy() {
    for (api in listOf(29, 33, 37)) {
      val reads = mutableListOf<String>()
      val plan = CoreRoutingPlan.prepareIntegrated(ProfileSelection.ROUTING_RU_DIRECT, "invalid", "invalid",
        listOf("1.1.1.1"), api, true) { path ->
        reads += path
        listOf(File("src/main/assets/$path"), File("app/src/main/assets/$path"))
          .first { it.isFile }.readBytes()
      }
      assertEquals(listOf(CoreFairwayRuPolicy.ASSET), reads)
      assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), plan.included)
      assertTrue(plan.excluded.isEmpty())
      assertFalse(plan.partialRu)
      assertEquals(45_223_232L, plan.directRuIpv4)
      assertEquals(8662, checkNotNull(plan.fairwayPolicy).networks.size)
      assertEquals(1, plan.nativeMode)
    }
  }

  @Test fun corruptGeoAndUnknownModeFailBeforeReturningPlan() {
    assertThrows(IllegalArgumentException::class.java) {
      CoreRoutingPlan.prepare(ProfileSelection.ROUTING_RU_DIRECT, "", "", listOf("1.1.1.1"), 33) { byteArrayOf() }
    }
    assertThrows(IllegalArgumentException::class.java) {
      CoreRoutingPlan.prepare("unknown", "", "", listOf("1.1.1.1"), 33) { error("Unexpected GEO read") }
    }
  }
}
