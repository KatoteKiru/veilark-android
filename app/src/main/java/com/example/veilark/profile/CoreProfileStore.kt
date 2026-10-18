package com.example.veilark.profile

import android.content.Context

/** UI-safe metadata; it contains no identity keys, client address or readiness. */
data class CoreProfileMetadata(
  val kind: CoreProfileKind,
  val name: String,
  val dnsIpv4: List<String>,
)

/** Selection/import facade. Existing profile files stay separate and are never migrated.
 * Import validates and encrypts before persisting the selected kind; it never starts an engine.
 */
object CoreProfileStore {
  private const val PREFERENCES = "profile_meta"
  private const val KIND_KEY = "core_profile_kind"
  private const val LOAD_ERROR = "Unable to load selected Veilark Core profile."
  private const val IMPORT_ERROR = "Unable to import Veilark Core profile."
  private const val SELECTION_ERROR = "Unable to save Veilark Core profile selection."

  @Synchronized fun selectedKind(context: Context): CoreProfileKind? = CoreProfileKind.resolve(
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all[KIND_KEY],
    CoreBetaProfileStore.exists(context),
    CoreArgonProfileStore.exists(context),
  )

  @Synchronized fun exists(context: Context): Boolean = selectedKind(context) != null

  /** Delete only the selected identity; keep the explicit selection as a tombstone.
   * Removing the preference would silently select another retained Core identity.
   * Caller must first establish that the Core service has stopped.
   */
  @Synchronized fun deleteSelected(context: Context) {
    val kind = selectedKind(context) ?: return
    // Migrated legacy selections may not have an explicit marker yet. Persist it
    // before deletion so a retained profile can never become selected implicitly.
    check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KIND_KEY, kind.preferenceValue).commit()) { SELECTION_ERROR }
    val removed = when (kind) {
      CoreProfileKind.LEGACY_V1 -> CoreBetaProfileStore.delete(context)
      CoreProfileKind.ARGON_V1 -> CoreArgonProfileStore.delete(context)
    }
    check(removed) { "Unable to delete selected Veilark Core profile." }
  }

  @Synchronized fun loadMetadata(context: Context): CoreProfileMetadata {
    try {
      return when (selectedKind(context)) {
        CoreProfileKind.LEGACY_V1 -> CoreBetaProfileStore.load(context).use {
          CoreProfileMetadata(CoreProfileKind.LEGACY_V1, it.name, it.dnsIpv4)
        }
        CoreProfileKind.ARGON_V1 -> CoreArgonProfileStore.load(context).use {
          CoreProfileMetadata(CoreProfileKind.ARGON_V1, it.name, it.dnsIpv4)
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
          CoreProfileMetadata(kind, it.name, it.dnsIpv4)
        }
      }
    } catch (_: Exception) {
      throw IllegalArgumentException(IMPORT_ERROR)
    }
    check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
      .putString(KIND_KEY, kind.preferenceValue).commit()) { SELECTION_ERROR }
    return metadata
  }
}
