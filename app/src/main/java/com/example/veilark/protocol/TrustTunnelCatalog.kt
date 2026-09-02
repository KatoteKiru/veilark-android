package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.SecureProfileStore
import com.example.veilark.profile.SubscriptionIdentity
import com.example.veilark.profile.SubscriptionKind
import com.example.veilark.profile.SubscriptionOrigin
import org.json.JSONArray
import org.json.JSONObject

data class TrustTunnelCatalogEntry(
  val id: String,
  val name: String,
  val config: String,
  val sourceId: String = id,
  val sourceUrl: String? = null,
  val shareLink: String? = null,
  val origin: SubscriptionOrigin = SubscriptionOrigin.LEGACY,
  val fingerprint: String = SubscriptionIdentity.nodeFingerprint(
    SubscriptionKind.TRUST_TUNNEL,
    config,
  ),
)

data class TrustTunnelSourceEntry(
  val id: String,
  val name: String,
  val sourceUrl: String?,
  val origin: SubscriptionOrigin,
  val nodeCount: Int,
  val shareLink: String? = null,
)

object TrustTunnelCatalog {
  fun load(context: Context): List<TrustTunnelCatalogEntry> {
    if (!SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL_CATALOG)) {
      return emptyList()
    }
    val encoded = SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL_CATALOG)
    val decoded = decode(encoded)
    if (JSONObject(encoded).optInt("version", 1) < FORMAT_VERSION) {
      // Do not discard a valid in-memory migration if its opportunistic write fails.
      runCatching { save(context, decoded) }
    }
    return decoded
  }

  fun migrateSingle(
    context: Context,
    displayName: String?,
    sourceUrl: String? = null,
  ): List<TrustTunnelCatalogEntry> {
    val existing = load(context)
    if (existing.isNotEmpty() ||
      !SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL)
    ) {
      return existing
    }
    val config = SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL)
    val name = displayName?.takeIf(String::isNotBlank) ?: "TrustTunnel"
    val migratableUrl = sourceUrl?.takeIf {
      it.startsWith("https://", ignoreCase = true)
    }
    return replaceSource(
      context = context,
      profiles = listOf(CompiledTrustTunnelProfile(name, config)),
      sourceUrl = migratableUrl,
    )
  }

  fun migrateLegacySource(
    context: Context,
    sourceUrl: String?,
  ): List<TrustTunnelCatalogEntry> {
    if (sourceUrl.isNullOrBlank()) return load(context)
    val entries = load(context)
    val legacy = entries.filter { it.origin == SubscriptionOrigin.LEGACY }
    if (legacy.isEmpty()) return entries
    val normalizedUrl = runCatching { SubscriptionIdentity.normalizeUrl(sourceUrl) }
      .getOrElse { return entries }
    val migratedSourceId = sourceId(normalizedUrl, normalizedUrl)
    val migrated = entries.map { entry ->
      if (entry.origin != SubscriptionOrigin.LEGACY) {
        entry
      } else {
        entry.copy(
          sourceId = migratedSourceId,
          sourceUrl = normalizedUrl,
          origin = SubscriptionOrigin.REMOTE,
        )
      }
    }
    save(context, migrated)
    return migrated
  }

  fun upsert(
    context: Context,
    profiles: List<CompiledTrustTunnelProfile>,
  ): List<TrustTunnelCatalogEntry> {
    if (profiles.isEmpty()) return load(context)
    var result = load(context)
    profiles.forEach { profile ->
      result = replaceSourceEntries(
        entries = result,
        profiles = listOf(profile),
        sourceUrl = null,
        origin = SubscriptionOrigin.MANUAL,
        localIdentity = profile.config,
      )
    }
    save(context, result)
    return result
  }

  /** Replaces all and only profiles that belong to one subscription source. */
  fun replaceSource(
    context: Context,
    profiles: List<CompiledTrustTunnelProfile>,
    sourceUrl: String?,
    origin: SubscriptionOrigin = if (sourceUrl == null) {
      SubscriptionOrigin.MANUAL
    } else {
      SubscriptionOrigin.REMOTE
    },
    localIdentity: String = profiles.joinToString("\n") { it.config },
  ): List<TrustTunnelCatalogEntry> {
    require(profiles.isNotEmpty()) { "В источнике нет профилей TrustTunnel" }
    val result = replaceSourceEntries(
      entries = load(context),
      profiles = profiles,
      sourceUrl = sourceUrl,
      origin = origin,
      localIdentity = localIdentity,
    )
    save(context, result)
    return result
  }

  fun sourceId(sourceUrl: String?, localIdentity: String): String =
    SubscriptionIdentity.sourceId(
      SubscriptionKind.TRUST_TUNNEL,
      sourceUrl,
      localIdentity,
    )

  fun sources(entries: List<TrustTunnelCatalogEntry>): List<TrustTunnelSourceEntry> =
    entries.groupBy(TrustTunnelCatalogEntry::sourceId).map { (sourceId, profiles) ->
      val first = profiles.first()
      TrustTunnelSourceEntry(
        id = sourceId,
        name = if (profiles.size == 1) first.name else "${first.name} +${profiles.size - 1}",
        sourceUrl = first.sourceUrl,
        origin = first.origin,
        nodeCount = profiles.size,
        shareLink = first.shareLink ?: first.sourceUrl,
      )
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

  fun removeSource(context: Context, sourceId: String): List<TrustTunnelCatalogEntry> {
    val result = removeSource(load(context), sourceId)
    save(context, result)
    return result
  }

  internal fun removeSource(
    entries: List<TrustTunnelCatalogEntry>,
    sourceId: String,
  ): List<TrustTunnelCatalogEntry> {
    require(sourceId.isNotBlank()) { "Не указан источник подписки" }
    require(entries.any { it.sourceId == sourceId }) { "Подписка больше не найдена" }
    return entries.filterNot { it.sourceId == sourceId }
  }

  fun removeWhere(
    context: Context,
    predicate: (TrustTunnelCatalogEntry) -> Boolean,
  ): List<TrustTunnelCatalogEntry> {
    val result = load(context).filterNot(predicate)
    save(context, result)
    return result
  }

  fun activate(
    context: Context,
    profiles: List<TrustTunnelCatalogEntry>,
    id: String,
  ): TrustTunnelCatalogEntry {
    val selected = profiles.firstOrNull { it.id == id }
      ?: error("Профиль TrustTunnel не найден")
    SecureProfileStore.save(context, SecureProfileStore.TRUST_TUNNEL, selected.config)
    return selected
  }

  fun nodes(profiles: List<TrustTunnelCatalogEntry>): List<ConnectionNode> =
    profiles.map { profile ->
      ConnectionNode(
        tag = profile.id,
        name = profile.name,
        protocol = "TrustTunnel",
      )
    }

  internal fun encode(profiles: List<TrustTunnelCatalogEntry>): String =
    JSONObject()
      .put("version", FORMAT_VERSION)
      .put(
        "profiles",
        JSONArray().apply {
          profiles.forEach { profile ->
            put(
              JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("config", profile.config)
                .put("sourceId", profile.sourceId)
                .put("sourceUrl", profile.sourceUrl ?: JSONObject.NULL)
                .put("shareLink", profile.shareLink ?: JSONObject.NULL)
                .put("origin", profile.origin.wireName)
                .put("fingerprint", profile.fingerprint),
            )
          }
        },
      )
      .toString()

  internal fun decode(value: String): List<TrustTunnelCatalogEntry> {
    val root = JSONObject(value)
    val version = root.getInt("version")
    require(version in 1..FORMAT_VERSION) {
      "Версия каталога TrustTunnel не поддерживается"
    }
    val profiles = root.getJSONArray("profiles")
    return buildList {
      repeat(profiles.length()) { index ->
        val item = profiles.getJSONObject(index)
        val name = item.getString("name").trim().take(80)
        val config = item.getString("config")
        require(name.isNotBlank() && config.isNotBlank()) {
          "Повреждён профиль TrustTunnel"
        }
        val fingerprint = item.optString("fingerprint").takeIf(String::isNotBlank)
          ?: SubscriptionIdentity.nodeFingerprint(SubscriptionKind.TRUST_TUNNEL, config)
        val sourceUrl = item.optString("sourceUrl").takeIf {
          it.isNotBlank() && it != "null"
        }
        val shareLink = item.optString("shareLink").takeIf {
          it.isNotBlank() && it != "null"
        }
        add(
          TrustTunnelCatalogEntry(
            id = item.getString("id"),
            name = name,
            config = config,
            sourceId = if (version >= 2) {
              item.getString("sourceId")
            } else {
              sourceId(null, config)
            },
            sourceUrl = sourceUrl,
            shareLink = shareLink,
            origin = if (version >= 2) {
              SubscriptionOrigin.fromWireName(item.optString("origin"))
            } else {
              SubscriptionOrigin.LEGACY
            },
            fingerprint = fingerprint,
          ),
        )
      }
    }
  }

  internal fun replaceSourceEntries(
    entries: List<TrustTunnelCatalogEntry>,
    profiles: List<CompiledTrustTunnelProfile>,
    sourceUrl: String?,
    origin: SubscriptionOrigin,
    localIdentity: String,
  ): List<TrustTunnelCatalogEntry> {
    val normalizedUrl = sourceUrl
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(SubscriptionIdentity::normalizeUrl)
    val sourceId = sourceId(normalizedUrl, localIdentity)
    val replacements = profiles.map { profile ->
      val fingerprint = SubscriptionIdentity.nodeFingerprint(
        SubscriptionKind.TRUST_TUNNEL,
        profile.config,
      )
      TrustTunnelCatalogEntry(
        id = SubscriptionIdentity.entryId(sourceId, fingerprint),
        name = profile.displayName.trim().take(80),
        config = profile.config,
        sourceId = sourceId,
        sourceUrl = normalizedUrl,
        shareLink = profile.shareLink,
        origin = origin,
        fingerprint = fingerprint,
      )
    }.distinctBy(TrustTunnelCatalogEntry::fingerprint)
    val replacementFingerprints = replacements.mapTo(hashSetOf()) { it.fingerprint }
    val preserved = entries.filterNot { entry ->
      entry.sourceId == sourceId ||
        (origin == SubscriptionOrigin.BUILT_IN &&
          entry.origin == SubscriptionOrigin.LEGACY &&
          entry.fingerprint in replacementFingerprints)
    }
    return (preserved + replacements)
      .distinctBy(TrustTunnelCatalogEntry::id)
      .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
  }

  private fun save(context: Context, profiles: List<TrustTunnelCatalogEntry>) {
    SecureProfileStore.save(
      context,
      SecureProfileStore.TRUST_TUNNEL_CATALOG,
      encode(profiles),
    )
  }

  private const val FORMAT_VERSION = 2
}
