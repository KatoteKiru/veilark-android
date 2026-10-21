package com.example.veilark.profile

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreArgonProfileTest {
  private val client = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
  private val server = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
  private fun profile() = """{"schema":"veilark-argon-profile-v1","engine":"argon-v1","beta":true,"name":"Argon test","endpoint":{"ipv4":"192.0.2.1","port":443},"identity":{"client_static_private_base64":"$client","server_static_public_base64":"$server"},"network":{"mtu":1360,"dns_ipv4":["1.1.1.1","77.88.8.8"]}}"""
  private fun rejected(text: String) {
    try { CoreArgonProfile.parse(text).close(); fail("Must reject invalid profile") }
    catch (error: IllegalArgumentException) {
      assertEquals("Invalid Veilark Core profile.", error.message)
      assertFalse(error.toString().contains(client)); assertFalse(error.toString().contains(server))
    }
  }
  private val wire = "\"wire_profile\":{\"id\":2,\"feedback_version\":1}"
  private fun profileV2() = profile().replace("veilark-argon-profile-v1", "veilark-argon-profile-v2")
    .replace("\"engine\":", "$wire,\"engine\":")

  @Test fun wireProfileIsExplicitAndLegacyEnvelopeCannotSelectNewProtocol() {
    CoreArgonProfile.parse(profile()).use {
      assertEquals(CoreArgonHandshakeProfile.LEGACY, it.handshakeProfile)
      assertEquals(0, it.handshakeProfile.wireProfile)
    }
    CoreArgonProfile.parse(profileV2()).use {
      assertEquals(CoreArgonHandshakeProfile.CARRIER2_LAB1, it.handshakeProfile)
      assertEquals(2, it.handshakeProfile.wireProfile)
      assertEquals(1, it.handshakeProfile.feedbackVersion)
    }
    for (bad in listOf(
      profileV2().replace("veilark-argon-profile-v2", "veilark-argon-profile-v1"),
      profileV2().replace("$wire,", ""),
      profileV2().replace(wire, "\"wire_profile\":\"carrier2-lab1\""),
      profileV2().replace(wire, "\"wire_profile\":1"),
      profileV2().replace("\"id\":2", "\"id\":1"),
      profileV2().replace("\"id\":2", "\"id\":2.0"),
      profileV2().replace("\"feedback_version\":1", "\"feedback_version\":0"),
      profileV2().replace("\"feedback_version\":1", "\"feedback_version\":\"1\""),
      profileV2().replace("\"feedback_version\":1", "\"feedback_version\":1,\"extra\":true"),
      profileV2().replace("$wire,", "$wire,$wire,"),
    )) rejected(bad)
  }
  @Test fun ownsIndependentSecretCopiesAndClosesWithoutLoggingEnvelope() {
    val p = CoreArgonProfile.parse(profile().toByteArray())
    assertEquals("192.0.2.1",p.endpointIpv4);assertEquals(443,p.endpointPort)
    assertEquals(1360,p.mtu);assertEquals(listOf("1.1.1.1","77.88.8.8"),p.dnsIpv4)
    assertEquals("CoreArgonProfile(secret redacted)",p.toString())
    val copied=p.clientStaticPrivateCopy();copied.fill(0)
    assertArrayEquals(ByteArray(32){1},p.clientStaticPrivateCopy())
    assertArrayEquals(ByteArray(32){2},p.serverStaticPublicCopy())
    p.discardEnvelope()
    try { p.validatedEnvelope();fail("Envelope should be discarded") } catch (_: IllegalStateException) { }
    p.close();p.close()
    try { p.clientStaticPrivateCopy();fail("Closed profile") } catch (_: IllegalStateException) { }
    try { p.serverStaticPublicCopy();fail("Closed profile") } catch (_: IllegalStateException) { }
  }
  @Test fun identityIsNotConvertedFromLegacyPskOrTrustedBeforeHandshake() {
    for (bad in listOf(
      profile().replace("veilark-argon-profile-v1","veilark-core-beta-profile-v1"),
      profile().replace("argon-v1","original-v1"),
      profile().replace("\"beta\":true","\"beta\":false"),
      profile().replace("client_static_private_base64","psk_base64"),
      profile().replace("\"mtu\":1360","\"assigned_ipv4\":\"10.0.0.1\",\"mtu\":1360"),
    )) rejected(bad)
  }
  @Test fun rejectsDuplicateUnknownAndMissingFieldsAtEveryBoundary() {
    for (bad in listOf(
      profile().replace("\"name\":","\"beta\":true,\"name\":"),
      profile().replace("\"endpoint\":{","\"endpoint\":{\"ipv4\":\"192.0.2.2\","),
      profile().replace("\"identity\":{","\"identity\":{\"extra\":1,"),
      profile().replace("\"network\":{","\"network\":{\"extra\":1,"),
      profile().replace(",\"beta\":true", ""),
      profile().replace(",\"server_static_public_base64\":\"$server\"", ""),
    )) rejected(bad)
  }
  @Test fun rejectsNonCanonicalOrEmptyKeys() {
    val zero=Base64.getEncoder().encodeToString(ByteArray(32))
    for (key in listOf(zero,client.dropLast(1),client.dropLast(2)+"F=",client+"="," $client")) {
      rejected(profile().replace(client,key))
    }
    rejected(profile().replace(server,zero))
  }
  @Test fun validatesExactIntegerMtuAndNumericUnicastAddresses() {
    CoreArgonProfile.parse(profile().replace("1360","1280")).close()
    for (mtu in listOf("1279","1361","1280.0","1e3","9223372036854775808")) rejected(profile().replace("1360",mtu))
    for (port in listOf("0","65536","443.0","\"443\"")) rejected(profile().replace("\"port\":443","\"port\":$port"))
    for (ip in listOf("0.0.0.0","127.0.0.1","224.1.1.1","255.255.255.255","192.000.2.1","2001:db8::1","vpn.example")) {
      rejected(profile().replace("192.0.2.1",ip))
    }
  }
  @Test fun dnsAndNameAreBoundedAndReturnedListIsImmutable() {
    rejected(profile().replace("\"1.1.1.1\",\"77.88.8.8\"","\"1.1.1.1\",\"1.1.1.1\""))
    rejected(profile().replace("\"1.1.1.1\",\"77.88.8.8\"",""))
    rejected(profile().replace("Argon test",""));rejected(profile().replace("Argon test","x".repeat(81)))
    rejected(profile().replace("Argon test","\\u202e"))
    val p=CoreArgonProfile.parse(profile())
    try { (p.dnsIpv4 as MutableList<String>).add("8.8.8.8");fail("Immutable DNS") }
    catch (_: UnsupportedOperationException) { } finally { p.close() }
  }
  @Test fun invalidUtf8AndOversizedInputAreRejectedWithoutSecretErrors() {
    for (bytes in listOf(byteArrayOf(0xc0.toByte(),0x80.toByte()),ByteArray(16385))) {
      try { CoreArgonProfile.parse(bytes).close();fail("Invalid input") }
      catch (error: IllegalArgumentException) { assertEquals("Invalid Veilark Core profile.",error.message) }
    }
    rejected(" ".repeat(16385))
  }
}
