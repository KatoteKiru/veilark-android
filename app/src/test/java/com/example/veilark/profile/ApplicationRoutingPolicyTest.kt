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

  private fun policy(mode: String, vararg packages: String) =
    ApplicationRoutingPolicy.fromPreferences(mode, packages.toSet())

  private companion object {
    const val VPN_PACKAGE = "app.veilark.test"
  }
}
