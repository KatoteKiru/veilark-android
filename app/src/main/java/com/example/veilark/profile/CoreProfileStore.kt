package com.example.veilark.profile

import android.content.Context
import com.example.veilark.R

/** UI-safe metadata; it contains no identity keys, client address or readiness. */
data class CoreProfileMetadata(
  val kind: CoreProfileKind,
  val name: String,
  val dnsIpv4: List<String>,
  val argonHandshakeProfile: CoreArgonHandshakeProfile? = null,
)

/** Selection/import facade. Existing profile files stay separate and are never migrated.
 * Import validates and encrypts before persisting the selected kind; it never starts an engine.
 *
 * Two routes are kept side by side: the direct route (legacy, Argon or an
 * enrollment record, chosen as before) and the relay route for whitelists.
 * Which one is used is a separate choice, so importing or picking one route
 * never discards the other route's profile or selection.
 */
object CoreProfileStore {
  private const val PREFERENCES = "profile_meta"
  private const val KIND_KEY = "core_profile_kind"
  private const val ENROLLMENT_ISSUER = "core_enrollment_issuer"
  private const val ENROLLMENT_PROFILE = "core_enrollment_profile"
  private const val ROUTE_KEY = "core_route"
  private const val ROUTE_RELAY = "relay"
  private const val LOAD_ERROR = "Unable to load selected Veilark Core profile."
  private const val IMPORT_ERROR = "Unable to import Veilark Core profile."
  private const val SELECTION_ERROR = "Unable to save Veilark Core profile selection."

  @Synchronized fun selectedKind(context: Context): CoreProfileKind? {
    // A deleted relay profile stays selected as a tombstone, never falling back to the direct route.
    if (relaySelected(context)) return CoreProfileKind.ARGON_RELAY_V1.takeIf { CoreArgonRelayProfileStore.exists(context) }
    val identity = selectedEnrollment(context)
    if (identity != null) {
      return EnrollmentV2Store.loadBlocking(context, identity.first, identity.second)?.use { CoreProfileKind.ARGON_V1 }
    }
    return CoreProfileKind.resolve(
      context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all[KIND_KEY],
      CoreBetaProfileStore.exists(context), CoreArgonProfileStore.exists(context))
  }

  private fun relaySelected(context: Context): Boolean =
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(ROUTE_KEY, null) == ROUTE_RELAY

  /** Both routes may be stored; the UI offers a choice only between existing ones. */
  @Synchronized fun hasRelayProfile(context: Context): Boolean = CoreArgonRelayProfileStore.exists(context)
  @Synchronized fun hasDirectProfile(context: Context): Boolean = directKind(context) != null
  @Synchronized fun relayRouteSelected(context: Context): Boolean = relaySelected(context)

  private fun directKind(context: Context): CoreProfileKind? {
    val identity = selectedEnrollment(context)
    if (identity != null) return CoreProfileKind.ARGON_V1.takeIf { EnrollmentV2Store.hasLocalRecord(context, identity.first, identity.second) }
    return CoreProfileKind.resolve(
      context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all[KIND_KEY],
      CoreBetaProfileStore.exists(context), CoreArgonProfileStore.exists(context))
  }

  /** Switches between stored routes; the caller must first establish that the Core service has stopped. */
  @Synchronized fun selectRoute(context: Context, relay: Boolean) {
    check(if (relay) CoreArgonRelayProfileStore.exists(context) else directKind(context) != null) { SELECTION_ERROR }
    commitRoute(context, relay)
  }

  private fun commitRoute(context: Context, relay: Boolean) {
    val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
    if (relay) editor.putString(ROUTE_KEY, ROUTE_RELAY) else editor.remove(ROUTE_KEY)
    check(editor.commit()) { SELECTION_ERROR }
  }

  /** A Passage profile delivered by a verified enroll/v2 link. It replaces the Passage slot
   * and selects that route, leaving the direct route's kind and enrollment pointer alone. */
  @Synchronized internal fun importSignedRelay(context: Context, payload: ByteArray): CoreProfileMetadata {
    val metadata = try {
      CoreArgonRelayProfileStore.import(context, payload, allowStaticCredentials = true).use {
        CoreProfileMetadata(CoreProfileKind.ARGON_RELAY_V1, it.name, it.dnsIpv4, it.handshakeProfile)
      }
    } catch (_: Exception) {
      throw IllegalArgumentException(IMPORT_ERROR)
    }
    commitRoute(context, relay = true)
    return metadata
  }

  @Synchronized fun loadSelectedRelay(context: Context): CoreArgonRelayProfile {
    check(relaySelected(context)) { LOAD_ERROR }
    return CoreArgonRelayProfileStore.load(context)
  }

  private fun selectedEnrollment(context: Context): Pair<String, String>? {
    return enrollmentIdentity(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all)
  }

  internal fun enrollmentIdentity(saved: Map<String, *>): Pair<String, String>? {
    if (!saved.containsKey(ENROLLMENT_ISSUER) && !saved.containsKey(ENROLLMENT_PROFILE)) return null
    val issuer = saved[ENROLLMENT_ISSUER] as? String ?: error(LOAD_ERROR)
    val profile = saved[ENROLLMENT_PROFILE] as? String ?: error(LOAD_ERROR)
    check(saved[KIND_KEY] == CoreProfileKind.ARGON_V1.preferenceValue &&
      listOf(issuer, profile).all { Regex("[A-Za-z0-9_-]{1,48}").matches(it) }) { LOAD_ERROR }
    return issuer to profile
  }

