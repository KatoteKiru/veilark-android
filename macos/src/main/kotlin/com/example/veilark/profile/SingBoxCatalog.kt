package com.example.veilark.profile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class SingBoxCatalogEntry(
  val id: String,
  val name: String,
  val config: String,
  val nodes: List<ConnectionNode>,
  val selectedNodeTag: String,
  val sourceUrl: String?,
  val origin: SubscriptionOrigin = if (sourceUrl == null) {
    SubscriptionOrigin.MANUAL
  } else {
    SubscriptionOrigin.REMOTE
  },
  val nodeFingerprints: Map<String, String> = emptyMap(),
)

object SingBoxCatalog {
  fun create(
    config: String,
    nodes: List<ConnectionNode>,
    selectedNodeTag: String,
    sourceUrl: String?,
    suggestedName: String?,
  ): SingBoxCatalogEntry {
    val normalizedUrl = sourceUrl
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?.let(SubscriptionIdentity::normalizeUrl)
    val name = normalizedUrl?.let(::hostName)
      ?: suggestedName
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: "Local profile"
    val identity = normalizedUrl ?: config
    return SingBoxCatalogEntry(
      id = SubscriptionIdentity.sourceId(
        SubscriptionKind.SING_BOX,
        normalizedUrl,
        identity,
      ),
      name = name.take(80),
      config = config,
      nodes = nodes,
      selectedNodeTag = selectedNodeTag,
      sourceUrl = normalizedUrl,
      origin = if (normalizedUrl == null) {
        SubscriptionOrigin.MANUAL
      } else {
        SubscriptionOrigin.REMOTE
      },
      nodeFingerprints = nodeFingerprints(config, nodes),
    )
  }

  fun replaceSource(
    entries: List<SingBoxCatalogEntry>,
    entry: SingBoxCatalogEntry,
  ): List<SingBoxCatalogEntry> {
    val result = entries.filterNot { existing ->
      existing.id == entry.id ||
        (
          entry.sourceUrl != null && existing.sourceUrl?.let {
            runCatching { SubscriptionIdentity.normalizeUrl(it) }.getOrNull()
          } == entry.sourceUrl
          )
    } + entry
    return result.distinctBy(SingBoxCatalogEntry::id)
  }

  fun removeSource(
    entries: List<SingBoxCatalogEntry>,
    sourceId: String,
  ): List<SingBoxCatalogEntry> {
    require(sourceId.isNotBlank()) { "Не указан источник подписки" }
    require(entries.any { it.id == sourceId }) { "Подписка больше не найдена" }
    return entries.filterNot { it.id == sourceId }
  }

  fun encode(entries: List<SingBoxCatalogEntry>): String =
    JSONObject()
      .put("version", FORMAT_VERSION)
      .put(
        "profiles",
        JSONArray().apply {
          entries.forEach { entry ->
            put(
              JSONObject()
                .put("id", entry.id)
                .put("name", entry.name)
                .put("config", entry.config)
                .put("nodes", ProfileSelection.encodeNodes(entry.nodes))
                .put("selectedNodeTag", entry.selectedNodeTag)
                .put("sourceUrl", entry.sourceUrl ?: JSONObject.NULL)
                .put("origin", entry.origin.wireName)
                .put(
                  "nodeFingerprints",
                  JSONObject().apply {
                    entry.nodeFingerprints.forEach { (tag, fingerprint) ->
                      put(tag, fingerprint)
                    }
                  },
                ),
            )
          }
        },
      )
      .toString()

  fun decode(value: String): List<SingBoxCatalogEntry> {
    val root = JSONObject(value)
    val version = root.getInt("version")
    require(version in 1..FORMAT_VERSION) { "Версия каталога sing-box не поддерживается" }
    val profiles = root.getJSONArray("profiles")
    return buildList {
      repeat(profiles.length()) { index ->
        val item = profiles.getJSONObject(index)
        val config = item.getString("config")
        val name = item.getString("name").trim().take(80)
        require(config.isNotBlank() && name.isNotBlank()) { "Повреждён профиль sing-box" }
        val sourceUrl = item.optString("sourceUrl").takeIf {
          it.isNotBlank() && it != "null"
        }
        val origin = if (version >= 2) {
          SubscriptionOrigin.fromWireName(item.optString("origin"))
        } else if (sourceUrl == null) {
          SubscriptionOrigin.LEGACY
        } else {
          SubscriptionOrigin.REMOTE
        }
        val fingerprints = if (version >= 2) {
          item.optJSONObject("nodeFingerprints")?.let { objectValue ->
            buildMap {
              objectValue.keys().forEach { tag -> put(tag, objectValue.getString(tag)) }
            }
          }.orEmpty()
        } else {
          emptyMap()
        }
        val nodes = ProfileSelection.decodeNodes(item.optString("nodes"))
        add(
          SingBoxCatalogEntry(
            id = item.getString("id"),
            name = name,
            config = config,
            nodes = nodes,
            selectedNodeTag = item.optString(
              "selectedNodeTag",
              ProfileSelection.AUTOMATIC_TAG,
            ),
            sourceUrl = sourceUrl,
            origin = origin,
            nodeFingerprints = if (version >= 2) {
              fingerprints
            } else {
              nodeFingerprints(config, nodes)
            },
          ),
        )
      }
    }
  }

  private fun hostName(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.")?.takeIf(String::isNotBlank)
  }.getOrNull() ?: "Subscription"

  private fun nodeFingerprints(
    config: String,
    nodes: List<ConnectionNode>,
  ): Map<String, String> {
    val outbounds = runCatching { JSONObject(config).optJSONArray("outbounds") }.getOrNull()
    return nodes.associate { node ->
      val outbound = outbounds?.let { values ->
        (0 until values.length())
          .asSequence()
          .mapNotNull(values::optJSONObject)
          .firstOrNull { it.optString("tag") == node.tag }
      }
      val identity = outbound?.toString() ?: "${node.protocol}\n${node.tag}\n${node.name}"
      node.tag to SubscriptionIdentity.nodeFingerprint(SubscriptionKind.SING_BOX, identity)
    }
  }

  private const val FORMAT_VERSION = 2
}
