package com.example.veilark.profile

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class CoreArgonRelayProfileTest {
  private val client = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
  private val server = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
  private val service = """{"uri":"turn:relay.example.net:3478?transport=udp","credential":{"source":"service","service":"telemost","parameters":{"room":"abc"}}}"""
  private fun profile(relays: String = "[$service]") = """{"schema":"veilark-argon-relay-profile-v1","engine":"argon-v1","beta":true,"name":"Relay test","exits":[{"ipv4":"192.0.2.1","port":443},{"ipv4":"192.0.2.2","port":443}],"relays":$relays,"identity":{"client_static_private_base64":"$client","server_static_public_base64":"$server"},"network":{"mtu":1360,"dns_ipv4":["1.1.1.1"]}}"""

  private fun rejected(text: String, allowStatic: Boolean = false) {
    try { CoreArgonRelayProfile.parse(text, allowStatic).close(); fail("Must reject invalid profile") }
    catch (error: IllegalArgumentException) { assertEquals("Invalid Veilark Core profile.", error.message) }
  }

  @Test fun unusableRelaysAreSkippedAndCountedButNeverTheOnlyOnes() {
    val tcp = """{"uri":"turn:relay.example.net:443?transport=tcp","credential":{"source":"service","service":"telemost"}}"""
    val tls = """{"uri":"turns:relay.example.net:443","credential":{"source":"service","service":"telemost"}}"""
    CoreArgonRelayProfile.parse(profile("[$tcp,$service,$tls]"), false).use {
      assertEquals(1, it.relays.size)
      assertEquals(2, it.skippedRelays)
      assertEquals(2, it.exits.size)
      val credential = it.relays.single().credential as CoreArgonRelayProfile.Credential.Service
      assertEquals("telemost", credential.service)
      assertEquals(mapOf("room" to "abc"), credential.parameters)
    }
    rejected(profile("[$tcp,$tls]"))
  }

  @Test fun staticCredentialsAreAcceptedOnlyWhereAllowed() {
    val static = """{"uri":"turn:192.0.2.9","credential":{"source":"static","username":"lab","password":"secret"}}"""
    rejected(profile("[$static]"), allowStatic = false)
    CoreArgonRelayProfile.parse(profile("[$static]"), true).use {
      val credential = it.relays.single().credential as CoreArgonRelayProfile.Credential.Static
      credential.withBytes { user, password ->
        assertEquals("lab", String(user, Charsets.US_ASCII)); assertEquals("secret", String(password, Charsets.US_ASCII))
      }
    }
  }

  @Test fun directProfileFieldsAndUnknownKeysAreRejected() {
    rejected(profile().replace("\"exits\":", "\"endpoint\":{\"ipv4\":\"192.0.2.1\",\"port\":443},\"exits\":"))
    rejected(profile().replace("veilark-argon-relay-profile-v1", "veilark-argon-profile-v1"))
    rejected(profile().replace("\"service\":\"telemost\"", "\"service\":\"Telemost\""))
    rejected(profile("[]"))
  }
}
