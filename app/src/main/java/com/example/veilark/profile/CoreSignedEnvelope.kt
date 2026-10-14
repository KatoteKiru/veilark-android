package com.example.veilark.profile

import com.example.veilark.security.Ed25519Verifier
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Inactive v2 signature boundary. No imports, storage or VPN admission use this yet. */
internal object CoreSignedEnvelope {
  private const val ERROR = "Invalid signed Core envelope."
  private val fields = setOf("schema", "issuer_id", "key_id", "payload_base64", "signature_base64")
  private val id = Regex("[a-z0-9][a-z0-9_-]{0,31}")
  private val base64 = Regex("[A-Za-z0-9+/]*={0,2}")
  private val domain = "VEILARK-CORE-PROFILE-V2\u0000".toByteArray(Charsets.US_ASCII)

  /** Trust must come from an authenticated app release, never from the envelope. */
  class IssuerSnapshot(val generation: Long, entries: List<Entry>) {
    class Entry(val issuerId: String, val keyId: String, publicKeyX509: ByteArray) {
      private val key = publicKeyX509.copyOf()
      internal fun keyCopy() = key.copyOf()
      override fun toString() = "CoreIssuerEntry"
    }
    private val keys: Map<Pair<String, String>, ByteArray>
    init {
      require(generation > 0 && entries.size in 1..64) { ERROR }
      val copied = linkedMapOf<Pair<String, String>, ByteArray>()
      entries.forEach {
        require(id.matches(it.issuerId) && id.matches(it.keyId)) { ERROR }
        val pair = it.issuerId to it.keyId
        require(pair !in copied) { ERROR }
        val key = it.keyCopy()
        require(Ed25519Verifier.hasSupportedPublicKeyEncoding(key)) { ERROR }
        copied[pair] = key
      }
      keys = copied.toMap()
    }
    internal fun keyCopy(issuer: String, keyId: String) = keys[issuer to keyId]?.copyOf()
    override fun toString() = "CoreIssuerSnapshot(generation=$generation)"
  }

  /** Signature checked ONLY; payload schema/IDs/time/floor must still be validated. */
  class SignatureCheckedEnvelope internal constructor(
    val issuerId: String,
    val keyId: String,
    val trustGeneration: Long,
    private val payload: ByteArray,
  ) : java.io.Closeable {
    private var closed = false
    @Synchronized fun payloadCopy(): ByteArray {
      check(!closed) { ERROR }
      return payload.copyOf()
    }
    @Synchronized override fun close() { payload.fill(0); closed = true }
    override fun toString() = "CoreSignatureCheckedEnvelope(payload redacted; not an admitted profile)"
  }

  fun checkSignature(input: ByteArray, trust: IssuerSnapshot): SignatureCheckedEnvelope {
    var payload: ByteArray? = null
    var message: ByteArray? = null
    try {
      require(input.size in 1..24576)
      val envelope = EnvelopeJson(strictUtf8(input)).parse()
      require(envelope.keys == fields && envelope["schema"] == "veilark-core-signed-profile-v2")
      val issuer = envelope.getValue("issuer_id")
      val keyId = envelope.getValue("key_id")
      require(id.matches(issuer) && id.matches(keyId))
      val key = trust.keyCopy(issuer, keyId) ?: error(ERROR)
      payload = decode(envelope.getValue("payload_base64"), 16384)
      require(payload.isNotEmpty())
      val signature = decode(envelope.getValue("signature_base64"), 64)
      require(signature.size == 64)
      message = domain + payload
      require(Ed25519Verifier.verify(message, signature, key))
      strictUtf8(payload) // Syntax/IDs/time/storage validation is a separate next boundary.
      return SignatureCheckedEnvelope(issuer, keyId, trust.generation, payload).also { payload = null }
    } catch (_: IllegalArgumentException) { throw IllegalArgumentException(ERROR) }
      catch (_: IllegalStateException) { throw IllegalArgumentException(ERROR) }
      catch (_: CharacterCodingException) { throw IllegalArgumentException(ERROR) }
    finally { payload?.fill(0); message?.fill(0) }
  }

  private fun decode(text: String, maxBytes: Int): ByteArray {
    require(text.length <= ((maxBytes + 2) / 3) * 4 && text.length % 4 == 0 && base64.matches(text))
    val bytes = Base64.getDecoder().decode(text)
    if (bytes.size > maxBytes || Base64.getEncoder().encodeToString(bytes) != text) {
      bytes.fill(0)
      error(ERROR)
    }
    return bytes
  }

  private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString().also { require(!it.startsWith('\uFEFF')) }

  /** Exact flat string-valued envelope; nested/non-string values are unsupported. */
  private class EnvelopeJson(private val text: String) {
    private var at = 0
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean = if (at < text.length && text[at] == char) { at++; true } else false
    fun parse(): Map<String, String> {
      space(); require(take('{')); space()
      val result = linkedMapOf<String, String>()
      require(!take('}'))
      do {
        space(); val key = string()
        require(key in fields && key !in result)
        space(); require(take(':')); space()
        result[key] = string(); space()
      } while (take(','))
      require(take('}')); space(); require(at == text.length)
      return result
    }
    private fun string(): String {
      require(take('"'))
      val out = StringBuilder()
      while (at < text.length) {
        val char = text[at++]
        if (char == '"') return out.toString().also { value ->
          var i = 0
          while (i < value.length) {
            val c = value[i++]
            if (Character.isHighSurrogate(c)) require(i < value.length && Character.isLowSurrogate(value[i++]))
            else require(!Character.isLowSurrogate(c))
          }
        }
        require(char >= ' ')
        if (char != '\\') out.append(char) else {
          require(at < text.length)
          out.append(when (val escape = text[at++]) {
            '"', '\\', '/' -> escape
            'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
            'u' -> {
              require(at + 4 <= text.length)
              val hex = text.substring(at, at + 4)
              require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
              at += 4; hex.toInt(16).toChar()
            }
            else -> error(ERROR)
          })
        }
      }
      error(ERROR)
    }
  }
}
