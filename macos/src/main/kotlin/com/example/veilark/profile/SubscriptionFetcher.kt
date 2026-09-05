package com.example.veilark.profile

import com.example.veilark.io.readAtMost
import com.example.veilark.update.MacUpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.util.Base64
import java.util.prefs.Preferences
import javax.net.ssl.SSLException

object SubscriptionFetcher {
  suspend fun fetch(
    source: String,
    headers: Map<String, String> = desktopHeaders(source),
  ): ByteArray = fetchWithConnection(source, headers) { URL(it.toString()).openConnection() as HttpURLConnection }

  internal suspend fun fetchWithConnection(
    source: String,
    headers: Map<String, String>,
    openConnection: (URI) -> HttpURLConnection,
  ): ByteArray = withContext(Dispatchers.IO) {
    var current = runCatching { URI(source.trim()) }
      .getOrElse { throw IllegalArgumentException("Адрес подписки некорректен", it) }
    repeat(MAX_REDIRECTS + 1) { redirect ->
      require(current.scheme.equals("https", ignoreCase = true)) {
        "Разрешены только HTTPS-подписки"
      }
      require(!current.host.isNullOrBlank()) { "Адрес подписки некорректен" }
      val connection = openConnection(current)
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.setRequestProperty(
          "User-Agent",
          "SFA/1.13.21 Veilark/${MacUpdateClient.CURRENT_VERSION}-macos",
        )
        connection.setRequestProperty("X-Client", "Veilark")
        SubscriptionClientObservation.headersForHop(headers, redirect).forEach(connection::setRequestProperty)
        connection.setRequestProperty(
          "Accept",
          "application/json, text/yaml, application/yaml, text/plain, " +
            "application/octet-stream, */*",
        )
        connection.setRequestProperty("Accept-Encoding", "identity")
        val responseCode = try {
          connection.responseCode
        } catch (failure: Exception) {
          throw classifyNetworkFailure(failure)
        }
        when (responseCode) {
          in 200..299 -> {
            require(
              !connection.getHeaderField("x-hwid-max-devices-reached")
                .equals("true", ignoreCase = true) &&
                !connection.getHeaderField("x-hwid-limit").equals("true", ignoreCase = true),
            ) {
              "Лимит устройств этой подписки исчерпан"
            }
            val body = try {
              connection.inputStream.use { it.readAtMost(MAX_BYTES + 1) }
            } catch (failure: Exception) {
              throw classifyNetworkFailure(failure)
            }
            return@withContext validateBody(body, connection.contentType.orEmpty())
          }
          in 300..399 -> {
            require(redirect < MAX_REDIRECTS) { "Слишком много перенаправлений" }
            val location = connection.getHeaderField("Location")
              ?: error("Перенаправление без адреса")
            val redirected = current.resolve(location)
            require(redirected.scheme.equals("https", ignoreCase = true)) {
              "Сервер перенаправил подписку на небезопасный HTTP-адрес"
            }
            current = redirected
          }
          401 -> error("Сервер подписки требует авторизацию (HTTP 401)")
          403 -> error("Доступ к подписке запрещён или исчерпан лимит устройств (HTTP 403)")
          404 -> error("Ссылка подписки не найдена или устарела (HTTP 404)")
          409 -> error("Лимит устройств подписки исчерпан (HTTP 409)")
          410 -> error("Подписка удалена или истекла (HTTP 410)")
          503 -> error("Подписка ещё подготавливается. Повторите обновление немного позже (HTTP 503)")
          429 -> error("Слишком много запросов к подписке (HTTP 429)")
          else -> error("Сервер подписки ответил HTTP $responseCode")
        }
      } finally {
        connection.disconnect()
      }
    }
    error("Не удалось загрузить подписку")
  }

  internal fun validateBody(body: ByteArray, contentType: String): ByteArray {
    require(body.size <= MAX_BYTES) { "Подписка больше 4 МБ" }
    require(body.isNotEmpty()) { "Сервер вернул пустую подписку" }
    val prefix = String(
      body,
      0,
      minOf(body.size, HTML_PROBE_BYTES),
      Charsets.UTF_8,
    ).trimStart().lowercase()
    require(
      "text/html" !in contentType.lowercase() &&
        !prefix.startsWith("<!doctype html") &&
        !prefix.startsWith("<html"),
    ) {
      "Сервер вернул веб-страницу вместо подписки"
    }
    return body
  }

  fun desktopHeaders(source: String): Map<String, String> = deviceHeaders(source, SubscriptionClientIdentity::id)

  internal fun deviceHeaders(source: String, identity: () -> String): Map<String, String> {
    if (!SubscriptionClientObservation.isControlled(source, "sub.senyasenyavski.uk", 2096)) return emptyMap()
    val model = when (System.getProperty("os.arch", "").lowercase()) {
      "aarch64", "arm64" -> "Apple Silicon Mac"
      "x86_64", "amd64" -> "Intel Mac"
      else -> "Mac"
    }
    return mapOf(
      "X-Veilark-Install-Id" to identity(),
      "X-Veilark-Device-Label" to model,
      "X-Veilark-Device-Model" to model,
      "X-Veilark-Platform" to "macos",
      "X-Veilark-App-Version" to MacUpdateClient.CURRENT_VERSION,
    )
  }

  private fun classifyNetworkFailure(failure: Exception): IllegalStateException {
    val message = when (failure) {
      is UnknownHostException -> "Не найден сервер подписки (ошибка DNS)"
      is SocketTimeoutException -> "Сервер подписки не ответил вовремя"
      is SSLException -> "Не удалось проверить TLS-сертификат сервера подписки"
      is ConnectException -> "Не удалось подключиться к серверу подписки"
      else -> "Ошибка загрузки подписки: ${failure.javaClass.simpleName}"
    }
    return IllegalStateException(message, failure)
  }

  private const val MAX_REDIRECTS = 3
  private const val MAX_BYTES = 4 * 1024 * 1024
  private const val HTML_PROBE_BYTES = 2_048
}

private object SubscriptionClientIdentity {
  private const val KEY = "install_id"

  fun id(): String {
    val preferences = Preferences.userNodeForPackage(SubscriptionFetcher::class.java)
    return SubscriptionClientObservation.installationId(
      current = { preferences.get(KEY, null) },
      legacy = { preferences.get("hwid", null) },
      persist = { preferences.put(KEY, it); preferences.flush(); true },
      generate = { Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(java.security.SecureRandom()::nextBytes)) },
    )
  }
}
