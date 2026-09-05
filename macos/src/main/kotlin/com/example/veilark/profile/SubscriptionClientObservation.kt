package com.example.veilark.profile

import java.net.URI

/** Privacy-scoped metadata for a random app installation, not a hardware identifier. */
internal object SubscriptionClientObservation {
  private val INSTALL_ID = Regex("^[A-Za-z0-9_-]{24}$")
  private val PATH = Regex("^/(?:managed|trust)/[A-Za-z0-9_-]{16,64}$")
  private var pendingPersistenceId: String? = null

  fun isControlled(source: String, host: String, port: Int): Boolean {
    val uri = runCatching { URI(source.trim()) }.getOrNull() ?: return false
    val effectivePort = if (uri.port == -1) 443 else uri.port
    val expectedPort = if (port == -1) 443 else port
    return uri.scheme.equals("https", true) && uri.userInfo == null &&
      uri.rawQuery == null && uri.rawFragment == null && host.isNotBlank() &&
      uri.host.equals(host.trim(), true) && effectivePort == expectedPort &&
      PATH.matches(uri.rawPath.orEmpty())
  }

  fun headersForHop(headers: Map<String, String>, redirect: Int): Map<String, String> =
    if (redirect == 0) headers
    else headers.filterKeys { !it.startsWith("X-Veilark-", ignoreCase = true) }

  fun ascii(value: String, fallback: String): String = value.trim()
    .replace(Regex("[^A-Za-z0-9 ._-]"), "")
    .replace(Regex("\\s+"), " ").trim().take(64).ifBlank { fallback }

  @Synchronized
  fun installationId(
    current: () -> String?,
    legacy: () -> String? = { null },
    persist: (String) -> Boolean,
    generate: () -> String,
  ): String {
    val stored = current()
    val selected = stored?.takeIf(INSTALL_ID::matches)
      ?: legacy()?.takeIf(INSTALL_ID::matches)
      ?: generate().also { require(INSTALL_ID.matches(it)) }
    if (selected != stored || pendingPersistenceId == selected) {
      // Preferences can update their memory cache before commit/flush fails.
      // Retry durability before returning that same cached value next time.
      pendingPersistenceId = selected
      check(persist(selected)) { "Cannot persist subscription installation identity" }
      pendingPersistenceId = null
    }
    return selected
  }
}
