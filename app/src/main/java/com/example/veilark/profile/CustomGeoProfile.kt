package com.example.veilark.profile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Data-only suffix-domain/IP rules. Parsing and saving do not change active routing. */
@ConsistentCopyVisibility
data class CustomGeoProfile private constructor(
  val name: String,
  val directEntries: String,
  val vpnEntries: String,
) {
  fun toJson(): String = JSONObject().put("schema", SCHEMA).put("name", name)
    .put("direct", JSONArray(directEntries.lines().filter(String::isNotBlank)))
    .put("vpn", JSONArray(vpnEntries.lines().filter(String::isNotBlank))).toString()

  companion object {
    const val SCHEMA = "veilark-custom-geo-v1"
    const val MAX_BYTES = 256 * 1024
    private const val MAX_RULES = 2000

    fun parse(input: String): CustomGeoProfile {
      require(input.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Профиль слишком большой (максимум 256 КБ)" }
      val text = input.trim()
      requireBoundedDepth(text)
      val root = try {
        val tokener = JSONTokener(text)
        val result = tokener.nextValue() as? JSONObject ?: throw IllegalArgumentException()
        require(tokener.nextClean() == '\u0000')
        result
      } catch (_: Exception) {
        throw IllegalArgumentException("Профиль должен содержать корректный JSON")
      }
      require(root.opt("schema") == SCHEMA) { "Неизвестный формат GEO-профиля" }
      requireKeys(root, setOf("schema", "name", "direct", "vpn"))
      require(root.has("direct") && root.has("vpn")) { "Профиль должен содержать списки direct и vpn" }
      return create(readName(root, "name"), readRules(root, "direct").joinToString("\n"), readRules(root, "vpn").joinToString("\n"))
    }

    fun create(name: String, directEntries: String, vpnEntries: String): CustomGeoProfile {
      val cleanName = name.trim()
      require(cleanName.length in 1..80 && name.none(Char::isISOControl)) { "Название должно содержать 1–80 символов без управляющих знаков" }
      require(directEntries.length + vpnEntries.length <= MAX_BYTES) { "Слишком много правил" }
      val direct = normalize(directEntries)
      val vpn = normalize(vpnEntries)
      require(direct.size + vpn.size in 1..MAX_RULES) { "Добавьте от 1 до 2000 правил" }
      require(direct.toSet().intersect(vpn.toSet()).isEmpty()) { "Одно правило указано одновременно для direct и VPN" }
      val profile = CustomGeoProfile(cleanName, direct.joinToString("\n"), vpn.joinToString("\n"))
      require(profile.toJson().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Профиль слишком большой (максимум 256 КБ)" }
      return profile
    }

    internal fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()

    private fun readName(root: JSONObject, key: String): String {
      require(root.opt(key) is String) { "В профиле отсутствует название" }
      return root.getString(key)
    }

    internal fun requireBoundedDepth(text: String) {
      require('\u0000' !in text) { "GEO-профиль содержит недопустимый нулевой символ" }
      var depth = 0
      var quoted = false
      var escaped = false
      for (character in text) {
        if (quoted) {
          if (escaped) escaped = false
          else if (character == '\\') escaped = true
          else if (character == '"') quoted = false
        } else when (character) {
          '"' -> quoted = true
          '{', '[' -> { depth++; require(depth <= 16) { "Слишком сложная структура GEO-профиля" } }
          '}', ']' -> depth--
        }
      }
    }

    private fun requireKeys(root: JSONObject, allowed: Set<String>) {
      require(root.keys().asSequence().all { it in allowed }) { "Профиль содержит неподдерживаемые поля; изменения не применены" }
    }

    private fun readRules(root: JSONObject, key: String): List<String> {
      if (!root.has(key)) return emptyList()
      val array = root.optJSONArray(key) ?: throw IllegalArgumentException("Поле $key должно быть списком правил")
      require(array.length() <= MAX_RULES) { "Слишком много правил" }
      return (0 until array.length()).map {
        require(array.get(it) is String) { "Правила должны быть строками" }
        val rule = array.getString(it)
        require(rule.isNotBlank() && rule.none { c -> c.isWhitespace() || c == ',' || c == ';' }) { "Каждый элемент списка должен содержать одно правило" }
        rule
      }
    }

    private fun normalize(input: String): List<String> = input.split(Regex("[\\s,;]+")).filter(String::isNotBlank).map { raw ->
      require(raw.length <= 260) { "Слишком длинное правило" }
      if (GeoCategoryCatalog.isCategory(raw)) return@map raw
      require(!raw.contains(":") || raw.substringBefore('/').matches(Regex("[0-9a-fA-F:]+"))) {
        "Используйте домены, IP/CIDR или категории geosite:/geoip: из каталога"
      }
      require(!raw.contains('/') || raw.substringAfter('/').toIntOrNull() != null) { "Используйте домен или IP/CIDR, без URL" }
      if (raw.contains(':')) {
        require(runCatching { InetAddress.getByName(raw.substringBefore('/')).address.size == 16 }.getOrDefault(false)) { "Некорректный IPv6" }
      }
      try {
        val entries = ProfileSelection.routingEntries(raw)
        (entries.domains + entries.networks).single()
      } catch (_: Exception) { throw IllegalArgumentException("Некорректное правило маршрутизации") }
    }.distinct()
  }
}
