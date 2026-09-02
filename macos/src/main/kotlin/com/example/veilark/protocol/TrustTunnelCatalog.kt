package com.example.veilark.protocol

import com.example.veilark.profile.ConnectionNode
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
)

object TrustTunnelCatalog {
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
      )
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

  fun removeSource(
    entries: List<TrustTunnelCatalogEntry>,
    sourceId: String,
  ): List<TrustTunnelCatalogEntry> {
    require(sourceId.isNotBlank()) { "Не указан источник подписки" }
    require(entries.any { it.sourceId == sourceId }) { "Подписка больше не найдена" }
    return entries.filterNot { it.sourceId == sourceId }
  }

  fun nodes(profiles: List<TrustTunnelCatalogEntry>): List<ConnectionNode> =
    profiles.map { profile ->
      ConnectionNode(
        tag = profile.id,
        name = profile.name,
        protocol = "TrustTunnel",
      )
    }

  fun encode(profiles: List<TrustTunnelCatalogEntry>): String =
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
                .put("origin", profile.origin.wireName)
                .put("fingerprint", profile.fingerprint),
            )
          }
        },
      )
      .toString()

  fun decode(value: String): List<TrustTunnelCatalogEntry> {
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

  fun replaceSourceEntries(
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
        origin = origin,
        fingerprint = fingerprint,
      )
    }.distinctBy(TrustTunnelCatalogEntry::fingerprint)
    val replacementFingerprints = replacements.mapTo(hashSetOf()) { it.fingerprint }
    val preserved = entries.filterNot { entry ->
      entry.sourceId == sourceId ||
        (
          origin == SubscriptionOrigin.BUILT_IN &&
            entry.origin == SubscriptionOrigin.LEGACY &&
            entry.fingerprint in replacementFingerprints
          )
    }
    return (preserved + replacements)
      .distinctBy(TrustTunnelCatalogEntry::id)
      .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
  }

  private const val FORMAT_VERSION = 2
}
