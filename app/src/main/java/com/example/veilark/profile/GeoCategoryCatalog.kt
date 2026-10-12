package com.example.veilark.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class GeoCategory(val id: String, val label: String, val kind: String)

/** Explicit downloads from the official MetaCubeX public GEO rule-set publisher (no HAPP data).
 * Catalog snapshots bind an immutable Git tree, blob hash and bounded asset size.
 * Existing files remain available offline; incomplete updates never replace them. */
class GeoCategoryCatalog(filesDir: File) {
  private val root = File(filesDir, "geo-categories")
  private val catalog = File(root, "catalog.json")
  private val prepared = File(root, "prepared.json")
  private data class Entry(val category: GeoCategory, val revision: String, val blob: String, val size: Int)

  fun load(): List<GeoCategory> = entries().map { it.category }

  suspend fun refresh(): List<GeoCategory> = withContext(Dispatchers.IO) {
    val reference = JSONObject(String(fetch("https://api.github.com/repos/MetaCubeX/meta-rules-dat/git/ref/heads/sing", 16384), Charsets.UTF_8)).getJSONObject("object")
    require(reference.getString("type") == "commit")
    val revision = reference.getString("sha").also { require(HASH.matches(it)) }
    val treeRoot = JSONObject(String(fetch("https://api.github.com/repos/MetaCubeX/meta-rules-dat/git/trees/$revision", 4 * 1024 * 1024), Charsets.UTF_8))
    fun child(tree: JSONObject, name: String): JSONObject {
      val files = tree.getJSONArray("tree")
      val sha = (0 until files.length()).map { files.getJSONObject(it) }.single { it.getString("path") == name && it.getString("type") == "tree" }.getString("sha")
      require(HASH.matches(sha))
      return JSONObject(String(fetch("https://api.github.com/repos/MetaCubeX/meta-rules-dat/git/trees/$sha", 4 * 1024 * 1024), Charsets.UTF_8))
    }
    val geo = child(treeRoot, "geo")
    val all = listOf("geosite", "geoip").flatMap { kind ->
      parseTree(kind, child(geo, kind), revision).map { entry ->
        JSONObject().put("id", entry.category.id).put("revision", entry.revision).put("blob", entry.blob).put("size", entry.size)
      }
    }
    require(all.isNotEmpty()) { "Каталог GEO пуст" }
    atomic(catalog, JSONObject().put("version", 1).put("entries", JSONArray(all)).toString().toByteArray())
    load()
  }

  /** Download only selected categories. Call before applying a scenario. */
  suspend fun prepare(directEntries: String, vpnEntries: String): Map<String, String> = withContext(Dispatchers.IO) {
    val selected = selected(directEntries, vpnEntries)
    if (selected.isEmpty()) return@withContext emptyMap()
    val available = entries().associateBy { it.category.id }
    val paths = selected.associateWith { id ->
      val entry = available[id] ?: error("Категория $id отсутствует: сначала загрузите каталог GEO")
      val target = file(entry)
      if (!valid(target, entry)) {
        val bytes = fetch("https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/${entry.revision}/geo/${entry.category.kind}/${id.substringAfter(':')}.json", MAX_ASSET)
        require(validBytes(bytes, entry)) { "Проверка файла GEO не пройдена" }
        atomic(target, bytes)
      }
      target.absolutePath
    }
    if (selected.isNotEmpty()) {
      val checked = (entries(prepared).associateBy { it.category.id } + selected.associateWith { available.getValue(it) }).values
      atomic(prepared, encode(checked))
    }
    paths
  }

  /** No network on VPN start. A selected missing/corrupt asset fails closed. */
  fun localPaths(directEntries: String, vpnEntries: String): Map<String, String> {
    val selected = selected(directEntries, vpnEntries)
    if (selected.isEmpty()) return emptyMap()
    val knownGood = entries(prepared).associateBy { it.category.id }
    val available = if (selected.all { it in knownGood }) knownGood else entries().associateBy { it.category.id } + knownGood
    return selected.associateWith { id ->
      val entry = available[id] ?: error("Категория $id отсутствует в локальном каталоге GEO")
      val target = file(entry)
      require(valid(target, entry)) { "Категория $id не загружена; примените сценарий с доступом к сети" }
      target.absolutePath
    }
  }

  private fun encode(entries: Collection<Entry>): ByteArray = JSONObject().put("version", 1).put("entries", JSONArray(entries.map { entry ->
    JSONObject().put("id", entry.category.id).put("revision", entry.revision).put("blob", entry.blob).put("size", entry.size)
  })).toString().toByteArray()

  private fun entries(source: File = catalog): List<Entry> {
    if (!source.exists()) return emptyList()
    require(source.length() <= 4 * 1024 * 1024) { "Каталог GEO повреждён" }
    val json = JSONObject(source.readText())
    require(json.getInt("version") == 1)
    val array = json.getJSONArray("entries")
    require(array.length() in 1..10000)
    val result = (0 until array.length()).map { index ->
      val item = array.getJSONObject(index)
      val id = item.getString("id")
      require(isCategory(id))
      val revision = item.getString("revision").also { require(HASH.matches(it)) }
      val blob = item.getString("blob").also { require(HASH.matches(it)) }
      val size = item.getInt("size").also { require(it in 4..MAX_ASSET) }
      Entry(GeoCategory(id, id.substringAfter(':'), id.substringBefore(':')), revision, blob, size)
    }
    require(result.map { it.category.id }.distinct().size == result.size)
    return result.sortedBy { it.category.id }
  }

