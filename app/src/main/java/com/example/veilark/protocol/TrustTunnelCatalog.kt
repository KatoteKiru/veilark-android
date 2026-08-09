package com.example.veilark.protocol

import android.content.Context
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.SecureProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class TrustTunnelCatalogEntry(
  val id: String,
  val name: String,
  val config: String,
)

object TrustTunnelCatalog {
  fun load(context: Context): List<TrustTunnelCatalogEntry> {
    if (!SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL_CATALOG)) {
      return emptyList()
    }
    return decode(
      SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL_CATALOG),
    )
  }

  fun migrateSingle(context: Context, displayName: String?): List<TrustTunnelCatalogEntry> {
    val existing = load(context)
    if (existing.isNotEmpty() ||
      !SecureProfileStore.exists(context, SecureProfileStore.TRUST_TUNNEL)
    ) {
      return existing
    }
    val config = SecureProfileStore.load(context, SecureProfileStore.TRUST_TUNNEL)
    val name = displayName?.takeIf(String::isNotBlank) ?: "TrustTunnel"
    return upsert(context, listOf(CompiledTrustTunnelProfile(name, config)))
  }

  fun upsert(
    context: Context,
    profiles: List<CompiledTrustTunnelProfile>,
  ): List<TrustTunnelCatalogEntry> {
    if (profiles.isEmpty()) return load(context)
    val entries = load(context).associateByTo(linkedMapOf(), TrustTunnelCatalogEntry::id)
    profiles.forEach { profile ->
      val id = stableId(profile.displayName)
      entries[id] = TrustTunnelCatalogEntry(id, profile.displayName, profile.config)
    }
    val result = entries.values.sortedBy { it.name.lowercase() }
    SecureProfileStore.save(
      context,
      SecureProfileStore.TRUST_TUNNEL_CATALOG,
      encode(result),
    )
    return result
  }

  fun removeWhere(
    context: Context,
    predicate: (TrustTunnelCatalogEntry) -> Boolean,
  ): List<TrustTunnelCatalogEntry> {
    val result = load(context).filterNot(predicate)
    SecureProfileStore.save(
      context,
      SecureProfileStore.TRUST_TUNNEL_CATALOG,
      encode(result),
    )
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
      .put("version", 1)
      .put(
        "profiles",
        JSONArray().apply {
          profiles.forEach { profile ->
            put(
              JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("config", profile.config),
            )
          }
        },
      )
      .toString()

  internal fun decode(value: String): List<TrustTunnelCatalogEntry> {
    val root = JSONObject(value)
    require(root.getInt("version") == 1) { "Версия каталога TrustTunnel не поддерживается" }
    val profiles = root.getJSONArray("profiles")
    return buildList {
      repeat(profiles.length()) { index ->
        val item = profiles.getJSONObject(index)
        val name = item.getString("name").trim().take(80)
        val config = item.getString("config")
        require(name.isNotBlank() && config.isNotBlank()) {
          "Повреждён профиль TrustTunnel"
        }
        add(
          TrustTunnelCatalogEntry(
            id = item.getString("id"),
            name = name,
            config = config,
          ),
        )
      }
    }
  }

  private fun stableId(name: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(name.trim().lowercase().toByteArray(Charsets.UTF_8))
    return digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
