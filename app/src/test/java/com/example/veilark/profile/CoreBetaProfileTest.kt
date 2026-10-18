package com.example.veilark.profile

import org.junit.Assert.*
import org.junit.Test

class CoreBetaProfileTest {
  private fun fixture(): String = """{
    "schema":"veilark-core-beta-profile-v1","name":"Test beta","engine":"original-v1","beta":true,
    "enrollment":{"schema":"veilark-original-enrollment-v1","protocol":"original-v1",
    "transport":"raw-stream-v1","endpoint":"198.51.100.20:8443","peer":1,
    "carrier":"${"11".repeat(32)}","initiator":"${"22".repeat(32)}","responder":"${"33".repeat(32)}",
    "psk_base64":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "client_ipv4":"10.203.44.1","server_ipv4":"10.203.44.2","prefix_length":30,"mtu":1280},
    "network":{"dns_ipv4":["1.1.1.1","8.8.8.8"],"lifetime_seconds":3595}}
    """.trimIndent()

  private fun reject(input: String) {
    try { CoreBetaProfile.parse(input); fail("Expected rejected profile") }
    catch (e: IllegalArgumentException) {
      assertEquals("Invalid Veilark Core beta profile.", e.message)
      assertNull(e.cause)
    }
  }

  @Test fun validProfileHasExplicitNetworkAndOrderedCopiedContext() {
    val raw = fixture()
    val profile = CoreBetaProfile.parse(raw.toByteArray(Charsets.UTF_8))
    assertEquals("Test beta", profile.name)
    assertEquals("198.51.100.20", profile.endpointIpv4)
    assertEquals(8443, profile.endpointPort)
    assertEquals(1, profile.peer)
    assertEquals("10.203.44.1", profile.clientIpv4)
    assertEquals("10.203.44.2", profile.serverIpv4)
    assertEquals(3595, profile.lifetimeSeconds)
    assertEquals(listOf("1.1.1.1", "8.8.8.8"), profile.dnsIpv4)
    assertArrayEquals(ByteArray(32), profile.keyCopy())
    assertArrayEquals(ByteArray(32) { 0x11 } + ByteArray(32) { 0x22 } + ByteArray(32) { 0x33 }, profile.contextCopy())
    profile.keyCopy()[0] = 7
    profile.contextCopy()[0] = 7
    assertEquals(0.toByte(), profile.keyCopy()[0])
    assertEquals(0x11.toByte(), profile.contextCopy()[0])
    assertEquals(raw, profile.validatedEnvelope())
    assertEquals("CoreBetaProfile(secret redacted)", profile.toString())
  }

  @Test fun duplicateDecodedKeysRejectedAtEveryLevel() {
    reject(fixture().replace("\"beta\":true", "\"beta\":false,\"beta\":true"))
    reject(fixture().replace("\"peer\":1", "\"peer\":2,\"peer\":1"))
    reject(fixture().replace("\"lifetime_seconds\":3595", "\"lifetime_seconds\":10,\"lifetime_seconds\":3595"))
    reject(fixture().replace("\"peer\":1", "\"p\\u0065er\":2,\"peer\":1"))
  }

  @Test fun unknownMissingAndMistypedFieldsRejected() {
    reject(fixture().replace("\"beta\":true", "\"beta\":true,\"extra\":1"))
    reject(fixture().replace("\"peer\":1", "\"peer\":1,\"extra\":1"))
    reject(fixture().replace("\"lifetime_seconds\":3595", "\"lifetime_seconds\":3595,\"extra\":1"))
    reject(fixture().replace("\"beta\":true", "\"beta\":\"true\""))
    reject(fixture().replace("\"peer\":1", "\"peer\":true"))
    reject(fixture().replace("\"peer\":1", "\"peer\":null"))
    reject(fixture().replace("\"beta\":true,", ""))
    reject(fixture().replace("original-v1", "other-v1"))
  }

  @Test fun integersMustBeLexicalIntegersAndWithinBounds() {
    reject(fixture().replace("33".repeat(32), "22".repeat(32)))
    for (bad in listOf("0", "65", "1.0", "1e0", "-1", "01", "99999999999999999999999"))
      reject(fixture().replace("\"peer\":1", "\"peer\":$bad"))
    for (bad in listOf("9", "3596", "10.0", "\"10\""))
      reject(fixture().replace("\"lifetime_seconds\":3595", "\"lifetime_seconds\":$bad"))
    assertEquals(10, CoreBetaProfile.parse(fixture().replace("3595", "10")).lifetimeSeconds)
    reject(fixture().replace("\"mtu\":1280", "\"mtu\":1500"))
    reject(fixture().replace("\"prefix_length\":30", "\"prefix_length\":24"))
  }

  @Test fun endpointAndReservedPrivatePairAreStrictLiterals() {
    for (bad in listOf("example.com:443", "[::1]:443", "198.51.100.20:0", "198.51.100.20:65536", "198.051.100.20:8443", "198.51.100.20:8443/path"))
      reject(fixture().replace("198.51.100.20:8443", bad))
    for (bad in listOf("10.203.44.0", "10.203.44.2", "10.203.44.3", "198.51.100.1", "010.203.44.1"))
      reject(fixture().replace("10.203.44.1", bad))
    reject(fixture().replace("10.203.44.2", "10.203.45.2"))
    for (pair in listOf("172.16.0", "172.31.255", "192.168.0"))
      assertNotNull(CoreBetaProfile.parse(fixture().replace("10.203.44", pair)))
    reject(fixture().replace("10.203.44", "172.32.0"))
  }