  private fun file(entry: Entry) = File(root, "${entry.blob}-${filename(entry.category.id)}")
  private fun valid(file: File, entry: Entry) = file.isFile && file.length() == entry.size.toLong() && validBytes(file.readBytes(), entry)
  private fun validBytes(bytes: ByteArray, entry: Entry) = bytes.size == entry.size &&
    gitBlobHash(bytes) == entry.blob && runCatching { validateSource(String(bytes, Charsets.UTF_8)); true }.getOrDefault(false)

  private fun fetch(url: String, limit: Int): ByteArray {
    val connection = java.net.URI(url).toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 20000
    connection.instanceFollowRedirects = false
    connection.setRequestProperty("User-Agent", "Veilark-GEO")
    try {
      require(connection.responseCode == 200) { "Не удалось загрузить GEO (HTTP ${connection.responseCode})" }
      require(connection.contentLengthLong <= limit) { "Файл GEO слишком большой" }
      return connection.inputStream.use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
          val count = stream.read(buffer)
          if (count < 0) break
          require(output.size() + count <= limit) { "Файл GEO слишком большой" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
    } finally { connection.disconnect() }
  }

  private fun atomic(target: File, bytes: ByteArray) {
    check(root.isDirectory || root.mkdirs())
    val temporary = File.createTempFile(".geo-", ".tmp", root)
    try {
      java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
      Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
  }

  fun trustDirectExclusions(direct: String, vpn: String): List<String> {
    val directParsed = ProfileSelection.routingEntries(direct)
    val vpnParsed = ProfileSelection.routingEntries(vpn)
    // TrustTunnel exclusions cannot express ordered VPN overrides over a broad
    // category. Refuse ambiguous combinations instead of silently bypassing.
    require(vpnParsed.categories.isEmpty()) { "Категории VPN требуют sing-box; TrustTunnel поддерживает GEO-категории только напрямую" }
    if (directParsed.categories.isNotEmpty()) require(vpn.isBlank()) { "GEO напрямую вместе с исключениями VPN требует sing-box" }
    val paths = localPaths(direct, vpn)
    return directParsed.categories.flatMap { id -> trustRules(File(requireNotNull(paths[id])).readText()) }.distinct()
  }

  companion object {
    internal fun validateSource(text: String): JSONObject {
      CustomGeoProfile.requireBoundedDepth(text)
      val root = JSONObject(text)
      require(root.optInt("version") in 1..3 && root.optJSONArray("rules") != null) { "Некорректный GEO rule-set" }
      return root
    }
    internal fun trustRules(text: String): List<String> {
      val rules = validateSource(text).getJSONArray("rules")
      val result = linkedSetOf<String>()
      require(rules.length() <= 10000)
      repeat(rules.length()) { i ->
        val rule = rules.getJSONObject(i)
        require(rule.keys().asSequence().all { it in setOf("domain", "domain_suffix", "ip_cidr") }) { "Эта категория содержит regex/keyword/логические правила: используйте sing-box" }
        for (field in listOf("domain", "domain_suffix", "ip_cidr")) {
          val values = rule.optJSONArray(field) ?: continue
          require(values.length() <= 200000)
          repeat(values.length()) { j ->
            val raw = values.getString(j)
            val normalized = ProfileSelection.routingEntries(raw)
            require(normalized.categories.isEmpty())
            if (field == "ip_cidr") {
              require(normalized.networks.size == 1 && normalized.domains.isEmpty())
              result += normalized.networks.single()
            } else {
              require(normalized.domains.size == 1 && normalized.networks.isEmpty())
              val domain = normalized.domains.single()
              result += domain
              if (field == "domain_suffix") result += "*.$domain"
            }
            require(result.size <= 200000) { "Категория превышает лимит правил TrustTunnel" }
          }
        }
      }
      require(result.isNotEmpty()) { "Категория GEO пуста" }
      return result.toList()
    }
    private const val MAX_ASSET = 32 * 1024 * 1024
    private val HASH = Regex("[0-9a-f]{40}")
    private val TOKEN = Regex("(geosite|geoip):[a-z0-9][a-z0-9@!_.-]{0,127}")
    fun isCategory(value: String): Boolean = TOKEN.matches(value)
    fun selected(direct: String, vpn: String): Set<String> =
      (ProfileSelection.routingEntries(direct).categories + ProfileSelection.routingEntries(vpn).categories).toSet()
    internal fun gitBlobHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
      update("blob ${bytes.size}\u0000".toByteArray()); digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun filename(id: String) = id.replace(':', '-') + ".json"
    private fun parseTree(kind: String, tree: JSONObject, revision: String): List<Entry> {
      require(!tree.optBoolean("truncated", true)) { "Каталог GEO усечён" }
      val files = tree.getJSONArray("tree")
      require(files.length() in 1..10000)
      return (0 until files.length()).mapNotNull { index ->
        val file = files.getJSONObject(index)
        val path = file.getString("path")
        if (file.optString("type") != "blob" || !path.endsWith(".json")) return@mapNotNull null
        val id = "$kind:${path.removeSuffix(".json")}"
        require(isCategory(id)) { "Некорректное имя категории GEO" }
        val blob = file.getString("sha").also { require(HASH.matches(it)) }
        val size = file.getInt("size").also { require(it in 4..MAX_ASSET) }
        Entry(GeoCategory(id, id.substringAfter(':'), kind), revision, blob, size)
      }
    }
  }
}
