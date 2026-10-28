package com.example.veilark.profile

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

/** Fixed account-claim response shapes; never accepts a private key from a server. */
object FairwayDeviceEnrollmentCodec {
  private const val ERROR = "Invalid device enrollment response"
  private val scopePattern = Regex("[A-Za-z0-9_-]{32}")

  fun claimScope(bytes: ByteArray): String {
    val root = objectValue(bytes)
    require(root.keys == setOf("schema", "binding_scope") && root["schema"] == "veilark-core-device-claim-v1") { ERROR }
    return scope(root["binding_scope"])
  }

  /** Consumes the supplied private-key copy. The resulting document is secret. */
  fun mergeProfile(bytes: ByteArray, expectedScope: String, expectedPublicKey: String, privateKey: ByteArray): ByteArray {
    try {
      require(scopePattern.matches(expectedScope) && expectedPublicKey.matches(Regex("[0-9a-f]{64}")) && privateKey.size == 32) { ERROR }
      val root = objectValue(bytes)
      require(root.keys == setOf("schema", "binding_scope", "client_public_key_hex", "profile") &&
        root["schema"] == "veilark-core-device-profile-v1" &&
        scope(root["binding_scope"]) == expectedScope && root["client_public_key_hex"] == expectedPublicKey) { ERROR }
      val profile = root["profile"] as? Map<*, *> ?: error(ERROR)
      val identity = profile["identity"] as? Map<*, *> ?: error(ERROR)
      require(identity.keys == setOf("server_static_public_base64")) { ERROR }
      val merged = profile.toMutableMap().apply {
        put("identity", identity + ("client_static_private_base64" to Base64.getEncoder().encodeToString(privateKey)))
      }
      val document = json(merged)
      CoreArgonProfile.parse(document).use { require(it.handshakeProfile == CoreArgonHandshakeProfile.LEGACY) { ERROR } }
      return document.toByteArray(Charsets.UTF_8)
    } finally { privateKey.fill(0) }
  }

  private fun scope(value: Any?): String = (value as? String)?.takeIf(scopePattern::matches) ?: error(ERROR)

  private fun objectValue(bytes: ByteArray): Map<*, *> {
    require(bytes.size in 1..65_536) { ERROR }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    return CoreProfileJson.parse(text) as? Map<*, *> ?: error(ERROR)
  }

  // Preserve numeric tokens so malformed integer fields cannot become valid
  // through a permissive JSON library's floating-point normalization.
  private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> JSONObject.quote(value)
    is Boolean -> value.toString()
    is CoreProfileJson.NumberToken -> value.text
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) ->
      require(key is String) { ERROR }; JSONObject.quote(key) + ":" + json(item)
    }
    is List<*> -> value.joinToString(",", "[", "]", transform = ::json)
    else -> error(ERROR)
  }
}
