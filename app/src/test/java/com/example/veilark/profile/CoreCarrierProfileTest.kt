package com.example.veilark.profile

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreCarrierProfileTest {
  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val trust = CoreSignedEnvelope.IssuerSnapshot(7, listOf(
    CoreSignedEnvelope.IssuerSnapshot.Entry("fixture", "key1", pair.public.encoded)))
  private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
  private val valid = """{
    "schema":"veilark-core-carrier-profile-v2","engine":"original-v1","beta":true,"name":"Тест",
    "issuer_id":"fixture","key_id":"key1","profile_id":"${"a".repeat(32)}","revision":1,
    "issued_at":2200000000,"not_before":2200000000,"expires_at":2200003600,
    "carrier":{"kind":"tls13-original-stream-v1","connect_ipv4":"203.0.113.1","connect_port":443,
      "server_name":"origin.fixture.test","tls_version":"TLSv1.3","early_data":false,"alpn":[],
      "connect_timeout_ms":5000,"tls_timeout_ms":5000,"pair_timeout_ms":1000,"teardown_timeout_ms":2000,"buffer_bytes":16384},
    "enrollment":{"schema":"veilark-original-enrollment-v2","protocol":"original-v1","transport":"tls13-original-stream-v1",
      "endpoint":"203.0.113.1:443","peer":1,"carrier":"${"1".repeat(64)}","initiator":"${"2".repeat(64)}","responder":"${"3".repeat(64)}",
      "psk_base64":"$key","client_ipv4":"10.8.0.1","server_ipv4":"10.8.0.2","prefix_length":30,"mtu":1280},
    "network":{"dns_ipv4":["1.1.1.1","77.88.8.8"],"lifetime_seconds":1799}}
  """.trimIndent()
  private fun parse(payload: String): CoreCarrierProfile {
    val bytes = payload.toByteArray()
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private); signer.update("VEILARK-CORE-PROFILE-V2\u0000".toByteArray() + bytes)
    val b64 = Base64.getEncoder()
    val envelope = """{"schema":"veilark-core-signed-profile-v2","issuer_id":"fixture","key_id":"key1","payload_base64":"${b64.encodeToString(bytes)}","signature_base64":"${b64.encodeToString(signer.sign())}"}"""
    return CoreSignedEnvelope.checkSignature(envelope.toByteArray(), trust).use { CoreCarrierProfile.parse(it) }
  }
  private fun rejected(payload: String) {
    try { parse(payload).use { fail("Invalid signed payload accepted") } }
    catch (expected: IllegalArgumentException) { assertEquals("Invalid signed Core payload.", expected.message) }
  }

  @Test fun parsesExactV2IncludingPost2038LongTimesAndOwnedSecrets() {
    val profile = parse(valid)
    assertEquals(2200000000L, profile.issuedAt)
    assertEquals(7L, profile.trustGeneration)
    assertEquals("origin.fixture.test", profile.carrier.serverName)
    assertEquals(443, profile.carrier.port)
    assertEquals(96, profile.contextCopy().size)
    assertArrayEquals(ByteArray(32) { 7 }, profile.keyCopy())
    profile.keyCopy().fill(0)
    assertArrayEquals(ByteArray(32) { 7 }, profile.keyCopy())
    assertFalse(profile.toString().contains(key))
    assertThrows(UnsupportedOperationException::class.java) { (profile.dnsIpv4 as MutableList<String>).clear() }
    profile.close()
    assertThrows(IllegalStateException::class.java) { profile.keyCopy() }
  }

  @Test fun signedIdsAndAllExactSchemasAreBound() {
    for ((before, after) in listOf("\"issuer_id\":\"fixture\"" to "\"issuer_id\":\"other\"",
      "\"key_id\":\"key1\"" to "\"key_id\":\"key2\"",
      "veilark-core-carrier-profile-v2" to "veilark-core-beta-profile-v1",
      "veilark-original-enrollment-v2" to "veilark-original-enrollment-v1",
      "\"beta\":true" to "\"beta\":false")) rejected(valid.replace(before, after))
    rejected(valid.replaceFirst("{", "{\"unknown\":true,"))
    rejected(valid.replace("\"mtu\":1280", "\"mtu\":1280,\"extra\":0"))
    rejected(valid.replace("\"lifetime_seconds\":1799", "\"lifetime_seconds\":1799,\"extra\":0"))
    rejected(valid.replace("\"buffer_bytes\":16384", "\"buffer_bytes\":16384,\"extra\":0"))
  }

  @Test fun lexicalNumbersRejectOverflowWrappingFractionsAndSignedTokens() {
    for (number in listOf("-1", "+1", "01", "1.0", "1e0", "2147483648", "9223372036854775808")) {
      rejected(valid.replace("\"revision\":1", "\"revision\":$number"))
    }
    for (number in listOf("0", "4102444801", "18446744073709551616", "2200000000.0", "22e8")) {
      rejected(valid.replace("\"issued_at\":2200000000", "\"issued_at\":$number"))
    }
    parse(valid.replace("\"revision\":1", "\"revision\":2147483647")).close()
  }

  @Test fun structuralTimeAndIdentityBoundsAreStrictWithoutClaimingCurrentAdmission() {
    rejected(valid.replace("\"not_before\":2200000000", "\"not_before\":2200000001"))
    for (expiry in listOf("2200000000", "2199999999", "2202592001")) {
      rejected(valid.replace("\"expires_at\":2200003600", "\"expires_at\":$expiry"))
    }
    rejected(valid.replace("\"profile_id\":\"${"a".repeat(32)}\"", "\"profile_id\":\"${"A".repeat(32)}\""))
    parse(valid.replace("\"expires_at\":2200003600", "\"expires_at\":2202592000")).close()
  }

  @Test fun exactEpochEndpointsAndOneSecondValidityDoNotWrap() {
    for (bad in listOf("-2200000000", "+2200000000", "02200000000")) {
      rejected(valid.replace("\"issued_at\":2200000000", "\"issued_at\":$bad"))
    }
    parse(valid.replace("2200000000", "1").replace("2200003600", "2")).use {
      assertEquals(1L, it.issuedAt); assertEquals(2L, it.expiresAt)
    }
    parse(valid.replace("2200000000", "4102444799").replace("2200003600", "4102444800")).use {
      assertEquals(4102444800L, it.expiresAt)
    }
  }

  @Test fun tlsEndpointAndBudgetSettingsCannotDowngradeOrExpand() {
    for ((before, after) in listOf("TLSv1.3" to "TLSv1.2", "\"early_data\":false" to "\"early_data\":true",
      "\"alpn\":[]" to "\"alpn\":[\"h2\"]", "\"buffer_bytes\":16384" to "\"buffer_bytes\":32768",
      "\"endpoint\":\"203.0.113.1:443\"" to "\"endpoint\":\"203.0.113.1:24443\"",
      "tls13-original-stream-v1" to "raw-stream-v1", "\"teardown_timeout_ms\":2000" to "\"teardown_timeout_ms\":5001")) {
      rejected(valid.replace(before, after))
    }
    rejected(valid.replace("\"connect_timeout_ms\":5000", "\"connect_timeout_ms\":10000")
      .replace("\"tls_timeout_ms\":5000", "\"tls_timeout_ms\":10000"))
    for (host in listOf("localhost", "ORIGIN.fixture.test", "origin.fixture.test.", "*.fixture.test", "-bad.test", "bad-.test", "a..test",
      "1.2.3.4", "1.2.3.004", "127.1", "0x7f.0x1", "[::1]")) {
      rejected(valid.replace("origin.fixture.test", host))
    }
  }

  @Test fun enrollmentAndDnsRemainCanonicalAndBounded() {
    for ((before, after) in listOf("\"peer\":1" to "\"peer\":65", "\"mtu\":1280" to "\"mtu\":1500",
      "\"prefix_length\":30" to "\"prefix_length\":32", "10.8.0.1" to "8.8.8.1", "10.8.0.2" to "10.8.0.3",
      "\"responder\":\"${"3".repeat(64)}\"" to "\"responder\":\"${"2".repeat(64)}\"",
      "\"carrier\":\"${"1".repeat(64)}\"" to "\"carrier\":\"${"A".repeat(64)}\"",
      "\"dns_ipv4\":[\"1.1.1.1\",\"77.88.8.8\"]" to "\"dns_ipv4\":[\"1.1.1.1\",\"1.1.1.1\"]")) rejected(valid.replace(before, after))
    for (ip in listOf("0.1.2.3", "127.0.0.1", "169.254.0.1", "224.0.0.1", "203.0.113.01")) {
      rejected(valid.replace("203.0.113.1", ip))
    }
    rejected(valid.replace(key, Base64.getEncoder().encodeToString(ByteArray(31) { 7 })))
    rejected(valid.replace(key, Base64.getEncoder().encodeToString(ByteArray(32)))) // Native rejects WeakPsk.
    // Endpoint syntax must not infer a directed broadcast without a subnet mask.
    parse(valid.replace("203.0.113.1", "203.0.113.255")).close()
  }

  @Test fun strictSharedLexerRejectsDuplicateEscapedKeysTrailingDataAndInvalidUnicode() {
    rejected(valid.replaceFirst("{", "{\"revision\":1,"))
    rejected(valid.replaceFirst("{", "{\"revis\\u0069on\":1,"))
    rejected(valid + "{}")
    rejected(valid.replace("\"name\":\"Тест\"", "\"name\":\"\\ud800\""))
    rejected(valid.replace("\"name\":\"Тест\"", "\"name\":\"\\u0000\""))
    rejected(valid.replace("\"name\":\"Тест\"", "\"name\":\"${"x".repeat(81)}\""))
  }
}
