package com.example.veilark.profile

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Public synthetic data only. These keys must never be installed as app trust. */
class EnrollmentV2DraftConsumerTest {
  private val fixture: Map<*, *> by lazy {
    var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    var file: File? = null
    while (directory != null) {
      val candidate = File(directory, "docs/fixtures/enrollment-v2-draft-20261023.json")
      if (candidate.isFile) { file = candidate; break }
      directory = directory.parentFile
    }
    CoreProfileJson.parse(requireNotNull(file) { "Missing public draft fixture" }.readText(Charsets.UTF_8)) as Map<*, *>
  }
  private val valid get() = fixture["valid"] as Map<*, *>
  private val inputs get() = fixture["public_test_inputs"] as Map<*, *>
  private val uri get() = valid["uri"] as String
  private val now get() = (inputs["clock_epoch"] as CoreProfileJson.NumberToken).text.toLong()
  private val wire get() = (valid["jwe_ascii"] as String).toByteArray(Charsets.US_ASCII)
  private val trust get() = mapOf(("fixture-only" to "fixture-only") to
    (hex("302a300506032b6570032100") + hex(inputs["ed25519_public_hex"] as String)))

  @Test fun independentlyConsumesPythonBytesAndOwnsRedactedCopies() {
    EnrollmentV2DraftConsumer.verify(uri, wire, trust, now).use { result ->
      assertEquals("fixture-only", result.issuer)
      assertEquals("fixture-only", result.keyId)
      assertEquals("fixture-profile", result.profileId)
      assertEquals(1L, result.revision)
      assertEquals(1792713600L, result.issuedAt)
      assertEquals(1792713600L, result.notBefore)
      assertEquals(1792800000L, result.expiresAt)
      assertFalse(result.relayRoute)
      val profile = result.profileCopy()
      assertArrayEquals((valid["profile_utf8"] as String).toByteArray(), profile)
      profile.fill(0)
      assertArrayEquals((valid["profile_utf8"] as String).toByteArray(), result.profileCopy())
      assertArrayEquals(hex(valid["signed_content_sha256"] as String), result.signedContentDigestCopy())
      assertEquals("EnrollmentV2DraftConsumer(verified draft; secrets redacted)", result.toString())
      assertFalse(result.toString().contains(uri))
      result.close()
      assertThrows(IllegalStateException::class.java) { result.profileCopy() }
      assertThrows(IllegalStateException::class.java) { result.signedContentDigestCopy() }
    }
  }

  @Test fun sharedMaliciousKnownCekAndSemanticVectorsReject() {
    val negatives = fixture["negative"] as List<*>
    assertEquals(4, negatives.size)
    for (value in negatives) {
      val entry = value as Map<*, *>
      val link = if (entry.containsKey("locator"))
        uri.replace(uri.substringAfter("?t=").substringBefore('#'), entry["locator"] as String) else uri
      val clock = (entry["clock_epoch"] as? CoreProfileJson.NumberToken)?.text?.toLong() ?: now
      rejects(link, (entry["jwe_ascii"] as String).toByteArray(Charsets.US_ASCII), trust, clock)
    }
  }

  @Test fun missingOrUnknownTrustAndUnusableClockReject() {
    rejects(keys = emptyMap())
    rejects(keys = mapOf(("other-issuer" to "fixture-only") to trust.values.first()))
    rejects(keys = mapOf(("fixture-only" to "other-key") to trust.values.first()))
    rejects(keys = mapOf(("fixture-only" to "fixture-only") to ByteArray(44)))
    rejects(clock = 0)
    rejects(clock = 9_007_199_254_740_992L)
  }

  @Test fun canonicalLinkAndEnvelopeLimitsRejectBeforeFurtherProcessing() {
    rejects(link = "$uri&extra=1")
    rejects(link = uri.replace("#k=", "#k=%"))
    // Last CEK symbol has unused low bits; alternate spelling decodes identically.
    rejects(link = uri.dropLast(1) + "9")
    val parts = (valid["jwe_ascii"] as String).split('.').toMutableList()
    parts[2] += "="
    rejects(bytes = parts.joinToString(".").toByteArray())
    rejects(bytes = ByteArray(65_537) { 'A'.code.toByte() })
    rejects(bytes = wrap(ByteArray(34_001) { 'A'.code.toByte() }))
    rejects(bytes = wire.copyOf(wire.size - 1))
  }

