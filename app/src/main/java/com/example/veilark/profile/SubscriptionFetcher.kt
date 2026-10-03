package com.example.veilark.profile

import android.content.Context
import android.os.Build
import android.util.Base64
import com.example.veilark.BuildConfig
import com.example.veilark.io.readAtMost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import javax.net.ssl.SSLException

object SubscriptionFetcher {
  suspend fun fetch(
    source: String,
    headers: Map<String, String> = emptyMap(),
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
        // Remnawave negotiates the response family by User-Agent. SFA requests native
        // sing-box JSON; panels without negotiation normally fall back to URI/base64.
        connection.setRequestProperty(
          "User-Agent",
          "SFA/1.13.21 Veilark/${BuildConfig.VERSION_NAME}",
        )
        connection.setRequestProperty("X-Client", "Veilark")
        headersForHop(headers, redirect).forEach(connection::setRequestProperty)
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

  private const val MAX_REDIRECTS = 3
  private const val MAX_BYTES = 4 * 1024 * 1024
  private const val HTML_PROBE_BYTES = 2_048

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

  fun androidHeaders(context: Context, source: String): Map<String, String> = deviceHeaders(
    source, BuildConfig.SUBSCRIPTION_HOST, BuildConfig.SUBSCRIPTION_PORT,
    "${Build.MANUFACTURER} ${Build.MODEL}", BuildConfig.VERSION_NAME,
  ) { SubscriptionClientIdentity.id(context) }

  internal fun deviceHeaders(
    source: String,
    configuredHost: String,
    configuredPort: Int,
    deviceModel: String,
    appVersion: String,
    identity: () -> String,
  ): Map<String, String> {
    if (!isVeilarkControlledEndpoint(
        source,
        configuredHost,
        configuredPort,
      )
    ) {
      return emptyMap()
    }
    val model = asciiDeviceValue(deviceModel, "Android device")
    return mapOf(
      "X-Veilark-Install-Id" to identity(),
      "X-Veilark-Device-Label" to asciiDeviceValue("Android $model", "Android device"),
      "X-Veilark-Device-Model" to model,
      "X-Veilark-Platform" to "android",
      "X-Veilark-App-Version" to asciiDeviceValue(appVersion, "unknown"),
    )
  }

  internal fun isVeilarkControlledEndpoint(
    source: String,
    configuredHost: String,
    configuredPort: Int,
  ): Boolean = SubscriptionClientObservation.isControlled(source, configuredHost, configuredPort)

  private fun asciiDeviceValue(value: String, fallback: String): String =
    SubscriptionClientObservation.ascii(value, fallback)

  /** Observation headers identify one app install and must never cross a redirect boundary. */
  internal fun headersForHop(headers: Map<String, String>, redirect: Int): Map<String, String> =
    SubscriptionClientObservation.headersForHop(headers, redirect)
}

private object SubscriptionClientIdentity {
  private const val PREFERENCES = "subscription_client"
  private const val KEY = "install_id"
  private const val LEGACY_KEY = "hwid"

  fun id(context: Context): String {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    return SubscriptionClientObservation.installationId(
      current = { preferences.getString(KEY, null) },
      legacy = { preferences.getString(LEGACY_KEY, null) },
      persist = { preferences.edit().putString(KEY, it).commit() },
      generate = {
        val random = ByteArray(18).also(java.security.SecureRandom()::nextBytes)
        Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
      },
    )
  }
}
