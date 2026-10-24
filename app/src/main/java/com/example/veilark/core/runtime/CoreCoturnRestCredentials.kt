package com.example.veilark.core.runtime

import com.example.veilark.profile.CoreArgonRelayProfile

/**
 * Credentials for our own coturn relay, run with `use-auth-secret`.
 *
 * coturn's REST scheme accepts any username of the form `<unix expiry>:<label>`
 * whose password is base64(HMAC-SHA1(secret, username)). The server mints that
 * pair when it signs the Passage profile, so the phone carries a credential that
 * stops working on its own at the expiry, the shared secret never leaves the
 * relay host, and rotating the secret revokes every issued profile at once.
 * Nothing is fetched at connect time: under a whitelist our own servers may be
 * unreachable except for the relay itself.
 *
 * The expiry is wall-clock time while the grant runs on the monotonic clock the
 * caller passes in, so the remaining lifetime is measured here and handed over.
 */
internal class CoreCoturnRestCredentials(
  private val wallClockMillis: () -> Long = System::currentTimeMillis,
) : CoreRelayCredentialAdapter {
  override val service = SERVICE

  override suspend fun fetch(
    relay: CoreArgonRelayProfile.Relay,
    parameters: Map<String, String>,
    binding: CoreFairwayNetworkBinding,
    nowMillis: Long,
  ): CoreTurnGrant {
    require(parameters.keys == setOf("username", "password")) { ERROR }
    val username = parameters.getValue("username")
    val expiry = username.substringBefore(':', "").takeIf { it.matches(DIGITS) }?.toLongOrNull()
    requireNotNull(expiry) { ERROR }
    val remaining = expiry - wallClockMillis() / 1000
    require(remaining > 0) { ERROR }
    val user = username.toByteArray(Charsets.US_ASCII)
    val password = parameters.getValue("password").toByteArray(Charsets.US_ASCII)
    try {
      // The core takes at most a day per handle; a longer credential is simply reused on reconnect.
      return CoreTurnGrant.create(relay.uri, user, password, remaining.coerceAtMost(MAX_GRANT_SECONDS), nowMillis)
    } finally { user.fill(0); password.fill(0) }
  }

  companion object {
    const val SERVICE = "coturn-rest"
    private const val ERROR = "TURN credentials unavailable."
    private const val MAX_GRANT_SECONDS = 86_400L
    private val DIGITS = Regex("[1-9][0-9]{0,11}")
  }
}