  @Test fun authenticatedWrongPurposeDuplicateAndUnsupportedProfileReject() {
    val accepted = signedCandidate()
    EnrollmentV2DraftConsumer.verify(uri, accepted.first, accepted.second, now).close()
    signedReject(payload = (valid["payload_utf8"] as String).replace("veilark.profile.enrollment.v2", "wrong-purpose"))
    signedReject(header = (valid["protected_header_utf8"] as String).replace("Ed25519", "EdDSA"))
    signedReject(header = (valid["protected_header_utf8"] as String).replace("fixture-only", "bad.key"))
    signedReject(payload = "{\"\\u0076\":2," + (valid["payload_utf8"] as String).drop(1))
    val oldProfile = b64((valid["profile_utf8"] as String).toByteArray())
    val unsupported = (valid["profile_utf8"] as String).replace("veilark-argon-profile-v1", "veilark-argon-profile-v2")
    signedReject(payload = (valid["payload_utf8"] as String).replace(oldProfile, b64(unsupported.toByteArray())))
    signedReject(payload = (valid["payload_utf8"] as String).replace("\"revision\":1", "\"revision\":1e0"))
    signedReject(payload = (valid["payload_utf8"] as String).replace("1792800000", "1795305601"))
    signedReject(payload = " ".repeat(24_577))
    val malformed = signedCandidate(payloadBytes = byteArrayOf(0xc3.toByte(), 0x28))
    rejects(bytes = malformed.first, keys = malformed.second)
    val duplicateProfile = "{\"schema\":\"veilark-argon-profile-v1\"," + (valid["profile_utf8"] as String).drop(1)
    signedReject(payload = (valid["payload_utf8"] as String).replace(oldProfile, b64(duplicateProfile.toByteArray())))
  }

  @Test fun signedPassageProfileIsFlaggedAndMayCarryLabRelayCredentials() {
    val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    val passage = """{"schema":"veilark-argon-relay-profile-v1","engine":"argon-v1","beta":true,"name":"Passage lab",""" +
      """"exits":[{"ipv4":"192.0.2.1","port":3478}],"relays":[{"uri":"turn:192.0.2.9",""" +
      """"credential":{"source":"static","username":"lab","password":"secret"}}],""" +
      """"identity":{"client_static_private_base64":"$key","server_static_public_base64":"$key"},""" +
      """"network":{"mtu":1280,"dns_ipv4":["1.1.1.1"]}}"""
    val oldProfile = b64((valid["profile_utf8"] as String).toByteArray())
    val signed = signedCandidate((valid["payload_utf8"] as String).replace(oldProfile, b64(passage.toByteArray())))
    EnrollmentV2DraftConsumer.verify(uri, signed.first, signed.second, now).use {
      assertTrue(it.relayRoute)
      assertArrayEquals(passage.toByteArray(), it.profileCopy())
    }
  }

  @Test fun importWindowSkewHasExactBoundaries() {
    EnrollmentV2DraftConsumer.verify(uri, wire, trust, 1792713300L).close()
    rejects(clock = 1792713299L)
    EnrollmentV2DraftConsumer.verify(uri, wire, trust, 1792799999L).close()
    rejects(clock = 1792800000L)
  }

  private fun signedReject(
    payload: String = valid["payload_utf8"] as String,
    header: String = valid["protected_header_utf8"] as String,
  ) {
    val candidate = signedCandidate(payload, header)
    rejects(bytes = candidate.first, keys = candidate.second)
  }

  private fun signedCandidate(
    payload: String = valid["payload_utf8"] as String,
    header: String = valid["protected_header_utf8"] as String,
    payloadBytes: ByteArray = payload.toByteArray(Charsets.UTF_8),
  ): Pair<ByteArray, Map<Pair<String, String>, ByteArray>> {
    // Fresh synthetic JDK key: test issuer only, independently verifies Tink/JDK bytes.
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val signingInput = b64(header.toByteArray(Charsets.UTF_8)) + "." + b64(payloadBytes)
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private)
    signer.update(signingInput.toByteArray(Charsets.US_ASCII))
    val jws = (signingInput + "." + b64(signer.sign())).toByteArray(Charsets.US_ASCII)
    return wrap(jws) to mapOf(("fixture-only" to "fixture-only") to pair.public.encoded)
  }

  private fun wrap(plaintext: ByteArray): ByteArray {
    // Deliberate fixture-only key/nonce reuse; all material is already public.
    val aad = valid["aad_ascii"] as String
    val iv = hex(inputs["iv_hex"] as String)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(inputs["cek_hex"] as String), "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(aad.toByteArray(Charsets.US_ASCII))
    val encrypted = cipher.doFinal(plaintext)
    return "$aad..${b64(iv)}.${b64(encrypted.copyOfRange(0, encrypted.size - 16))}.${b64(encrypted.takeLast(16).toByteArray())}"
      .toByteArray(Charsets.US_ASCII)
  }

  private fun rejects(
    link: String = uri,
    bytes: ByteArray = wire,
    keys: Map<Pair<String, String>, ByteArray> = trust,
    clock: Long = now,
  ) {
    val failure = assertThrows(IllegalArgumentException::class.java) {
      EnrollmentV2DraftConsumer.verify(link, bytes, keys, clock).close()
    }
    assertEquals("Invalid draft enrollment.", failure.message)
    assertNull(failure.cause)
  }
  private fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
  private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
