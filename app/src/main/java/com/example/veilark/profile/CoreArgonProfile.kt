package com.example.veilark.profile

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Collections

/** Explicit wire contract; never inferred from routing mode or a failed handshake. */
enum class CoreArgonHandshakeProfile(val wireProfile: Int, val feedbackVersion: Int) {
  LEGACY(0, 0), CARRIER2_LAB1(2, 1), PASSAGE_V1(3, 1);

  val maximumMtu: Int get() = if (this == PASSAGE_V1) 1357 else 1360
  val minimumRelayAbi: Int get() = if (this == PASSAGE_V1) 4 else 3
}

/** Argon identity/configuration, never a legacy PSK conversion or authenticated
 * session. The client's IP is obtained only from the authenticated handshake.
 * Caller owns key copies and must erase them after native synchronous admission.
 */
class CoreArgonProfile private constructor(
  val name: String,
  val endpointIpv4: String,
  val endpointPort: Int,
  val mtu: Int,
  val dnsIpv4: List<String>,
  val handshakeProfile: CoreArgonHandshakeProfile,
  private val clientStaticPrivate: ByteArray,
  private val serverStaticPublic: ByteArray,
  private var envelope: String?,
) : java.io.Closeable {
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
    envelope = null; closed = true
  }
  override fun toString(): String = "CoreArgonProfile(secret redacted)"

  companion object {
    private const val MAX_BYTES = 16 * 1024
    private const val ERROR = "Invalid Veilark Core profile."

    fun parse(bytes: ByteArray): CoreArgonProfile {
      require(bytes.size <= MAX_BYTES) { ERROR }
      val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      } catch (_: Exception) { throw IllegalArgumentException(ERROR) }
      return parse(text)
    }

    fun parse(text: String): CoreArgonProfile {
      var client: ByteArray? = null
      var server: ByteArray? = null
      try {
        check(text.length <= MAX_BYTES && CoreProfileJson.validUnicode(text))
        val encoded = text.toByteArray(Charsets.UTF_8)
        try { check(encoded.size <= MAX_BYTES) } finally { encoded.fill(0) }
        val parsed = CoreProfileJson.parse(text) as? Map<*, *> ?: error("invalid")
        val fields = setOf("schema", "engine", "beta", "name", "endpoint", "identity", "network")
        val wireProfile = when (parsed["schema"]) {
          "veilark-argon-profile-v1" -> {
            parsed.obj(fields)
            CoreArgonHandshakeProfile.LEGACY
          }
          "veilark-argon-profile-v2" -> {
            parsed.obj(fields + "wire_profile")
            val wire = parsed["wire_profile"].obj(setOf("id", "feedback_version"))
            wire["id"].integer(2, 2)
            wire["feedback_version"].integer(1, 1)
            CoreArgonHandshakeProfile.CARRIER2_LAB1
          }
          else -> error("invalid")
        }
        val root = parsed
        check(root["engine"] == "argon-v1" && root["beta"] == true)
        val name = root["name"].str()
        check(name.codePointCount(0, name.length) in 1..80)
        check(name.codePoints().allMatch { Character.getType(it) !in setOf(
          Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(),
          Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        ) })
        val endpoint = root["endpoint"].obj(setOf("ipv4", "port"))
        val address = endpoint["ipv4"].str().also { ipv4(it) }
        val port = endpoint["port"].integer(1, 65535)
        val identity = root["identity"].obj(setOf("client_static_private_base64", "server_static_public_base64"))
        client = key(identity["client_static_private_base64"].str())
        server = key(identity["server_static_public_base64"].str())
        val network = root["network"].obj(setOf("mtu", "dns_ipv4"))
        val mtu = network["mtu"].integer(1280, 1360)
        val dns = network["dns_ipv4"] as? List<*> ?: error("invalid")
        check(dns.size in 1..3)
        val resolvers = dns.map { it.str().also { value -> ipv4(value) } }
        check(resolvers.distinct().size == resolvers.size)
        return CoreArgonProfile(name, address, port, mtu,
          Collections.unmodifiableList(resolvers.toList()), wireProfile, client, server, text)
          .also { client = null; server = null }
      } catch (_: IllegalArgumentException) { throw IllegalArgumentException(ERROR) }
      catch (_: IllegalStateException) { throw IllegalArgumentException(ERROR) }
      finally { client?.fill(0); server?.fill(0) }
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
