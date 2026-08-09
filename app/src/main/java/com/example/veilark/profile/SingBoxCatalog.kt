package com.example.veilark.profile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

data class SingBoxCatalogEntry(
  val id: String,
  val name: String,
  val config: String,
  val nodes: List<ConnectionNode>,
  val selectedNodeTag: String,
  val sourceUrl: String?,
)

object SingBoxCatalog {
  fun load(context: Context): List<SingBoxCatalogEntry> {
    if (!SecureProfileStore.exists(context, SecureProfileStore.SING_BOX_CATALOG)) {
      return emptyList()
    }
    return runCatching {
      decode(SecureProfileStore.load(context, SecureProfileStore.SING_BOX_CATALOG))
    }.getOrDefault(emptyList())
  }

  fun migrateActive(
    context: Context,
    displayName: String?,
    nodes: List<ConnectionNode>,
    selectedNodeTag: String?,
    sourceUrl: String?,
  ): List<SingBoxCatalogEntry> {
    val existing = load(context)
    if (existing.isNotEmpty() ||
      !SecureProfileStore.exists(context, SecureProfileStore.SING_BOX)
    ) {
      return existing
    }
    val config = SecureProfileStore.load(context, SecureProfileStore.SING_BOX)
    return upsert(
      context,
      create(
        config = config,
        nodes = nodes,
        selectedNodeTag = selectedNodeTag ?: ProfileSelection.AUTOMATIC_TAG,
        sourceUrl = sourceUrl,
        suggestedName = displayName,
      ),
    )
  }

  fun create(
    config: String,
    nodes: List<ConnectionNode>,
    selectedNodeTag: String,
    sourceUrl: String?,
    suggestedName: String?,
  ): SingBoxCatalogEntry {
    val normalizedUrl = sourceUrl?.trim()?.takeIf(String::isNotBlank)
    val name = normalizedUrl?.let(::hostName)
      ?: suggestedName
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: "Локальный профиль"
    val identity = normalizedUrl ?: "$name\n$config"
    return SingBoxCatalogEntry(
      id = stableId(identity),
      name = name.take(80),
      config = config,
      nodes = nodes,
      selectedNodeTag = selectedNodeTag,
      sourceUrl = normalizedUrl,
    )
  }

  fun upsert(
    context: Context,
    entry: SingBoxCatalogEntry,
  ): List<SingBoxCatalogEntry> {
    val entries = load(context).associateByTo(linkedMapOf(), SingBoxCatalogEntry::id)
    entries[entry.id] = entry
    val result = entries.values.toList()
    save(context, result)
    return result
  }

  fun activate(context: Context, entry: SingBoxCatalogEntry) {
    SecureProfileStore.save(context, SecureProfileStore.SING_BOX, entry.config)
  }

  fun find(context: Context, id: String?): SingBoxCatalogEntry? {
    val entries = load(context)
    return entries.firstOrNull { it.id == id } ?: entries.firstOrNull()
  }

  internal fun encode(entries: List<SingBoxCatalogEntry>): String =
    JSONObject()
      .put("version", 1)
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
                .put("sourceUrl", entry.sourceUrl ?: JSONObject.NULL),
            )
          }
        },
      )
      .toString()

  internal fun decode(value: String): List<SingBoxCatalogEntry> {
    val root = JSONObject(value)
    require(root.getInt("version") == 1) { "Версия каталога sing-box не поддерживается" }
    val profiles = root.getJSONArray("profiles")
    return buildList {
      repeat(profiles.length()) { index ->
        val item = profiles.getJSONObject(index)
        val config = item.getString("config")
        val name = item.getString("name").trim().take(80)
        require(config.isNotBlank() && name.isNotBlank()) { "Повреждён профиль sing-box" }
        add(
          SingBoxCatalogEntry(
            id = item.getString("id"),
            name = name,
            config = config,
            nodes = ProfileSelection.decodeNodes(item.optString("nodes")),
            selectedNodeTag = item.optString(
              "selectedNodeTag",
              ProfileSelection.AUTOMATIC_TAG,
            ),
            sourceUrl = item.optString("sourceUrl").takeIf {
              it.isNotBlank() && it != "null"
            },
          ),
        )
      }
    }
  }

  private fun save(context: Context, entries: List<SingBoxCatalogEntry>) {
    SecureProfileStore.save(
      context,
      SecureProfileStore.SING_BOX_CATALOG,
      encode(entries),
    )
  }

  private fun hostName(url: String): String = runCatching {
    URI(url).host?.removePrefix("www.")?.takeIf(String::isNotBlank)
  }.getOrNull() ?: "Подписка"

  private fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(value.trim().toByteArray(Charsets.UTF_8))
    return digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
