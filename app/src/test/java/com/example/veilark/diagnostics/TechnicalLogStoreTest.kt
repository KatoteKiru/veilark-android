package com.example.veilark.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalLogStoreTest {
  // Exercise the actual persistence/redaction boundary without starting the disk writer.
  private fun safe(value: String, limit: Int = 800): String =
    TechnicalLogStore::class.java.getDeclaredMethod("safe", String::class.java, Int::class.javaPrimitiveType)
      .apply { isAccessible = true }.invoke(TechnicalLogStore, value, limit) as String

  private fun decode(value: String): TechnicalLogEntry? =
    TechnicalLogStore::class.java.getDeclaredMethod("decode", String::class.java)
      .apply { isAccessible = true }.invoke(TechnicalLogStore, value) as TechnicalLogEntry?

  @Test fun supportedProfileSchemesMaskTheEntireCredentialPayload() {
    for (scheme in listOf("tt", "vless", "trojan", "hysteria", "hysteria2", "hy2", "vmess", "ss", "shadowsocks", "tuic", "anytls")) {
      val result = safe("Import ${scheme.uppercase()}://synthetic-user:synthetic-secret@example.invalid:443?token=synthetic-query#synthetic-fragment failed")
      assertEquals("$scheme", "Import <profile-link-redacted> failed", result)
    }
  }

  @Test fun bearerTokensMaskDotsPaddingAndFragmentsWithoutRemovingUsefulContext() {
    for (value in listOf("synthetic.jwt.signature", "synthetic+/token==", "synthetic-token#fragment")) {
      val result = safe("api.example.invalid Authorization: bEaReR $value; status=401")
      assertEquals("api.example.invalid Authorization: bEaReR <redacted>; status=401", result)
    }
  }

  @Test fun httpUserinfoIsMaskedAndHostnameRemainsUseful() {
    for (userinfo in listOf("synthetic-user:synthetic-secret", "synthetic-user", "synthetic%40user:synthetic%3Asecret")) {
      assertEquals("dial https://<credentials-redacted>@api.example.invalid:443/path status=503",
        safe("dial https://$userinfo@api.example.invalid:443/path status=503"))
    }
    assertEquals("https://api.example.invalid/health status=200", safe("https://api.example.invalid/health status=200"))
  }

  @Test fun existingAssignmentQueryAndUuidControlsRemainMasked() {
    val result = safe("password=synthetic-password https://api.example.invalid/?auth=synthetic-auth&key=synthetic-key uuid=123e4567-e89b-42d3-a456-426614174000")
    for (secret in listOf("synthetic-password", "synthetic-auth", "synthetic-key", "123e4567")) assertFalse(result.contains(secret))
    assertTrue(result.contains("api.example.invalid"))
  }

  @Test fun masksBeforeTruncationAndNormalizesControls() {
    val prefix = "x".repeat(780) + " "
    val result = safe(prefix + "vmess://synthetic-secret".repeat(8))
    assertFalse(result.contains("synthetic"))
    assertTrue(result.length <= 800)
    assertEquals("dial api.example.invalid ready", safe("\u001B[31mdial\tapi.example.invalid\nready\u001B[0m"))
  }

  @Test fun historicalEntriesAreSanitizedBeforeSnapshotUseWithoutChangingIdentity() {
    val entry = decode("123\tWARN\tBearer synthetic-component\tImport ss://synthetic-secret@example.invalid and Authorization: Bearer synthetic-token")!!
    assertEquals(123L, entry.timestamp)
    assertEquals("WARN", entry.level)
    assertEquals("Bearer <redacted>", entry.component)
    assertEquals("Import <profile-link-redacted> and Authorization: Bearer <redacted>", entry.message)
    assertEquals(entry.message, safe(entry.message))
    assertEquals(entry.component, safe(entry.component, 32))
  }

  @Test fun malformedHistoricalRecordsRemainIgnored() {
    assertNull(decode("not-a-timestamp\tINFO\tAPP\tmessage"))
    assertNull(decode("123\tINFO\tAPP"))
  }
}
