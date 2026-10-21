package com.example.veilark.core.runtime

import com.example.veilark.profile.ApplicationRoutingUnavailableException
import com.example.veilark.profile.CoreProfileKind
import com.example.veilark.profile.ProfileSelection
import org.junit.Assert.*
import org.junit.Test

class CoreConnectionPreflightTest {
  @Test fun fullArgonPreflightUsesSameCompletePolicyAsService() {
    val reads = mutableListOf<String>()
    val plan = CoreConnectionPreflight.prepareIntegrated(CoreProfileKind.ARGON_V1,
      listOf("77.88.8.8"), mapOf("core_routing_mode" to ProfileSelection.ROUTING_RU_DIRECT),
      35, "app.veilark.test", { true }, { true }, true) { path ->
        reads += path
        java.io.File("src/main/assets", path).readBytes()
      }
    assertEquals(listOf(CoreFairwayRuPolicy.ASSET), reads)
    assertFalse(plan.partialRu)
    assertEquals(8662, plan.fairwayPolicy!!.networks.size)
    assertEquals(45_223_232L, plan.directRuIpv4)
    assertEquals(listOf(CoreIpv4Route("0.0.0.0", 0)), plan.included)
  }

  @Test fun unsupportedFullPolicyRejectsBeforeAssetsOrApplicationAdmission() {
    assertThrows(IllegalArgumentException::class.java) {
      CoreConnectionPreflight.prepareIntegrated(CoreProfileKind.ARGON_V1,
        listOf("1.1.1.1"), mapOf("core_routing_mode" to ProfileSelection.ROUTING_RU_DIRECT),
        30, "app.veilark.test", { true }, { error("Unexpected package admission") }, true) {
        error("Unexpected asset read")
      }
    }
  }

  @Test fun fullPolicyFlagNeverChangesLegacyEngineAdmission() {
    val plan = CoreConnectionPreflight.prepareIntegrated(CoreProfileKind.LEGACY_V1,
      listOf("1.1.1.1"), emptyMap<String, Any>(), 30, "app.veilark.test",
      { true }, { true }, true) { error("Unexpected GEO read") }
    assertNull(plan.fairwayPolicy)
    assertEquals(0, plan.nativeMode)
  }

  private fun prepare(
    kind: CoreProfileKind = CoreProfileKind.ARGON_V1,
    prefs: Map<String, *> = emptyMap<String, Any>(),
    probe: (CoreProfileKind) -> Boolean = { true },
    installed: (String) -> Boolean = { true },
  ) = CoreConnectionPreflight.prepare(kind, listOf("1.1.1.1"), prefs, 33,
    "app.veilark.test", probe, installed) { error("Unexpected GEO read") }

  @Test fun eachKindChecksItsOwnRuntimeWithoutLegacyFallback() {
    for (kind in CoreProfileKind.entries) {
      val probes = mutableListOf<CoreProfileKind>()
      val plan = prepare(kind, probe = { probes += it; true })
      assertEquals(listOf(kind), probes)
      assertEquals(0, plan.nativeMode)
    }
  }

  @Test fun missingRuntimeRejectsBeforeApplicationOrRouteSideEffects() {
    for (kind in CoreProfileKind.entries) {
      assertThrows(IllegalStateException::class.java) {
        prepare(kind, probe = { false }, installed = { error("Unexpected package query") })
      }
    }
  }

  @Test fun malformedSavedPolicyRejectsBeforeApplicationChecks() {
    for (prefs in listOf(
      mapOf("core_routing_mode" to 1),
      mapOf("core_routing_mode" to ProfileSelection.ROUTING_MANUAL, "core_direct_routes" to "example.com"),
    )) assertThrows(IllegalArgumentException::class.java) {
      prepare(prefs = prefs, installed = { error("Unexpected package query") })
    }
  }

  @Test fun onlyModeWithRemovedApplicationsNeverBecomesAll() {
    assertThrows(ApplicationRoutingUnavailableException::class.java) {
      prepare(prefs = mapOf("application_mode" to ProfileSelection.APPS_ONLY,
        "selected_applications" to setOf("com.example.removed")), installed = { false })
    }
  }

  @Test fun corruptSelectionAndPackageQueryFailureAreNotIgnored() {
    assertThrows(IllegalArgumentException::class.java) {
      prepare(prefs = mapOf("selected_applications" to setOf(123)))
    }
    assertThrows(SecurityException::class.java) {
      prepare(installed = { throw SecurityException("Package manager unavailable") })
    }
  }
}
