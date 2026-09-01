package com.example.veilark.profile

import java.net.URI
import java.security.MessageDigest

enum class SubscriptionKind(val wireName: String) {
  SING_BOX("sing-box"),
  TRUST_TUNNEL("trust-tunnel"),
}

enum class SubscriptionOrigin(val wireName: String) {
  BUILT_IN("built-in"),
  REMOTE("remote"),
  MANUAL("manual"),
  LEGACY("legacy");

  companion object {
    fun fromWireName(value: String?): SubscriptionOrigin =
      entries.firstOrNull { it.wireName == value } ?: LEGACY
  }
}

/** Stable, non-secret identities used to keep independent subscription sources isolated. */
object SubscriptionIdentity {
  fun sourceId(
    kind: SubscriptionKind,
    sourceUrl: String?,
    localIdentity: String,
  ): String {
    val identity = sourceUrl
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.let(::normalizeUrl)
      ?: localIdentity.trim()
    require(identity.isNotEmpty()) { "Источник подписки пуст" }
    return "${kind.wireName}-${digest(identity).take(20)}"
  }

  fun nodeFingerprint(kind: SubscriptionKind, identity: String): String {
    require(identity.isNotBlank()) { "Профиль узла пуст" }
    return "${kind.wireName}-${digest(identity.trim()).take(24)}"
  }

  fun entryId(sourceId: String, nodeFingerprint: String): String =
    digest("$sourceId\n$nodeFingerprint").take(20)

  internal fun normalizeUrl(value: String): String = runCatching {
    val uri = URI(value.trim())
    require(uri.scheme.equals("https", ignoreCase = true)) {
      "Удалённая подписка должна использовать HTTPS"
    }
    require(!uri.host.isNullOrBlank()) { "В ссылке подписки отсутствует сервер" }
    require(uri.userInfo == null) {
      "Ссылка подписки не должна содержать имя пользователя или пароль"
    }
    URI(
      uri.scheme.lowercase(),
      uri.userInfo,
      uri.host.lowercase(),
      uri.port,
      uri.path.ifBlank { "/" },
      uri.query,
      null,
    ).toASCIIString()
  }.getOrElse { failure ->
    throw IllegalArgumentException(
      failure.message ?: "Некорректная ссылка подписки",
      failure,
    )
  }

  private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
