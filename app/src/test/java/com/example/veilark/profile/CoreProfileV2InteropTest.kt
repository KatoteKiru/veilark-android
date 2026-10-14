package com.example.veilark.profile

import com.example.veilark.security.Ed25519Verifier
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Frozen public RFC fixture only. No fixture trust is installed in the app. */
@RunWith(Parameterized::class)
class CoreProfileV2InteropTest(private val id: String, private val vector: JsonObject) {
  @Test fun matchedIssuerAndAndroidEnvelopeAndPayloadVerdicts() {
    val expected = vector.getAsJsonObject("expected")
    assertEquals("NOT_EVALUATED", expected.get("admission").asString)
    val envelopeText = vector.get("envelope_utf8").asString
    // Math-only branch may parse duplicate JSON/padding; it never admits an envelope.
    val math = JsonParser.parseString(envelopeText).asJsonObject
    val payload = Base64.getDecoder().decode(math.get("payload_base64").asString)
    val signature = Base64.getDecoder().decode(math.get("signature_base64").asString)
    assertEquals("$id mathematical signature", expected.get("mathematical_signature_valid").asBoolean,
      Ed25519Verifier.verify(domain + payload, signature, publicKey))
    assertEquals("$id exact decoded bytes", vector.get("decoded_payload_sha256").asString, sha256(payload))
    val checked = try { CoreSignedEnvelope.checkSignature(envelopeText.toByteArray(Charsets.UTF_8), trust) }
      catch (_: IllegalArgumentException) { null }
    assertEquals("$id envelope boundary", expected.get("signature_boundary").asBoolean, checked != null)
    checked?.use { envelope ->
      val profile = try { CoreCarrierProfile.parse(envelope) }
        catch (_: IllegalArgumentException) { null }
      profile?.close()
      assertFalse("$id missing structural expectation", expected.get("payload_structure").isJsonNull)
      assertEquals("$id payload structure", expected.get("payload_structure").asBoolean, profile != null)
    }
    if (checked == null) assertTrue("$id unexpected payload verdict", expected.get("payload_structure").isJsonNull)
  }

  companion object {
    private const val FIXTURE_SHA256 = "2ee2fdec576518543a693ac7b3318808a68e7219a345eaed985cfa357055ef7d"
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it) }
    private val fixture: JsonObject = CoreProfileV2InteropTest::class.java
      .getResourceAsStream("/core-profile-v2-vectors.json")!!.use { stream ->
        val bytes = stream.readBytes()
        check(bytes.size == 35832 && sha256(bytes) == FIXTURE_SHA256)
        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
      }
    private val anchor = fixture.getAsJsonObject("fixture_trust")
    private val publicKey = Base64.getDecoder().decode(anchor.get("public_key_x509_base64").asString)
    private val trust = CoreSignedEnvelope.IssuerSnapshot(anchor.get("generation").asLong, listOf(
      CoreSignedEnvelope.IssuerSnapshot.Entry(anchor.get("issuer_id").asString, anchor.get("key_id").asString, publicKey)))
    private val domain = "VEILARK-CORE-PROFILE-V2\u0000".toByteArray(Charsets.US_ASCII)

    @JvmStatic @Parameterized.Parameters(name = "{0}")
    fun vectors(): Collection<Array<Any>> = fixture.getAsJsonArray("cases").map {
      val vector = it.asJsonObject
      arrayOf<Any>(vector.get("id").asString, vector)
    }.also { check(it.size == 16) }
  }
}
