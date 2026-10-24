package com.example.veilark.profile

import com.example.veilark.core.runtime.CoreTurnEndpoint
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Collections

/**
 * The whitelist-mode route: the same Argon identity as a direct profile, reached
 * through TURN relays on legitimate services instead of a direct UDP path.
 * Picking this profile is what puts the client into whitelist mode; it lives in
 * its own slot, so importing it never replaces the direct (Fairway) profile.
 *
 * TURN credentials are not stored here. Each relay names where its short-lived
 * credentials come from: the legitimate service itself, through an app-side
 * adapter, because the control plane may be unreachable under a whitelist. A
 * static pair is accepted only in lab builds, for a self-hosted relay.
 *
 * Relays the native carrier cannot use (TURN over TCP or TLS, `turns:`) are
 * skipped, not rejected, and counted in [skippedRelays].
 */
class CoreArgonRelayProfile private constructor(
  val name: String,
  val exits: List<Exit>,
  val relays: List<Relay>,
  val skippedRelays: Int,
  val mtu: Int,
  val dnsIpv4: List<String>,
  val handshakeProfile: CoreArgonHandshakeProfile,
  private val clientStaticPrivate: ByteArray,
  private val serverStaticPublic: ByteArray,
  private var envelope: String?,
) : java.io.Closeable {
  class Exit internal constructor(val ipv4: String, val port: Int)

  /** [endpoint] is resolved by the app through the captured network; native receives only its IPv4. */
  class Relay internal constructor(internal val uri: String, internal val endpoint: CoreTurnEndpoint, val credential: Credential)

  sealed class Credential {
    /** Fetched at connect time by the adapter registered for [service]. */
    class Service internal constructor(val service: String, val parameters: Map<String, String>) : Credential()

    /** Lab only: a fixed pair for a self-hosted relay. */
    class Static internal constructor(private val username: ByteArray, private val password: ByteArray) : Credential() {
      @Synchronized fun <T> withBytes(block: (ByteArray, ByteArray) -> T): T {
        val user = username.copyOf(); val secret = password.copyOf()
        try { return block(user, secret) } finally { user.fill(0); secret.fill(0) }
      }
      @Synchronized internal fun wipe() { username.fill(0); password.fill(0) }
      override fun toString(): String = "Credential.Static(redacted)"
    }
  }

  private var closed = false
  @Synchronized fun clientStaticPrivateCopy(): ByteArray {
    check(!closed) { "Veilark Core profile is closed" }
    return clientStaticPrivate.copyOf()
  }
  @Synchronized fun serverStaticPublicCopy(): ByteArray {
    check(!closed) { "Veilark Core profile is closed" }
    return serverStaticPublic.copyOf()
  }
  @Synchronized internal fun validatedEnvelope(): String = checkNotNull(envelope)
  @Synchronized internal fun discardEnvelope() { envelope = null }
  @Synchronized override fun close() {
    clientStaticPrivate.fill(0); serverStaticPublic.fill(0)
    relays.forEach { (it.credential as? Credential.Static)?.wipe() }
    envelope = null; closed = true
  }
  override fun toString(): String = "CoreArgonRelayProfile(secret redacted)"

  companion object {
    const val SCHEMA = "veilark-argon-relay-profile-v1"
    private const val MAX_BYTES = 16 * 1024
    private const val ERROR = "Invalid Veilark Core profile."
    private val SERVICE = Regex("[a-z0-9][a-z0-9-]{0,31}")
    private val PARAMETER = Regex("[a-z0-9_]{1,32}")

    fun parse(bytes: ByteArray, allowStaticCredentials: Boolean): CoreArgonRelayProfile {
      require(bytes.size <= MAX_BYTES) { ERROR }
      val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      } catch (_: Exception) { throw IllegalArgumentException(ERROR) }
      return parse(text, allowStaticCredentials)
    }

    fun parse(text: String, allowStaticCredentials: Boolean): CoreArgonRelayProfile {
      var client: ByteArray? = null
      var server: ByteArray? = null
      val statics = mutableListOf<Credential.Static>()
      try {
        check(text.length <= MAX_BYTES && CoreProfileJson.validUnicode(text))
        val encoded = text.toByteArray(Charsets.UTF_8)
        try { check(encoded.size <= MAX_BYTES) } finally { encoded.fill(0) }
        val root = CoreProfileJson.parse(text) as? Map<*, *> ?: error("invalid")
        val fields = setOf("schema", "engine", "beta", "name", "exits", "relays", "identity", "network")
        check(root.keys == fields || root.keys == fields + "wire_profile")
        check(root["schema"] == SCHEMA && root["engine"] == "argon-v1" && root["beta"] == true)
        val handshake = if ("wire_profile" in root) {
          val wire = root["wire_profile"].obj(setOf("id", "feedback_version"))
          val id = wire["id"].integer(2, 3)
          wire["feedback_version"].integer(1, 1)
          if (id == 3) CoreArgonHandshakeProfile.PASSAGE_V1 else CoreArgonHandshakeProfile.CARRIER2_LAB1
        } else CoreArgonHandshakeProfile.LEGACY
        val name = root["name"].str()
        check(name.codePointCount(0, name.length) in 1..80)
        check(name.codePoints().allMatch { Character.getType(it) !in setOf(
          Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(),
          Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        ) })
        val exitList = root["exits"] as? List<*> ?: error("invalid")
        check(exitList.size in 1..4)
        val exits = exitList.map {
          val exit = it.obj(setOf("ipv4", "port"))
          Exit(exit["ipv4"].str().also(::ipv4), exit["port"].integer(1, 65535))
        }
        check(exits.distinctBy { it.ipv4 to it.port }.size == exits.size)
        val relayList = root["relays"] as? List<*> ?: error("invalid")
        check(relayList.size in 1..8)
        var skipped = 0
        val relays = relayList.mapNotNull { entry ->
          val relay = entry.obj(setOf("uri", "credential"))
          val uri = relay["uri"].str()
          val endpoint = runCatching { CoreTurnEndpoint.parse(uri) }.getOrNull()
          if (endpoint == null) { skipped++; null }
          else Relay(uri, endpoint, credential(relay["credential"], allowStaticCredentials).also {
            if (it is Credential.Static) statics += it
          })
        }
        check(relays.isNotEmpty())
        val identity = root["identity"].obj(setOf("client_static_private_base64", "server_static_public_base64"))
        client = key(identity["client_static_private_base64"].str())
        server = key(identity["server_static_public_base64"].str())
        val network = root["network"].obj(setOf("mtu", "dns_ipv4"))
        val mtu = network["mtu"].integer(1280, handshake.maximumMtu)
        val dns = network["dns_ipv4"] as? List<*> ?: error("invalid")
        check(dns.size in 1..3)
        val resolvers = dns.map { it.str().also(::ipv4) }
        check(resolvers.distinct().size == resolvers.size)
        return CoreArgonRelayProfile(name, Collections.unmodifiableList(exits), Collections.unmodifiableList(relays),
          skipped, mtu, Collections.unmodifiableList(resolvers), handshake, client, server, text)
          .also { client = null; server = null; statics.clear() }
      } catch (_: IllegalArgumentException) { throw IllegalArgumentException(ERROR) }
      catch (_: IllegalStateException) { throw IllegalArgumentException(ERROR) }
      finally { client?.fill(0); server?.fill(0); statics.forEach(Credential.Static::wipe) }
    }

    private fun credential(value: Any?, allowStatic: Boolean): Credential {
      val map = value as? Map<*, *> ?: error("invalid")
      return when (map["source"]) {
        "service" -> {
          check(map.keys == setOf("source", "service") || map.keys == setOf("source", "service", "parameters"))
          val service = map["service"].str().also { check(SERVICE.matches(it)) }
          val raw = if ("parameters" in map) map["parameters"] as? Map<*, *> ?: error("invalid") else emptyMap<Any?, Any?>()
          check(raw.size <= 8)
          val parameters = raw.entries.associate { (key, item) ->
            val name = key as? String ?: error("invalid")
            val text = item.str()
            check(PARAMETER.matches(name) && text.length <= 512 && text.all { it.code in 32..126 })
            name to text
          }
          Credential.Service(service, Collections.unmodifiableMap(parameters))
        }
        "static" -> {
          check(allowStatic && map.keys == setOf("source", "username", "password"))
          val username = map["username"].str().toByteArray(Charsets.US_ASCII)
          val password = map["password"].str().toByteArray(Charsets.US_ASCII)
          // The same printable-ASCII subset CoreTurnGrant accepts.
          if (username.size !in 1..512 || password.size !in 1..1024 ||
            !username.all { it.toInt() in 33..126 } || !password.all { it.toInt() in 32..126 }) {
            username.fill(0); password.fill(0); error("invalid")
          }
          Credential.Static(username, password)
        }
        else -> error("invalid")
      }
    }

    private fun key(text: String): ByteArray {
      check(text.matches(Regex("[A-Za-z0-9+/]{43}=")))
      val bytes = Base64.getDecoder().decode(text)
      try {
        check(bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == text)
        check(bytes.any { it != 0.toByte() })
        return bytes
      } catch (failure: Exception) { bytes.fill(0); throw failure }
    }
    private fun ipv4(text: String) {
      val parts = text.split('.')
      check(parts.size == 4)
      val octets = parts.map {
        check(it.matches(Regex("0|[1-9][0-9]{0,2}")))
        it.toInt().also { value -> check(value in 0..255) }
      }
      check(octets[0] in 1..223 && octets[0] != 127)
    }
    private fun Any?.str(): String = this as? String ?: error("invalid")
    private fun Any?.obj(fields: Set<String>): Map<*, *> {
      val value = this as? Map<*, *> ?: error("invalid")
      check(value.keys == fields); return value
    }
    private fun Any?.integer(min: Int, max: Int): Int {
      val token = this as? CoreProfileJson.NumberToken ?: error("invalid")
      check(token.text.matches(Regex("0|[1-9][0-9]*")))
      val value = token.text.toLongOrNull() ?: error("invalid")
      check(value in min.toLong()..max.toLong()); return value.toInt()
    }
  }
}
