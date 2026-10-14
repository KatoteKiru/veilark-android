package com.example.veilark.profile

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreSignedEnvelopeTest {
  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val entry = CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key1", pair.public.encoded)
  private val trust = CoreSignedEnvelope.IssuerSnapshot(1, listOf(entry))
  private val domain = "VEILARK-CORE-PROFILE-V2\u0000".toByteArray()
  private val payload = "{\"issuer_id\":\"fixture\",\"key_id\":\"key1\",\"note\":\"Тест\"}".toByteArray()
  private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
  private fun signed(bytes: ByteArray, prefix: ByteArray = domain): String {
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private); signer.update(prefix + bytes)
    return signer.sign().let { signature ->
      """{"schema":"veilark-core-signed-profile-v2","issuer_id":"fixture","key_id":"key1","payload_base64":"${encode(bytes)}","signature_base64":"${encode(signature)}"}"""
    }
  }
  private fun rejected(input: ByteArray, snapshot: CoreSignedEnvelope.IssuerSnapshot = trust) {
    try {
      CoreSignedEnvelope.checkSignature(input, snapshot).use { fail("Accepted invalid envelope") }
    } catch (expected: IllegalArgumentException) {
      assertEquals("Invalid signed Core envelope.", expected.message)
    }
  }

  @Test fun signatureBoundaryPreservesExactBytesAndOwnsCopies() {
    val input = signed(payload).toByteArray()
    val result = CoreSignedEnvelope.checkSignature(input, trust)
    input.fill(0)
    assertArrayEquals(payload, result.payloadCopy())
    result.payloadCopy().fill(0)
    assertArrayEquals(payload, result.payloadCopy())
    assertEquals(1L, result.trustGeneration)
    assertFalse(result.toString().contains("Тест"))
    result.close()
    assertThrows(IllegalStateException::class.java) { result.payloadCopy() }
  }

  @Test fun purposeSeparatorIsExactAndCrossPurposeSignaturesFail() {
    assertEquals(24, domain.size)
    for (prefix in listOf(byteArrayOf(), "VEILARK-CORE-PROFILE-V2".toByteArray(), "veilark-update-v2".toByteArray())) {
      rejected(signed(payload, prefix).toByteArray())
    }
    val valid = signed(payload)
    rejected(valid.replace(encode(payload), encode(payload + byteArrayOf(32))).toByteArray())
  }

  @Test fun duplicateDecodedKeysAndUnknownFieldsNeverOverwrite() {
    val valid = signed(payload)
    for (field in listOf("\"issuer_id\":\"fixture\",", "\"issuer\\u005fid\":\"fixture\",",
      "\"public_key\":\"ignored\",", "\"alg\":\"Ed25519\",")) {
      rejected(valid.replaceFirst("{", "{$field").toByteArray())
    }
    rejected(valid.replace("\"fixture\"", "null").toByteArray())
    rejected((valid + "{}").toByteArray())
    rejected(valid.replaceFirst("}", ",}").toByteArray())
  }

  @Test fun canonicalBase64AndLengthBoundsAreEnforced() {
    val valid = signed(payload)
    for (encoded in listOf(encode(payload).trimEnd('='), encode(payload) + " ", encode(payload).replace('/', '_'))) {
      if (encoded != encode(payload)) rejected(valid.replace(encode(payload), encoded).toByteArray())
    }
    rejected(signed(byteArrayOf()).toByteArray())
    rejected(signed(ByteArray(16385) { 65 }).toByteArray())
    rejected(ByteArray(24577) { 32 })
    val sig = Regex("\"signature_base64\":\"([^\"]+)\"").find(valid)!!.groupValues[1]
    rejected(valid.replace(sig, encode(ByteArray(63))).toByteArray())
    rejected(valid.replace(sig, encode(ByteArray(65))).toByteArray())
    rejected(signed(byteArrayOf(65)).replace("\"QQ==\"", "\"QR==\"").toByteArray()) // Same decoded byte, noncanonical pad bits.
    val maximum = ByteArray(16384) { 65 }
    CoreSignedEnvelope.checkSignature(signed(maximum).toByteArray(), trust).use {
      assertArrayEquals(maximum, it.payloadCopy()) // Signature boundary, deliberately not a JSON profile.
    }
  }

  @Test fun strictUtf8BomAndSurrogatesRejectWithGenericError() {
    val valid = signed(payload)
    rejected(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
    rejected(("\uFEFF" + valid).toByteArray())
    rejected(valid.replace("\"fixture\"", "\"\\ud800\"").toByteArray())
    rejected(signed(byteArrayOf(0xc0.toByte(), 0xaf.toByte())).toByteArray())
    rejected(signed("\uFEFF{}".toByteArray()).toByteArray())
  }

  @Test fun trustIsSnapshotCopiedAndUnknownIdsDoNotIntroduceKeys() {
    val key = pair.public.encoded
    val copied = CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key1", key)
    key.fill(0)
    val mutable = mutableListOf(copied)
    val snapshot = CoreSignedEnvelope.IssuerSnapshot(2, mutable)
    mutable.clear()
    CoreSignedEnvelope.checkSignature(signed(payload).toByteArray(), snapshot).use {
      assertEquals(2L, it.trustGeneration)
    }
    rejected(signed(payload).replace("\"fixture\"", "\"unknown\"").toByteArray())
    val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    rejected(signed(payload).toByteArray(), CoreSignedEnvelope.IssuerSnapshot(1,
      listOf(CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key1", other.public.encoded))))
  }

  @Test fun trustSnapshotRejectsDuplicateIdsAndUnboundedLists() {
    assertThrows(IllegalArgumentException::class.java) { CoreSignedEnvelope.IssuerSnapshot(1, listOf(entry, entry)) }
    assertThrows(IllegalArgumentException::class.java) { CoreSignedEnvelope.IssuerSnapshot(0, listOf(entry)) }
    assertThrows(IllegalArgumentException::class.java) { CoreSignedEnvelope.IssuerSnapshot(1, emptyList()) }
    val entries = (0..64).map { CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key$it", pair.public.encoded) }
    assertThrows(IllegalArgumentException::class.java) { CoreSignedEnvelope.IssuerSnapshot(1, entries) }
  }
}
