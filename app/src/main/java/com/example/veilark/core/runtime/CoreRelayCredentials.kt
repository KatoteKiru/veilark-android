package com.example.veilark.core.runtime

import com.example.veilark.profile.CoreArgonRelayProfile

/**
 * Obtains short-lived TURN credentials for a Veilark Passage relay on the phone,
 * from the legitimate service that runs the relay. Under a whitelist that service
 * is reachable while our control plane may not be, so nothing here calls Veilark
 * servers. Each service has one adapter; the core stays service-agnostic and only
 * receives the resulting username and password through begin_v3.
 */
internal interface CoreRelayCredentialAdapter {
  /** Matches [CoreArgonRelayProfile.Credential.Service.service]. */
  val service: String

  /**
   * Requests credentials for [relay] over [binding], the captured physical
   * network, never the VPN. The grant is used once and closed by the caller.
   */
  suspend fun fetch(
    relay: CoreArgonRelayProfile.Relay,
    parameters: Map<String, String>,
    binding: CoreFairwayNetworkBinding,
    nowMillis: Long,
  ): CoreTurnGrant
}

internal class CoreRelayCredentials(adapters: List<CoreRelayCredentialAdapter>) {
  private val byService = adapters.associateBy { it.service }.also {
    require(it.size == adapters.size) { "Duplicate relay credential adapter" }
  }

  /** Whether this build can obtain credentials for [relay]; unsupported relays are left out of the race. */
  fun supports(relay: CoreArgonRelayProfile.Relay, labBuild: Boolean): Boolean = when (val credential = relay.credential) {
    is CoreArgonRelayProfile.Credential.Service -> credential.service in byService
    is CoreArgonRelayProfile.Credential.Static -> labBuild
  }

  suspend fun grant(
    relay: CoreArgonRelayProfile.Relay, binding: CoreFairwayNetworkBinding, nowMillis: Long,
  ): CoreTurnGrant = when (val credential = relay.credential) {
    is CoreArgonRelayProfile.Credential.Service ->
      checkNotNull(byService[credential.service]) { "No credential adapter for this relay" }
        .fetch(relay, credential.parameters, binding, nowMillis)
    // A lab relay's pair never expires on the server, so it gets the longest grant the core accepts:
    // native does not re-authenticate inside a handle, and a short grant would end every session early.
    is CoreArgonRelayProfile.Credential.Static -> credential.withBytes { username, password ->
      CoreTurnGrant.create(relay.uri, username, password, STATIC_LIFETIME_SECONDS, nowMillis)
    }
  }

  private companion object {
    const val STATIC_LIFETIME_SECONDS = 86_400L
  }
}
