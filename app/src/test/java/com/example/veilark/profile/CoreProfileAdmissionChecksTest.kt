package com.example.veilark.profile

import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreProfileAdmissionChecksTest {
  private val fixture = javaClass.getResourceAsStream("/core-profile-v2-vectors.json")!!.use {
    JsonParser.parseString(it.reader(Charsets.UTF_8).readText()).asJsonObject
  }
  private val trust = CoreSignedEnvelope.IssuerSnapshot(7, listOf(CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key1",
    Base64.getDecoder().decode(fixture.getAsJsonObject("fixture_trust").get("public_key_x509_base64").asString))))
  private fun profile(index: Int = 0): CoreCarrierProfile {
    val envelope = fixture.getAsJsonArray("cases")[index].asJsonObject.get("envelope_utf8").asString.toByteArray()
    return CoreSignedEnvelope.checkSignature(envelope, trust).use { CoreCarrierProfile.parse(it) }
  }
  private val identity = CoreProfileAdmissionChecks.Identity("fixture", "a".repeat(32))
  private fun state(floors: Map<CoreProfileAdmissionChecks.Identity, CoreProfileAdmissionChecks.Floor> = emptyMap(),
    lower: Long = 2200000000) = CoreProfileAdmissionChecks.LoadedState(lower, floors)
  private fun check(profile: CoreCarrierProfile, state: CoreProfileAdmissionChecks.LoadedState? = state(),
    wall: Long = 2200000000, elapsed: Long = 0,
    anchor: CoreProfileAdmissionChecks.ProcessAnchor = CoreProfileAdmissionChecks.ProcessAnchor(2200000000, 0),
    generation: Long = 7) = CoreProfileAdmissionChecks.evaluate(profile, state, generation,
      CoreProfileAdmissionChecks.ClockReading(wall, elapsed), anchor)
  private fun reason(actual: CoreProfileAdmissionChecks.Result, expected: CoreProfileAdmissionChecks.Rejection) {
    assertEquals(CoreProfileAdmissionChecks.Result.Rejected(expected), actual)
  }

  @Test fun missingContinuityAndStaleTrustNeverBecomeEmptyValidStores() = profile().use {
    reason(check(it, state = null), CoreProfileAdmissionChecks.Rejection.RECOVERY_REQUIRED)
    reason(check(it, generation = 8), CoreProfileAdmissionChecks.Rejection.STALE_TRUST)
    reason(check(it, generation = 0), CoreProfileAdmissionChecks.Rejection.STALE_TRUST)
  }

  @Test fun exactExpiryHasNoGraceAndFutureSkewHasOneBoundary() = profile().use {
    assertTrue(check(it, wall = it.expiresAt - 1) is CoreProfileAdmissionChecks.Result.EligibleForTransaction)
    reason(check(it, wall = it.expiresAt), CoreProfileAdmissionChecks.Rejection.EXPIRED)
    val initialAnchor = CoreProfileAdmissionChecks.ProcessAnchor(it.issuedAt - 500, 0)
    assertTrue(check(it, state(lower = it.issuedAt - 500), wall = it.issuedAt - 300, anchor = initialAnchor) is CoreProfileAdmissionChecks.Result.EligibleForTransaction)
    reason(check(it, state(lower = it.issuedAt - 500), wall = it.issuedAt - 301, anchor = initialAnchor), CoreProfileAdmissionChecks.Rejection.NOT_YET_VALID)
  }

  @Test fun savedGlobalClockFloorDetectsRollbackWithoutOverflow() = profile().use {
    reason(check(it, wall = it.issuedAt - 301), CoreProfileAdmissionChecks.Rejection.CLOCK_ROLLBACK)
    val accepted = check(it, wall = it.issuedAt - 300) as CoreProfileAdmissionChecks.Result.EligibleForTransaction
    assertEquals(it.issuedAt, accepted.effectiveNowSeconds)
    for (wall in listOf(0L, -1L, Long.MAX_VALUE, 4102444801L)) {
      reason(check(it, wall = wall), CoreProfileAdmissionChecks.Rejection.INVALID_CLOCK)
    }
  }

  @Test fun runningMonotonicAnchorPreventsWallRollbackExtendingExpiry() = profile().use {
    val anchor = CoreProfileAdmissionChecks.ProcessAnchor(it.issuedAt, 1000)
    reason(check(it, wall = it.issuedAt, elapsed = 3601000, anchor = anchor), CoreProfileAdmissionChecks.Rejection.EXPIRED)
    reason(check(it, elapsed = 999, anchor = anchor), CoreProfileAdmissionChecks.Rejection.MONOTONIC_ROLLBACK)
    reason(check(it, elapsed = -1), CoreProfileAdmissionChecks.Rejection.INVALID_CLOCK)
    reason(check(it, elapsed = Long.MAX_VALUE, anchor = anchor), CoreProfileAdmissionChecks.Rejection.INVALID_CLOCK)
  }

  @Test fun exactSignedPayloadDigestDistinguishesSameRevisionConflicts() = profile().use { original ->
    val saved = state(mapOf(identity to CoreProfileAdmissionChecks.Floor(original.revision, original.payloadSha256)))
    val same = check(original, saved) as CoreProfileAdmissionChecks.Result.EligibleForTransaction
    assertTrue(same.idempotent)
    profile(2).use { whitespace ->
      assertNotEquals(original.payloadSha256, whitespace.payloadSha256)
      reason(check(whitespace, saved), CoreProfileAdmissionChecks.Rejection.SAME_REVISION_CONFLICT)
    }
    reason(check(original, state(mapOf(identity to CoreProfileAdmissionChecks.Floor(2, original.payloadSha256)))),
      CoreProfileAdmissionChecks.Rejection.REVISION_ROLLBACK)
  }

  @Test fun newerRevisionKeepsIssuerProfileFloorAndSupportsPost2038Clock() = profile().use { original ->
    profile(1).use { next ->
      val result = check(next, state(mapOf(identity to CoreProfileAdmissionChecks.Floor(1, original.payloadSha256)),
        lower = next.issuedAt), wall = next.issuedAt) as CoreProfileAdmissionChecks.Result.EligibleForTransaction
      assertFalse(result.idempotent)
      assertEquals(Int.MAX_VALUE, result.nextFloor.revision)
      assertEquals(identity, result.identity)
      assertEquals(4102444799L, result.effectiveNowSeconds)
    }
  }

  @Test fun capacityIncludesTombstonesAndDoesNotBlockExistingIdentity(): Unit = profile().use {
    val floors = (0 until 64).associate { number ->
      CoreProfileAdmissionChecks.Identity("fixture", "%032x".format(number)) to CoreProfileAdmissionChecks.Floor(1, "0".repeat(64))
    }.toMutableMap()
    val loaded = state(floors)
    floors.clear()
    assertEquals(64, loaded.floors.size)
    reason(check(it, loaded), CoreProfileAdmissionChecks.Rejection.CAPACITY)
    val existing = loaded.floors.toMutableMap().apply {
      remove(keys.first()); put(identity, CoreProfileAdmissionChecks.Floor(1, it.payloadSha256))
    }
    assertTrue(check(it, state(existing)) is CoreProfileAdmissionChecks.Result.EligibleForTransaction)
    assertThrows(UnsupportedOperationException::class.java) { (loaded.floors as MutableMap).clear() }
  }
}