  /** Validates the authoritative protected record before committing a metadata-only pointer. */
  @Synchronized internal fun selectEnrollment(context: Context, issuer: String, profileId: String): CoreProfileMetadata {
    val value = EnrollmentV2Store.loadBlocking(context, issuer, profileId) ?: error(LOAD_ERROR)
    val metadata = value.use {
      val bytes = it.profileCopy()
      try { CoreArgonProfile.parse(bytes).use { profile ->
        CoreProfileMetadata(CoreProfileKind.ARGON_V1, profile.name, profile.dnsIpv4, profile.handshakeProfile)
      } } finally { bytes.fill(0) }
    }
    check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KIND_KEY, CoreProfileKind.ARGON_V1.preferenceValue)
      .putString(ENROLLMENT_ISSUER, issuer).putString(ENROLLMENT_PROFILE, profileId)
      .remove(ROUTE_KEY).commit()) { SELECTION_ERROR }
    return metadata
  }

  /** One selected-source resolver used by metadata and the real Core service. */
  @Synchronized fun loadSelectedArgon(context: Context): CoreArgonProfile {
    check(!relaySelected(context)) { LOAD_ERROR }
    val identity = selectedEnrollment(context) ?: return CoreArgonProfileStore.load(context)
    val value = EnrollmentV2Store.loadBlocking(context, identity.first, identity.second) ?: error(LOAD_ERROR)
    return value.use {
      val bytes = it.profileCopy()
      try { CoreArgonProfile.parse(bytes).also { profile -> profile.discardEnvelope() } }
      finally { bytes.fill(0) }
    }
  }

  @Synchronized fun exists(context: Context): Boolean = selectedKind(context) != null

  /** Retain the existing card/delete affordance after trust revocation, without
   * treating a local record as authorized for service admission or displaying its name.
   */
  @Synchronized fun displayName(context: Context): String? {
    try { return loadMetadata(context).name } catch (_: Exception) { }
    val identity = selectedEnrollment(context) ?: return null
    return if (EnrollmentV2Store.hasLocalRecord(context, identity.first, identity.second))
      context.getString(R.string.core_beta_title) else null
  }

  /** Delete only the selected identity; keep the explicit selection as a tombstone.
   * Removing the preference would silently select another retained Core identity.
   * Caller must first establish that the Core service has stopped.
   */
  @Synchronized fun deleteSelected(context: Context) {
    if (relaySelected(context)) {
      if (CoreArgonRelayProfileStore.exists(context)) check(CoreArgonRelayProfileStore.delete(context)) { "Unable to delete selected Veilark Core profile." }
      return
    }
    val identity = selectedEnrollment(context)
    if (identity != null) {
      // Revoking issuer trust must prevent use, not prevent local deletion.
      // Keep this pointer as a tombstone: never reveal a retained raw identity.
      EnrollmentV2Store.deleteBlocking(context, identity.first, identity.second)
      return
    }
    val kind = selectedKind(context) ?: return
    // Migrated legacy selections may not have an explicit marker yet. Persist it
    // before deletion so a retained profile can never become selected implicitly.
    check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KIND_KEY, kind.preferenceValue).commit()) { SELECTION_ERROR }
    val removed = when (kind) {
      CoreProfileKind.LEGACY_V1 -> CoreBetaProfileStore.delete(context)
      CoreProfileKind.ARGON_V1 -> CoreArgonProfileStore.delete(context)
      CoreProfileKind.ARGON_RELAY_V1 -> error(SELECTION_ERROR)
    }
    check(removed) { "Unable to delete selected Veilark Core profile." }
  }

  @Synchronized fun loadMetadata(context: Context): CoreProfileMetadata {
    try {
      return when (selectedKind(context)) {
        CoreProfileKind.LEGACY_V1 -> CoreBetaProfileStore.load(context).use {
          CoreProfileMetadata(CoreProfileKind.LEGACY_V1, it.name, it.dnsIpv4)
        }
        CoreProfileKind.ARGON_V1 -> loadSelectedArgon(context).use {
          CoreProfileMetadata(CoreProfileKind.ARGON_V1, it.name, it.dnsIpv4, it.handshakeProfile)
        }
        CoreProfileKind.ARGON_RELAY_V1 -> loadSelectedRelay(context).use {
          CoreProfileMetadata(CoreProfileKind.ARGON_RELAY_V1, it.name, it.dnsIpv4, it.handshakeProfile)
        }
        null -> throw IllegalStateException(LOAD_ERROR)
      }
    } catch (_: Exception) {
      throw IllegalStateException(LOAD_ERROR)
    }
  }

  @Synchronized fun import(context: Context, payload: ByteArray): CoreProfileMetadata {
    val kind = CoreProfileKind.recognize(payload)
    val metadata = try {
      when (kind) {
        CoreProfileKind.LEGACY_V1 -> CoreBetaProfileStore.import(context, payload).use {
          CoreProfileMetadata(kind, it.name, it.dnsIpv4)
        }
        CoreProfileKind.ARGON_V1 -> CoreArgonProfileStore.import(context, payload).use {
          CoreProfileMetadata(kind, it.name, it.dnsIpv4, it.handshakeProfile)
        }
        CoreProfileKind.ARGON_RELAY_V1 -> CoreArgonRelayProfileStore.import(context, payload).use {
          CoreProfileMetadata(kind, it.name, it.dnsIpv4, it.handshakeProfile)
        }
      }
    } catch (_: Exception) {
      throw IllegalArgumentException(IMPORT_ERROR)
    }
    if (kind == CoreProfileKind.ARGON_RELAY_V1) {
      // The direct route's kind and enrollment pointer stay as they were.
      commitRoute(context, relay = true)
      return metadata
    }
    check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KIND_KEY, kind.preferenceValue)
      .remove(ENROLLMENT_ISSUER).remove(ENROLLMENT_PROFILE).remove(ROUTE_KEY).commit()) { SELECTION_ERROR }
    return metadata
  }
}
