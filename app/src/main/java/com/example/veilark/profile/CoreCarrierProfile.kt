package com.example.veilark.profile

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.security.MessageDigest

/** Inactive structurally validated v2. NOT time/floor/freshness admission or stored trust. */
internal class CoreCarrierProfile private constructor(
  val name: String,
  val issuerId: String,
  val keyId: String,
  val profileId: String,
  val revision: Int,
  val issuedAt: Long,
  val expiresAt: Long,
  val trustGeneration: Long,
  val payloadSha256: String,
  val carrier: Carrier,
  val peer: Int,
  val clientIpv4: String,
  val serverIpv4: String,
  dnsIpv4: List<String>,
  val lifetimeSeconds: Int,
  private val key: ByteArray,
  private val context: ByteArray,
) : java.io.Closeable {
  data class Carrier(
    val ipv4: String, val port: Int, val serverName: String,
    val connectTimeoutMs: Int, val tlsTimeoutMs: Int,
    val pairTimeoutMs: Int, val teardownTimeoutMs: Int,
  )
  val dnsIpv4: List<String> = java.util.Collections.unmodifiableList(dnsIpv4.toList())
  private var closed = false
  @Synchronized fun keyCopy(): ByteArray { check(!closed); return key.copyOf() }
  @Synchronized fun contextCopy(): ByteArray { check(!closed); return context.copyOf() }
  @Synchronized override fun close() { key.fill(0); context.fill(0); closed = true }
  override fun toString() = "CoreCarrierProfile(secret redacted; admission pending)"

  companion object {
    private const val ERROR = "Invalid signed Core payload."
    private val topFields = setOf("schema", "engine", "beta", "name", "issuer_id", "key_id", "profile_id",
      "revision", "issued_at", "not_before", "expires_at", "carrier", "enrollment", "network")
    private val enrollmentFields = setOf("schema", "protocol", "transport", "endpoint", "peer", "carrier",
      "initiator", "responder", "psk_base64", "client_ipv4", "server_ipv4", "prefix_length", "mtu")
    private val carrierFields = setOf("kind", "connect_ipv4", "connect_port", "server_name", "tls_version",
      "early_data", "alpn", "connect_timeout_ms", "tls_timeout_ms", "pair_timeout_ms", "teardown_timeout_ms", "buffer_bytes")

    fun parse(envelope: CoreSignedEnvelope.SignatureCheckedEnvelope): CoreCarrierProfile {
      val bytes = envelope.payloadCopy()
      var ownedKey: ByteArray? = null
      var ownedContext: ByteArray? = null
      try {
        require(bytes.size in 1..16384)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require(CoreProfileJson.validUnicode(text))
        val root = CoreProfileJson.parse(text).obj(topFields)
        require(root["schema"] == "veilark-core-carrier-profile-v2" && root["engine"] == "original-v1" && root["beta"] == true)
        require(root["issuer_id"] == envelope.issuerId && root["key_id"] == envelope.keyId)
        val name = root["name"].str()
        require(name.codePointCount(0, name.length) in 1..80)
        require(name.codePoints().allMatch { Character.getType(it) !in setOf(Character.CONTROL.toInt(),
          Character.FORMAT.toInt(), Character.UNASSIGNED.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt()) })
        val profileId = root["profile_id"].str()
        require(profileId.matches(Regex("[0-9a-f]{32}")))
        val revision = root["revision"].number(1, Int.MAX_VALUE.toLong()).toInt()
        val issuedAt = root["issued_at"].number(1, 4102444800)
        require(root["not_before"].number(1, 4102444800) == issuedAt)
        val expiresAt = root["expires_at"].number(1, 4102444800)
        require(expiresAt - issuedAt in 1..2592000)

        val c = root["carrier"].obj(carrierFields)
        require(c["kind"] == "tls13-original-stream-v1" && c["tls_version"] == "TLSv1.3" && c["early_data"] == false)
        require((c["alpn"] as? List<*>)?.isEmpty() == true)
        val ipv4 = c["connect_ipv4"].str()
        require(unicast(ipv4(ipv4)))
        val port = c["connect_port"].number(1, 65535).toInt()
        val hostname = c["server_name"].str()
        require(hostname.length in 1..253 && hostname.any { it in 'a'..'z' } && hostname.split('.').let { labels ->
          labels.size >= 2 && labels.all { it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) } &&
            !labels.all { it.matches(Regex("[0-9]+|0x[0-9a-f]+")) }
        })
        val connectMs = c["connect_timeout_ms"].number(1000, 10000).toInt()
        val tlsMs = c["tls_timeout_ms"].number(1000, 10000).toInt()
        val pairMs = c["pair_timeout_ms"].number(100, 2000).toInt()
        val teardownMs = c["teardown_timeout_ms"].number(1000, 5000).toInt()
        require(connectMs + tlsMs + pairMs <= 20000)
        c["buffer_bytes"].number(16384, 16384)

        val e = root["enrollment"].obj(enrollmentFields)
        require(e["schema"] == "veilark-original-enrollment-v2" && e["protocol"] == "original-v1" && e["transport"] == "tls13-original-stream-v1")
        require(e["endpoint"] == "$ipv4:$port")
        val peer = e["peer"].number(1, 64).toInt()
        val client = e["client_ipv4"].str()
        val server = e["server_ipv4"].str()
        val clientBits = ipv4(client)
        require(privateIpv4(clientBits) && (clientBits and 3L) == 1L && ipv4(server) == clientBits + 1)
        e["prefix_length"].number(30, 30); e["mtu"].number(1280, 1280)
        val context = ByteArray(96)
        ownedContext = context
        listOf("carrier", "initiator", "responder").forEachIndexed { index, field ->
          val hex = e[field].str()
          require(hex.matches(Regex("[0-9a-f]{64}")))
          for (byte in 0 until 32) context[index * 32 + byte] = hex.substring(byte * 2, byte * 2 + 2).toInt(16).toByte()
        }
        require((0 until 32).any { context[32 + it] != context[64 + it] })
        val keyText = e["psk_base64"].str()
        require(keyText.matches(Regex("[A-Za-z0-9+/]{43}=")))
        val key = Base64.getDecoder().decode(keyText)
        ownedKey = key
        require(key.size == 32 && key.any { it != 0.toByte() } && Base64.getEncoder().encodeToString(key) == keyText)
        val network = root["network"].obj(setOf("dns_ipv4", "lifetime_seconds"))
        val dns = network["dns_ipv4"] as? List<*> ?: error(ERROR)
        require(dns.size in 1..3)
        val resolvers = dns.map { it.str().also { value -> require(unicast(ipv4(value))) } }
        require(resolvers.distinct().size == resolvers.size)
        val lifetime = network["lifetime_seconds"].number(10, 3595).toInt()
        return CoreCarrierProfile(name, envelope.issuerId, envelope.keyId, profileId, revision, issuedAt,
          expiresAt, envelope.trustGeneration, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
          Carrier(ipv4, port, hostname, connectMs, tlsMs, pairMs, teardownMs),
          peer, client, server, resolvers, lifetime, key, context).also { ownedKey = null; ownedContext = null }
      } catch (_: IllegalArgumentException) { throw IllegalArgumentException(ERROR) }
        catch (_: IllegalStateException) { throw IllegalArgumentException(ERROR) }
        catch (_: CharacterCodingException) { throw IllegalArgumentException(ERROR) }
      finally { bytes.fill(0); ownedKey?.fill(0); ownedContext?.fill(0) }
    }

    private fun Any?.str() = this as? String ?: error(ERROR)
    private fun Any?.obj(fields: Set<String>): Map<*, *> {
      val map = this as? Map<*, *> ?: error(ERROR)
      require(map.keys == fields); return map
    }
    private fun Any?.number(min: Long, max: Long): Long {
      val token = this as? CoreProfileJson.NumberToken ?: error(ERROR)
      require(token.text.matches(Regex("0|[1-9][0-9]*")))
      return (token.text.toLongOrNull() ?: error(ERROR)).also { require(it in min..max) }
    }
    private fun ipv4(text: String): Long {
      val parts = text.split('.')
      require(parts.size == 4)
      return parts.fold(0L) { bits, part ->
        require(part.matches(Regex("0|[1-9][0-9]{0,2}")))
        val octet = part.toInt(); require(octet <= 255); (bits shl 8) or octet.toLong()
      }
    }
    private fun privateIpv4(ip: Long) = ip ushr 24 == 10L || ip ushr 20 == 0xac1L || ip ushr 16 == 0xc0a8L
    private fun unicast(ip: Long) = ip ushr 24 !in setOf(0L, 127L) && ip ushr 16 != 0xa9feL && ip ushr 24 < 224L
  }
}