  @Test fun dnsIsDistinctBoundedAndUsableUnicast() {
    for (bad in listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "169.254.1.1", "224.0.0.1", "255.255.255.255", "dns.example", "01.1.1.1", "8.8.8.8"))
      reject(fixture().replace("1.1.1.1", bad))
    reject(fixture().replace("[\"1.1.1.1\",\"8.8.8.8\"]", "[]"))
    reject(fixture().replace("[\"1.1.1.1\",\"8.8.8.8\"]", "[\"1.1.1.1\",\"8.8.8.8\",\"9.9.9.9\",\"4.4.4.4\"]"))
    reject(fixture().replace("[\"1.1.1.1\",\"8.8.8.8\"]", "[true]"))
  }

  @Test fun credentialsExactCanonicalAndNeverEchoed() {
    reject(fixture().replace("11".repeat(32), "11".repeat(31)))
    reject(fixture().replace("22".repeat(32), "gg".repeat(32)))
    reject(fixture().replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB="))
    reject(fixture().replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
  }

  @Test fun nameUsesCodepointsAndRejectsControlsOrUnpairedSurrogates() {
    val emojiName = "\uD83D\uDE80".repeat(80)
    assertEquals(emojiName, CoreBetaProfile.parse(fixture().replace("Test beta", emojiName)).name)
    for (bad in listOf("", "a".repeat(81), "abc\\nxyz", "abc\\u0000xyz", "abc\\u2028xyz", "abc\\u200bxyz", "\\ud800"))
      reject(fixture().replace("Test beta", bad))
    reject(fixture().replace("Test beta", "\uD800"))
  }

  @Test fun syntaxUtf8SizeDepthAndTrailingDataAreBounded() {
    reject(fixture() + " {}")
    reject(fixture() + "x")
    reject(fixture().replace("\"beta\":true", "\"beta\":true,"))
    reject(fixture().replace("\"peer\":1", "\"peer\":+1"))
    reject(fixture().replace("\"peer\":1", "\"peer\":NaN"))
    reject(fixture().replace("Test beta", "\\x41"))
    reject(fixture().replace("\"beta\":true", "\"beta\":" + "[".repeat(20) + "true" + "]".repeat(20)))
    reject(" ".repeat(CoreBetaProfile.MAX_INPUT_BYTES) + fixture())
    reject(fixture().replace("Test beta", "界".repeat(6000)))
    try { CoreBetaProfile.parse(byteArrayOf(0xc3.toByte(), 0x28)); fail("Invalid UTF-8 accepted") }
    catch (e: IllegalArgumentException) { assertEquals("Invalid Veilark Core beta profile.", e.message) }
  }

  @Test fun persistenceCanDiscardEnvelopeWithoutDestroyingReconnectCredentials() {
    val raw = fixture()
    CoreBetaProfile.parse(raw).use { profile ->
      assertEquals(raw, profile.validatedEnvelope()) // Existing validate/persist contract.
      profile.discardEnvelope()
      try { profile.validatedEnvelope(); fail("Original enrollment JSON remains retained") }
      catch (failure: IllegalStateException) {
        assertEquals("Core profile envelope is unavailable.", failure.message)
      }
      assertEquals(32, profile.keyCopy().size)
      assertEquals(96, profile.contextCopy().size)
      assertEquals("Test beta", profile.name)
    }
  }

  @Test fun closeWipesOwnedBuffersIsIdempotentAndRejectsNewSecretCopies() {
    val expectedKey = ByteArray(32) { (it + 1).toByte() }
    val encodedKey = java.util.Base64.getEncoder().encodeToString(expectedKey)
    val profile = CoreBetaProfile.parse(fixture().replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", encodedKey))
    // Inspect the owned buffers, not caller copies: a no-op close must fail this test.
    val ownedKey = CoreBetaProfile::class.java.getDeclaredField("key").apply { isAccessible = true }.get(profile) as ByteArray
    val ownedContext = CoreBetaProfile::class.java.getDeclaredField("context").apply { isAccessible = true }.get(profile) as ByteArray
    val envelope = CoreBetaProfile::class.java.getDeclaredField("envelope").apply { isAccessible = true }
    val callerCopy = profile.keyCopy()
    assertArrayEquals(expectedKey, ownedKey)
    assertTrue(ownedContext.any { it != 0.toByte() })
    profile.close(); profile.close()
    assertTrue(ownedKey.all { it == 0.toByte() })
    assertTrue(ownedContext.all { it == 0.toByte() })
    assertNull(envelope.get(profile))
    assertArrayEquals(expectedKey, callerCopy) // JNI caller separately owns and wipes its copy.
    for (copy in listOf({ profile.keyCopy() }, { profile.contextCopy() })) {
      try { copy(); fail("Closed enrollment reused") }
      catch (failure: IllegalStateException) { assertEquals("Core profile is closed.", failure.message) }
    }
    callerCopy.fill(0)
  }

  @Test fun useClosesOwnedSecretsWhenOwnerThrows() {
    val profile = CoreBetaProfile.parse(fixture())
    try {
      profile.use { error("synthetic owner failure") }
    } catch (_: IllegalStateException) { }
    try { profile.contextCopy(); fail("Exception bypassed owner cleanup") }
    catch (failure: IllegalStateException) { assertEquals("Core profile is closed.", failure.message) }
  }
}
