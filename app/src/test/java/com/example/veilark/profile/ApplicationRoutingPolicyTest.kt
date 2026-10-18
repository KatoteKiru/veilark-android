package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRoutingPolicyTest {
  @Test
  fun allRoutesEverythingExceptTheVpnProcess() {
    val rules = policy(ProfileSelection.APPS_ALL).forTrustTunnel(VPN_PACKAGE)

    assertTrue(rules.includedPackages.isEmpty())
    assertEquals(setOf(VPN_PACKAGE), rules.excludedPackages)
  }

  @Test
  fun onlyUsesAnAllowListWithoutMixingInTheVpnProcess() {
    val rules = policy(
      ProfileSelection.APPS_ONLY,
      "com.google.android.youtube",
      VPN_PACKAGE,
    ).forTrustTunnel(VPN_PACKAGE)

    assertEquals(setOf("com.google.android.youtube"), rules.includedPackages)
    assertTrue(rules.excludedPackages.isEmpty())
  }

  @Test
  fun bypassExcludesSelectedApplicationsAndTheVpnProcess() {
    val rules = policy(
      ProfileSelection.APPS_BYPASS,
      "org.telegram.messenger",
    ).forTrustTunnel(VPN_PACKAGE)

    assertTrue(rules.includedPackages.isEmpty())
    assertEquals(
      setOf("org.telegram.messenger", VPN_PACKAGE),
      rules.excludedPackages,
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun onlyRejectsAnEmptyEffectiveAllowList() {
    policy(ProfileSelection.APPS_ONLY, VPN_PACKAGE).forTrustTunnel(VPN_PACKAGE)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsUnknownPreferenceValues() {
    policy("unexpected", "org.telegram.messenger")
  }

  @Test fun deletedBypassPackageDoesNotAbortInstalledExclusionsOrMutateSelection() {
    val saved = setOf("com.deleted.app", "org.telegram.messenger")
    val rules = ApplicationRoutingPolicy.fromPreferences(ProfileSelection.APPS_BYPASS, saved)
      .forAndroidVpn(VPN_PACKAGE)
    val applied = mutableSetOf<String>()
    val counts = rules.applyInstalled(VPN_PACKAGE,
      addIncluded = { throw AssertionError("Bypass must not add allowed apps") },
      addExcluded = { app -> if (app == "com.deleted.app") false else { applied += app; true } },
    )
    assertEquals(setOf(VPN_PACKAGE, "org.telegram.messenger"), applied)
    assertEquals(AppliedApplicationRules(0, 2, 1), counts)
    assertEquals(saved + VPN_PACKAGE, rules.excludedPackages)
  }

  @Test fun onlyWithOneDeletedPackageStillRestrictsToSurvivingSelectedApp() {
    val rules = policy(ProfileSelection.APPS_ONLY, "com.deleted.app", "org.telegram.messenger")
      .forAndroidVpn(VPN_PACKAGE)
    val allowed = mutableSetOf<String>()
    val counts = rules.applyInstalled(VPN_PACKAGE,
      addIncluded = { app -> if (app == "com.deleted.app") false else { allowed += app; true } },
      addExcluded = { throw AssertionError("Only must not add excluded apps") },
    )
    assertEquals(setOf("org.telegram.messenger"), allowed)
    assertEquals(AppliedApplicationRules(1, 0, 1), counts)
  }

  @Test(expected = ApplicationRoutingUnavailableException::class)
  fun allDeletedOnlySelectionsFailInsteadOfDefaultingToAll() {
    policy(ProfileSelection.APPS_ONLY, "com.deleted.app").forAndroidVpn(VPN_PACKAGE)
      .applyInstalled(VPN_PACKAGE, { false }, { throw AssertionError("Must not change to exclusions") })
  }

  @Test fun allIgnoresSavedDeletedSelectionsAndExcludesOwnVpn() {
    val rules = policy(ProfileSelection.APPS_ALL, "com.deleted.app").forAndroidVpn(VPN_PACKAGE)
    val touched = mutableSetOf<String>()
    assertEquals(AppliedApplicationRules(0, 1, 0), rules.applyInstalled(VPN_PACKAGE,
      { throw AssertionError("All must not create an allow list") }, { touched += it; true }))
    assertEquals(setOf(VPN_PACKAGE), touched)
  }

  @Test(expected = IllegalStateException::class)
  fun ownVpnExclusionFailureIsNeverSilentlySkipped() {
    policy(ProfileSelection.APPS_ALL).forAndroidVpn(VPN_PACKAGE)
      .applyInstalled(VPN_PACKAGE, { true }, { false })
  }

  @Test fun unrelatedPackageApiFailurePropagatesInsteadOfWeakeningPolicy() {
    val failure = SecurityException("test")
    try {
      policy(ProfileSelection.APPS_ONLY, "org.telegram.messenger").forAndroidVpn(VPN_PACKAGE)
        .applyInstalled(VPN_PACKAGE, { throw failure }, { true })
      throw AssertionError("Expected API failure")
    } catch (caught: SecurityException) {
      assertTrue(caught === failure)
    }
  }

  private fun policy(mode: String, vararg packages: String) =
    ApplicationRoutingPolicy.fromPreferences(mode, packages.toSet())

  private companion object {
    const val VPN_PACKAGE = "app.veilark.test"
  }
}
