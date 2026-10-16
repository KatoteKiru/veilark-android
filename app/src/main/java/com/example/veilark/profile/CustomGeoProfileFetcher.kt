package com.example.veilark.profile

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Public GitHub JSON/text data only; no redirects or repository code execution. */
object CustomGeoProfileFetcher {
  fun canonicalUrl(input: String): URI {
    val uri = try { URI(input.trim()) } catch (_: Exception) { throw IllegalArgumentException("Некорректная ссылка GitHub") }
    require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Нужна публичная HTTPS-ссылка GitHub без параметров" }
    require(uri.host in setOf("github.com", "raw.githubusercontent.com")) { "Поддерживаются только публичные файлы правил GitHub" }
    val path = uri.rawPath ?: ""
    require((path.endsWith(".json") || path.endsWith(".txt")) && !path.contains('%') && !path.contains('\\') && !path.contains("//")) { "Выберите JSON-профиль или текстовый список .txt на GitHub" }
    val parts = path.removePrefix("/").split('/')
    require(parts.none { it.isBlank() || it == "." || it == ".." } && parts.take(2).all { it.matches(Regex("[A-Za-z0-9_.-]+")) }) { "Некорректный путь GitHub" }
    val raw = if (uri.host == "github.com") {
      require(parts.size >= 5 && parts[2] == "blob") { "Нужна ссылка на JSON-файл (GitHub blob)" }
      parts.take(2) + parts.drop(3)
    } else {
      require(parts.size >= 4) { "Нужна ссылка на JSON-файл GitHub raw" }
      parts
    }
    return URI("https://raw.githubusercontent.com/" + raw.joinToString("/"))
  }

  /** Run off the main thread. Transport errors deliberately omit URLs and response bodies. */
  fun fetch(input: String): CustomGeoProfile = fetchWithConnection(input) {
    it.toURL().openConnection() as HttpURLConnection
  }

  internal fun fetchWithConnection(input: String, openConnection: (URI) -> HttpURLConnection): CustomGeoProfile {
    require(canonicalUrl(input).path.endsWith(".json")) { "Для профиля выберите JSON; текстовый список загрузите как правила" }
    return CustomGeoProfile.parse(fetchText(input, openConnection))
  }

  fun fetchRules(input: String): String = fetchRulesWithConnection(input) {
    it.toURL().openConnection() as HttpURLConnection
  }

  internal fun fetchRulesWithConnection(input: String, openConnection: (URI) -> HttpURLConnection): String {
    require(canonicalUrl(input).path.endsWith(".txt")) { "Для списка доменов/IP выберите текстовый файл .txt" }
    val lines = fetchText(input, openConnection).lineSequence()
      .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
    require(lines.all { line -> line.none { it.isWhitespace() || it == ',' || it == ';' } }) {
      "Каждая строка .txt должна содержать один домен или IP/CIDR; команды и списки не поддерживаются"
    }
    val text = lines.joinToString("\n")
    return CustomGeoProfile.create("Rules", text, "").directEntries
  }

  private fun fetchText(input: String, openConnection: (URI) -> HttpURLConnection): String {
    val url = canonicalUrl(input)
    val connection = try { openConnection(url) } catch (_: Exception) {
      throw IllegalArgumentException("Не удалось загрузить GEO-профиль из GitHub")
    }
    try {
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.setRequestProperty("Accept", "application/json, text/plain")
      connection.setRequestProperty("Accept-Encoding", "identity")
      connection.setRequestProperty("Cookie", "")
      require(connection.responseCode == 200) { "GitHub не вернул файл правил; редиректы и закрытые репозитории не поддерживаются" }
      require(connection.contentLengthLong <= CustomGeoProfile.MAX_BYTES) { "Профиль превышает 256 КБ" }
      val deadline = System.nanoTime() + 20_000_000_000L
      val bytes = connection.inputStream.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
          require(System.nanoTime() <= deadline) { "Истекло время загрузки профиля" }
          val count = stream.read(buffer)
          if (count < 0) break
          require(output.size() + count <= CustomGeoProfile.MAX_BYTES) { "Профиль превышает 256 КБ" }
          output.write(buffer, 0, count)
        }
        output.toByteArray()
      }
      return CustomGeoProfile.decodeUtf8(bytes)
    } catch (error: IllegalArgumentException) { throw error
    } catch (_: Exception) { throw IllegalArgumentException("Не удалось загрузить GEO-профиль из GitHub")
    } finally { connection.disconnect() }
  }
}
