package com.example.veilark.profile

/** Inactive checks inside a future registry transaction, never a VPN-start permit. */
internal object CoreProfileAdmissionChecks {
  private const val MAX_EPOCH = 4102444800L
  data class Identity(val issuerId: String, val profileId: String) {
    init {
      require(issuerId.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}")))
      require(profileId.matches(Regex("[0-9a-f]{32}")))
    }
  }
  data class Floor(val revision: Int, val payloadSha256: String) {
    init { require(revision > 0 && payloadSha256.matches(Regex("[0-9a-f]{64}"))) }
  }
  /** Only authenticated, validated storage may supply this; missing state is NOT empty. */
  class LoadedState(val lowerBoundSeconds: Long, floors: Map<Identity, Floor>) {
    val floors: Map<Identity, Floor> = java.util.Collections.unmodifiableMap(floors.toMap())
    init { require(lowerBoundSeconds in 1..MAX_EPOCH && this.floors.size <= 64) }
  }
  data class ProcessAnchor(val epochSeconds: Long, val elapsedRealtimeMs: Long) {
    init { require(epochSeconds in 1..MAX_EPOCH && elapsedRealtimeMs >= 0) }
  }
  data class ClockReading(val wallSeconds: Long, val elapsedRealtimeMs: Long)
  enum class Rejection {
    RECOVERY_REQUIRED, STALE_TRUST, INVALID_CLOCK, CLOCK_ROLLBACK,
    MONOTONIC_ROLLBACK, NOT_YET_VALID, EXPIRED, REVISION_ROLLBACK,
    SAME_REVISION_CONFLICT, CAPACITY,
  }
  sealed interface Result {
    data class Rejected(val reason: Rejection) : Result
    /** Needs atomic persistence and current trust revalidation before runtime adoption. */
    data class EligibleForTransaction(val effectiveNowSeconds: Long, val nextFloor: Floor,
      val idempotent: Boolean, val identity: Identity) : Result
  }

  /** Load state BEFORE reading clock, under the registry lock. Retain one anchor per process. */
  fun evaluate(profile: CoreCarrierProfile, state: LoadedState?, currentTrustGeneration: Long,
    clock: ClockReading, anchor: ProcessAnchor): Result {
    fun reject(reason: Rejection) = Result.Rejected(reason)
    if (state == null) return reject(Rejection.RECOVERY_REQUIRED)
    if (currentTrustGeneration <= 0 || profile.trustGeneration != currentTrustGeneration) return reject(Rejection.STALE_TRUST)
    if (clock.wallSeconds !in 1..MAX_EPOCH || clock.elapsedRealtimeMs < 0) return reject(Rejection.INVALID_CLOCK)
    if (clock.wallSeconds + 300 < state.lowerBoundSeconds) return reject(Rejection.CLOCK_ROLLBACK)
    var effective = maxOf(clock.wallSeconds, state.lowerBoundSeconds)
    if (clock.elapsedRealtimeMs < anchor.elapsedRealtimeMs) return reject(Rejection.MONOTONIC_ROLLBACK)
    val seconds = (clock.elapsedRealtimeMs - anchor.elapsedRealtimeMs) / 1000
    if (seconds > MAX_EPOCH - anchor.epochSeconds) return reject(Rejection.INVALID_CLOCK)
    effective = maxOf(effective, anchor.epochSeconds + seconds)
    if (profile.issuedAt > effective + 300) return reject(Rejection.NOT_YET_VALID)
    if (effective >= profile.expiresAt) return reject(Rejection.EXPIRED)
    val identity = Identity(profile.issuerId, profile.profileId) // Key rollover never selects a new floor.
    val old = state.floors[identity]
    if (old != null) {
      if (profile.revision < old.revision) return reject(Rejection.REVISION_ROLLBACK)
      if (profile.revision == old.revision && profile.payloadSha256 != old.payloadSha256) return reject(Rejection.SAME_REVISION_CONFLICT)
    } else if (state.floors.size >= 64) return reject(Rejection.CAPACITY)
    return Result.EligibleForTransaction(effective, Floor(profile.revision, profile.payloadSha256),
      old?.revision == profile.revision, identity)
  }
}
