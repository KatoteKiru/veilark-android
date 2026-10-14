package com.example.veilark.profile

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Collections

/** Secret-bearing profile. Do not place its envelope or key copies in logs or preferences. */
class CoreBetaProfile private constructor(
  val name: String,
  val endpointIpv4: String,
  val endpointPort: Int,
  val peer: Int,
  val clientIpv4: String,
  val serverIpv4: String,
  val dnsIpv4: List<String>,
  val lifetimeSeconds: Int,
  private val key: ByteArray,
  private val context: ByteArray,
  private var envelope: String?,
) : java.io.Closeable {
  private var closed = false
  @Synchronized fun keyCopy(): ByteArray {
    check(!closed) { "Core profile is closed." }
    return key.copyOf()
  }
  /** carrier || initiator || responder, exactly 96 bytes. */
  @Synchronized fun contextCopy(): ByteArray {
    check(!closed) { "Core profile is closed." }
    return context.copyOf()
  }
  /** Only for the existing encrypted private profile store. */
  @Synchronized fun validatedEnvelope(): String =
    checkNotNull(envelope) { "Core profile envelope is unavailable." }
  /** Persistence has completed; runtime and UI metadata never need the original JSON. */
  @Synchronized internal fun discardEnvelope() { envelope = null }
  /** Close only after the reconnect owner is finished; copies remain caller-owned. */
  @Synchronized override fun close() {
    key.fill(0); context.fill(0); envelope = null; closed = true
  }
  override fun toString(): String = "CoreBetaProfile(secret redacted)"

  companion object {
    const val MAX_INPUT_BYTES = 16 * 1024

    fun parse(input: ByteArray): CoreBetaProfile {
      require(input.size <= MAX_INPUT_BYTES) { ERROR }
      val text = try {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(input)).toString()
      } catch (_: Exception) { throw IllegalArgumentException(ERROR) }
      return parse(text)
    }

    fun parse(input: String): CoreBetaProfile {
      var parsedKey: ByteArray? = null
      try {
        check(input.length <= MAX_INPUT_BYTES)
        check(CoreProfileJson.validUnicode(input))
        val sizeCheckBytes = input.toByteArray(Charsets.UTF_8)
        try { check(sizeCheckBytes.size <= MAX_INPUT_BYTES) }
        finally { sizeCheckBytes.fill(0) }
        val root = CoreProfileJson.parse(input).obj(setOf("schema", "name", "engine", "beta", "enrollment", "network"))
        check(root["schema"] == "veilark-core-beta-profile-v1")
        check(root["engine"] == "original-v1" && root["beta"] == true)
        val name = root["name"].str()
        check(name.codePointCount(0, name.length) in 1..80)
        check(name.codePoints().allMatch { Character.getType(it) !in setOf(
          Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(),
          Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        ) })
        val e = root["enrollment"].obj(setOf("schema", "protocol", "transport", "endpoint", "peer", "carrier", "initiator", "responder", "psk_base64", "client_ipv4", "server_ipv4", "prefix_length", "mtu"))
        check(e["schema"] == "veilark-original-enrollment-v1" && e["protocol"] == "original-v1" && e["transport"] == "raw-stream-v1")
        val endpoint = e["endpoint"].str().split(':')
        check(endpoint.size == 2)
        ipv4(endpoint[0])
        check(endpoint[1].matches(Regex("[1-9][0-9]{0,4}")))
        val port = endpoint[1].toInt()
        check(port in 1..65535)
        val peer = e["peer"].integer(1, 64)
        val client = e["client_ipv4"].str()
        val server = e["server_ipv4"].str()
        val clientBits = ipv4(client)
        val serverBits = ipv4(server)
        check(privateIpv4(clientBits) && (clientBits and 3L) == 1L && serverBits == clientBits + 1)
        e["prefix_length"].integer(30, 30)
        e["mtu"].integer(1280, 1280)
        val initiator = hex(e["initiator"].str())
        val responder = hex(e["responder"].str())
        check(!initiator.contentEquals(responder))
        val context = hex(e["carrier"].str()) + initiator + responder
        val keyText = e["psk_base64"].str()
        check(keyText.matches(Regex("[A-Za-z0-9+/]{43}=")))
        val key = Base64.getDecoder().decode(keyText)
        parsedKey = key
        check(key.size == 32 && Base64.getEncoder().encodeToString(key) == keyText)
        val network = root["network"].obj(setOf("dns_ipv4", "lifetime_seconds"))
        val dns = (network["dns_ipv4"] as? List<*>) ?: error("invalid")
        check(dns.size in 1..3)
        val resolvers = dns.map { it.str().also { value -> check(dnsIpv4(ipv4(value))) } }
        check(resolvers.distinct().size == resolvers.size)
        return CoreBetaProfile(name, endpoint[0], port, peer, client, server, Collections.unmodifiableList(resolvers.toList()), network["lifetime_seconds"].integer(10, 3595), key, context, input)
          .also { parsedKey = null } // The returned closeable profile now owns these bytes.
      } catch (_: IllegalArgumentException) { throw IllegalArgumentException(ERROR) }
      catch (_: IllegalStateException) { throw IllegalArgumentException(ERROR) }
      finally { parsedKey?.fill(0) }
    }

    private const val ERROR = "Invalid Veilark Core beta profile."
    private fun Any?.str(): String = this as? String ?: error("invalid")
    private fun Any?.obj(fields: Set<String>): Map<*, *> {
      val result = this as? Map<*, *> ?: error("invalid")
      check(result.keys == fields)
      return result
    }
    private fun Any?.integer(min: Int, max: Int): Int {
      val token = this as? CoreProfileJson.NumberToken ?: error("invalid")
      check(token.text.matches(Regex("0|[1-9][0-9]*")))
      val value = token.text.toLongOrNull() ?: error("invalid")
      check(value in min.toLong()..max.toLong())
      return value.toInt()
    }
    private fun hex(text: String): ByteArray {
      check(text.matches(Regex("[0-9a-fA-F]{64}")))
      return ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun ipv4(text: String): Long {
      val parts = text.split('.')
      check(parts.size == 4)
      return parts.fold(0L) { bits, part ->
        check(part.matches(Regex("0|[1-9][0-9]{0,2}")))
        val octet = part.toInt()
        check(octet <= 255)
        (bits shl 8) or octet.toLong()
      }
    }
    private fun privateIpv4(ip: Long): Boolean = ip ushr 24 == 10L || ip ushr 20 == 0xac1L || ip ushr 16 == 0xc0a8L
    private fun dnsIpv4(ip: Long): Boolean = ip ushr 24 !in setOf(0L, 127L) && ip ushr 16 != 0xa9feL && ip ushr 24 < 224L
  }
}
