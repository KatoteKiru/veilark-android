package com.example.veilark.core.runtime

import com.example.veilark.profile.CoreArgonRelayProfile
import java.io.FileDescriptor
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreCoturnRestCredentialsTest {
  private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
  private val binding = object : CoreFairwayNetworkBinding {
    override fun isUsable() = true
    override fun bind(fd: FileDescriptor) = Unit
    override fun protect(fd: Int) = true
  }
  private val relay = CoreArgonRelayProfile.parse("""{"schema":"veilark-argon-relay-profile-v1","engine":"argon-v1","beta":true,"name":"Passage","exits":[{"ipv4":"192.0.2.1","port":443}],"relays":[{"uri":"turn:192.0.2.9:3478?transport=udp","credential":{"source":"service","service":"coturn-rest","parameters":{"username":"1700086400:p","password":"c2VjcmV0+/="}}}],"identity":{"client_static_private_base64":"$key","server_static_public_base64":"$key"},"network":{"mtu":1280,"dns_ipv4":["1.1.1.1"]}}""", false)
    .use { it.relays.single() }
  private val parameters = (relay.credential as CoreArgonRelayProfile.Credential.Service).parameters

  private fun fetch(wallSeconds: Long, parameters: Map<String, String> = this.parameters) = runBlocking {
    CoreCoturnRestCredentials { wallSeconds * 1000 }.fetch(relay, parameters, binding, nowMillis = 5_000)
  }

  @Test fun grantRunsUntilTheExpiryInTheUsernameAndAtMostADay() {
    fetch(1700086400 - 600).use { grant ->
      assertEquals(600_000L, grant.remainingMillis(5_000))
      grant.withCredentialBytes(5_000) { user, password, _ ->
        assertEquals("1700086400:p", String(user)); assertEquals("c2VjcmV0+/=", String(password))
      }
    }
    fetch(1700086400 - 30L * 86_400).use { assertEquals(86_400_000L, it.remainingMillis(5_000)) }
  }

  @Test fun expiredOrMalformedCredentialsNeverReachTheCore() {
    for (wall in listOf(1700086400L, 1700086401L)) {
      assertThrows(IllegalArgumentException::class.java) { fetch(wall) }
    }
    for (username in listOf("p:1700086400", "01700086400:p", "1700086400", ":p")) {
      assertThrows(IllegalArgumentException::class.java) { fetch(0, parameters + ("username" to username)) }
    }
    assertThrows(IllegalArgumentException::class.java) { fetch(0, parameters - "password") }
  }
}
