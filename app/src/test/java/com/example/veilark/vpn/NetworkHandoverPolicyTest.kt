package com.example.veilark.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoverPolicyTest {
  private data class Candidate(
    val name: String,
    val validated: Boolean,
    val priority: Int,
  )

  @Test
  fun wifiLossSelectsValidatedLteAndNeverReselectsLostWifi() {
    val wifi = Candidate("wifi", validated = true, NetworkHandoverPolicy.PRIORITY_WIFI)
    val lte = Candidate("lte", validated = true, NetworkHandoverPolicy.PRIORITY_CELLULAR)

    val replacement = NetworkHandoverPolicy.selectReplacement(
      active = null,
      candidates = listOf(wifi, lte),
      excluding = wifi,
      isUsable = Candidate::validated,
      priority = Candidate::priority,
    )

    assertEquals(lte, replacement)
  }

  @Test
  fun wifiLossWaitsUntilLteIsValidated() {
    val wifi = Candidate("wifi", validated = true, NetworkHandoverPolicy.PRIORITY_WIFI)
    val lte = Candidate("lte", validated = false, NetworkHandoverPolicy.PRIORITY_CELLULAR)

    assertNull(
      NetworkHandoverPolicy.selectReplacement(
        active = null,
        candidates = listOf(wifi, lte),
        excluding = wifi,
        isUsable = Candidate::validated,
        priority = Candidate::priority,
      ),
    )
    assertFalse(
      NetworkHandoverPolicy.shouldAdopt(
        currentUsable = false,
        currentPriority = NetworkHandoverPolicy.PRIORITY_OTHER,
        candidateValidated = false,
        candidatePriority = NetworkHandoverPolicy.PRIORITY_CELLULAR,
        candidateIsActive = true,
      ),
    )
  }

  @Test
  fun validatedLteIsAdoptedAfterWifiDisappears() {
    assertTrue(
      NetworkHandoverPolicy.shouldAdopt(
        currentUsable = false,
        currentPriority = NetworkHandoverPolicy.PRIORITY_WIFI,
        candidateValidated = true,
        candidatePriority = NetworkHandoverPolicy.PRIORITY_CELLULAR,
        candidateIsActive = false,
      ),
    )
  }

  @Test
  fun validatedWifiReplacesLteEvenWhenVpnIsAndroidActiveNetwork() {
    assertTrue(
      NetworkHandoverPolicy.shouldAdopt(
        currentUsable = true,
        currentPriority = NetworkHandoverPolicy.PRIORITY_CELLULAR,
        candidateValidated = true,
        candidatePriority = NetworkHandoverPolicy.PRIORITY_WIFI,
        candidateIsActive = false,
      ),
    )
  }

  @Test
  fun duplicateOrLowerPriorityCallbackDoesNotResetHealthyWifi() {
    assertFalse(
      NetworkHandoverPolicy.shouldAdopt(
        currentUsable = true,
        currentPriority = NetworkHandoverPolicy.PRIORITY_WIFI,
        candidateValidated = true,
        candidatePriority = NetworkHandoverPolicy.PRIORITY_CELLULAR,
        candidateIsActive = false,
      ),
    )
  }

  @Test
  fun reverseHandoverPrefersValidatedWifiOverLte() {
    val lte = Candidate("lte", validated = true, NetworkHandoverPolicy.PRIORITY_CELLULAR)
    val wifi = Candidate("wifi", validated = true, NetworkHandoverPolicy.PRIORITY_WIFI)

    val replacement = NetworkHandoverPolicy.selectReplacement(
      active = null,
      candidates = listOf(lte, wifi),
      excluding = null,
      isUsable = Candidate::validated,
      priority = Candidate::priority,
    )

    assertEquals(wifi, replacement)
  }
}
