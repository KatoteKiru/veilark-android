package com.example.veilark.profile

import com.example.veilark.security.Ed25519Verifier
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Unactivated draft experiment. No fetch, trust bootstrap, storage, or revision advancement.
 * Byte-array erasure is best effort: JVM strings/provider copies cannot be perfectly erased.
 */
internal class EnrollmentV2DraftConsumer private constructor(
  val issuer: String,
  val keyId: String,
  val profileId: String,
  val revision: Long,
  val issuedAt: Long,
  val notBefore: Long,
  val expiresAt: Long,
  private val profile: ByteArray,
  private val digest: ByteArray,
) : AutoCloseable {
  private var closed = false
  @Synchronized fun profileCopy(): ByteArray { check(!closed) { ERROR }; return profile.copyOf() }
  @Synchronized fun signedContentDigestCopy(): ByteArray { check(!closed) { ERROR }; return digest.copyOf() }
  @Synchronized override fun close() { profile.fill(0); digest.fill(0); closed = true }
  override fun toString(): String = "EnrollmentV2DraftConsumer(verified draft; secrets redacted)"

  companion object {
    private const val ERROR = "Invalid draft enrollment."
    private const val MAX_INTEGER = 9_007_199_254_740_991L
    private const val OUTER = "{\"alg\":\"dir\",\"enc\":\"A256GCM\",\"typ\":\"veilark-enrollment+jwe;v=2\",\"cty\":\"veilark-profile+jws;v=2\"}"
    private val ID = Regex("[A-Za-z0-9_-]{1,48}")
    private val LINK = Regex("veilark://enroll/v2\\?t=([A-Za-z0-9_-]{43})#k=([A-Za-z0-9_-]{43})")
    private val PAYLOAD_FIELDS = setOf("v", "purpose", "iss", "lid", "profile_id", "revision", "iat", "nbf", "exp", "profile_b64")

    /** Trust entries are explicitly supplied Ed25519 RFC8410 SPKI; none are installed here.
     * nowEpochSeconds is caller-supplied wall time. This checks only the import window.
     */
    fun verify(
      link: String,
      ciphertext: ByteArray,
      trustedKeys: Map<Pair<String, String>, ByteArray>,
      nowEpochSeconds: Long,
    ): EnrollmentV2DraftConsumer {
      val temporary = mutableListOf<ByteArray>()
      fun own(bytes: ByteArray): ByteArray = bytes.also { temporary.add(it) }
      fun decode(text: String, max: Int): ByteArray {
        check(text.isNotEmpty() && text.length <= (max * 8 + 5) / 6)
        check(text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })
        val bytes = own(Base64.getUrlDecoder().decode(text))
        check(bytes.size <= max && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == text)
        return bytes
      }
      try {
        check(nowEpochSeconds in 1..MAX_INTEGER)
        check(link.length <= 160 && link.all { it.code in 0..127 })
        val match = LINK.matchEntire(link) ?: error(ERROR)
        val locator = match.groupValues[1]
        check(decode(locator, 32).size == 32)
        val key = decode(match.groupValues[2], 32)
        check(key.size == 32)
        check(ciphertext.size in 1..65_536 && ciphertext.all { it.toInt() in 0..127 })
        val outer = String(ciphertext, Charsets.US_ASCII).split('.', limit = 6)
        check(outer.size == 5 && outer[1].isEmpty())
        check(outer[0] == Base64.getUrlEncoder().withoutPadding().encodeToString(OUTER.toByteArray(Charsets.UTF_8)))
        val iv = decode(outer[2], 12).also { check(it.size == 12) }
        val encrypted = decode(outer[3], 34_000)
        val tag = decode(outer[4], 16).also { check(it.size == 16) }
        val combined = own(encrypted + tag)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(own(outer[0].toByteArray(Charsets.US_ASCII)))
        val plaintext = own(cipher.doFinal(combined))
        check(plaintext.size <= 34_000 && plaintext.all { it.toInt() in 0..127 })
        val inner = String(plaintext, Charsets.US_ASCII).split('.', limit = 4)
        check(inner.size == 3)
        val header = objectValue(strictUtf8(decode(inner[0], 512)), setOf("alg", "kid", "typ"))
        check(header["alg"] == "Ed25519" && header["typ"] == "veilark-profile+jws;v=2")
        val kid = header["kid"].identifier()
        val payload = objectValue(strictUtf8(decode(inner[1], 24_576)), PAYLOAD_FIELDS)
        // Unverified fields select an existing local key only; no profile parsing/effects.
        val issuer = payload["iss"].identifier()
        val publicKey = trustedKeys[issuer to kid] ?: error(ERROR)
        val signature = decode(inner[2], 64).also { check(it.size == 64) }
        val signingInput = own((inner[0] + "." + inner[1]).toByteArray(Charsets.US_ASCII))
        check(Ed25519Verifier.verify(signingInput, signature, publicKey))
        check(payload["v"].integer() == 2L && payload["purpose"] == "veilark.profile.enrollment.v2")
        check(payload["lid"] == locator)
        val profileId = payload["profile_id"].identifier()
        val revision = payload["revision"].integer()
        val iat = payload["iat"].integer()
        val nbf = payload["nbf"].integer()
        val exp = payload["exp"].integer()
        check(iat <= nbf && nbf < exp && exp - iat <= 2_592_000)
        check(iat <= nowEpochSeconds + 300 && nbf <= nowEpochSeconds + 300 && nowEpochSeconds < exp)
        val profileBytes = decode(payload["profile_b64"] as? String ?: error(ERROR), 16_384)
        val profileText = strictUtf8(profileBytes)
        val profileObject = CoreProfileJson.parse(profileText) as? Map<*, *> ?: error(ERROR)
        check(profileObject["schema"] == "veilark-argon-profile-v1")
        CoreArgonProfile.parse(profileText).use { check(it.handshakeProfile == CoreArgonHandshakeProfile.LEGACY) }
        val digest = own(MessageDigest.getInstance("SHA-256").digest(signingInput))
        return EnrollmentV2DraftConsumer(issuer, kid, profileId, revision, iat, nbf, exp, profileBytes.copyOf(), digest.copyOf())
      } catch (_: Exception) {
        // No causes/messages from provider, JSON, link, profile, or key input escape.
        throw IllegalArgumentException(ERROR)
      } finally {
        temporary.forEach { it.fill(0) }
      }
    }

    private fun strictUtf8(bytes: ByteArray): String {
      val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
      check(!text.startsWith('\uFEFF'))
      return text
    }
    private fun objectValue(text: String, fields: Set<String>): Map<*, *> {
      val value = CoreProfileJson.parse(text) as? Map<*, *> ?: error(ERROR)
      check(value.keys == fields)
      return value
    }
    private fun Any?.identifier(): String = (this as? String ?: error(ERROR)).also { check(ID.matches(it)) }
    private fun Any?.integer(): Long {
      val token = this as? CoreProfileJson.NumberToken ?: error(ERROR)
      check(token.text.matches(Regex("[1-9][0-9]{0,15}")))
      return (token.text.toLongOrNull() ?: error(ERROR)).also { check(it in 1..MAX_INTEGER) }
    }
  }
}
