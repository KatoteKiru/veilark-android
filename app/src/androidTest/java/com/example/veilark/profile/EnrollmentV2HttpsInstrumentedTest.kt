package com.example.veilark.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.veilark.MainActivity
import com.example.veilark.core.runtime.CoreBetaManager
import com.example.veilark.protocol.ProfileEngine
import com.example.veilark.vpn.ConnectionState
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTPS/production-registry acceptance on an owned emulator only.
 * Synthetic RFC5737 endpoint; never starts VPN, and proves no phone/traffic behavior.
 * The bearer input is injected privately by the host and never included in assertions/logs.
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentV2HttpsInstrumentedTest {
  @Test fun actualViewImportResolvesProtectedProfileAndDeleteRetainsFloor() {
    check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Emulator-only acceptance" }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val input = File(context.filesDir, "enrollment-https-acceptance.link")
    check(input.isFile && input.length() in 1..160) { "Acceptance input unavailable" }
    val linkBytes = input.readBytes()
    val link = try {
      check(linkBytes.all { it.toInt() in 0..127 }) { "Acceptance input invalid" }
      String(linkBytes, Charsets.US_ASCII)
    } finally { linkBytes.fill(0) }
    check(EnrollmentV2Store.isCanonicalLink(link)) { "Acceptance input invalid" }
    assertStopped()

    // Public fixture profile only: no fixture verifier, issuer key or trust override.
    val publicFixture = instrumentation.context.assets.open("enrollment-v2-public-fixture.json")
      .bufferedReader(Charsets.UTF_8).use { CoreProfileJson.parse(it.readText()) as Map<*, *> }
    val raw = ((publicFixture["valid"] as Map<*, *>)["profile_utf8"] as String).toByteArray(Charsets.UTF_8)
    try { CoreProfileStore.import(context, raw) } finally { raw.fill(0) }
    val rawFile = SecureProfileStore.profileFile(context.noBackupFilesDir, SecureProfileStore.CORE_ARGON)
    val rawDigest = digest(rawFile)
    val preferences = context.getSharedPreferences("profile_meta", Context.MODE_PRIVATE)
    check(preferences.edit().putString("profile_engine", ProfileEngine.SING_BOX).commit())

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)
    ActivityScenario.launch<MainActivity>(intent).use {
      // One VIEW intent, one fetch. Poll only local metadata, never retry the import.
      val deadline = SystemClock.elapsedRealtime() + 20_000
      var imported = false
      while (SystemClock.elapsedRealtime() < deadline) {
        imported = runCatching { CoreProfileStore.loadMetadata(context).name == EXPECTED_NAME }.getOrDefault(false)
        if (imported) break
        SystemClock.sleep(50)
      }
      assertTrue("Protected VIEW import did not complete", imported)
      assertSelected(context)
      assertEquals(ProfileEngine.SING_BOX, preferences.getString("profile_engine", null))
      assertStopped()
    }

    val identity = checkNotNull(CoreProfileStore.enrollmentIdentity(preferences.all))
    val recordKey = recordKey(identity.first, identity.second)
    val recordFile = SecureProfileStore.profileFile(context.noBackupFilesDir, recordKey)
    assertTrue("Protected record missing", recordFile.isFile)
    val original = SecureProfileStore.loadEnrollmentBytes(context, recordKey)
    val floorLength = floorPrefixLength(original, requireProfile = true)
    val floor = original.copyOfRange(0, floorLength)
    original.fill(0)
    try {
      runBlocking { CoreBetaManager.deleteProfileWhenStopped(context) }
      assertNull(CoreProfileStore.selectedKind(context))
      assertTrue("Retained raw profile was removed", CoreArgonProfileStore.exists(context))
      assertArrayEquals(rawDigest, digest(rawFile))
      assertTrue("Revision floor file was removed", recordFile.isFile)
      assertTrue("Selection tombstone lost identity", identity == CoreProfileStore.enrollmentIdentity(preferences.all))
      val tombstone = SecureProfileStore.loadEnrollmentBytes(context, recordKey)
      try {
        assertEquals(floorLength, floorPrefixLength(tombstone, requireProfile = false))
        assertTrue("Revision floor changed during deletion", floor.indices.all { floor[it] == tombstone[it] })
      } finally { tombstone.fill(0) }
      assertThrows(IllegalStateException::class.java) { CoreProfileStore.loadSelectedArgon(context).close() }

      runBlocking { CoreBetaManager.importEnrollmentWhenStopped(context, link) }
      assertSelected(context)
      assertTrue("Restored source identity changed", identity == CoreProfileStore.enrollmentIdentity(preferences.all))
      assertArrayEquals(rawDigest, digest(rawFile))
      assertEquals(ProfileEngine.SING_BOX, preferences.getString("profile_engine", null))
      assertStopped()
    } finally {
      floor.fill(0); rawDigest.fill(0)
      // Tombstone preserves the accepted floor; host removes the owned fixture/app after receipt.
      runBlocking { CoreBetaManager.deleteProfileWhenStopped(context) }
      input.delete()
    }
  }

  private fun assertSelected(context: Context) {
    assertEquals(CoreProfileKind.ARGON_V1, CoreProfileStore.selectedKind(context))
    assertEquals(EXPECTED_NAME, CoreProfileStore.loadMetadata(context).name)
    CoreProfileStore.loadSelectedArgon(context).use {
      assertEquals(EXPECTED_NAME, it.name)
      assertEquals("192.0.2.1", it.endpointIpv4)
      assertEquals(443, it.endpointPort)
      assertEquals(1280, it.mtu)
      assertEquals(listOf("1.1.1.1", "77.88.8.8"), it.dnsIpv4)
      assertEquals(CoreArgonHandshakeProfile.LEGACY, it.handshakeProfile)
    }
  }

  private fun assertStopped() {
    assertTrue("Core service unexpectedly started", CoreBetaManager.stopped.value)
    assertEquals(ConnectionState.Disconnected, CoreBetaManager.state.value)
  }

  private fun digest(file: File): ByteArray {
    val bytes = file.readBytes()
    return try { MessageDigest.getInstance("SHA-256").digest(bytes) } finally { bytes.fill(0) }
  }

  private fun recordKey(issuer: String, profileId: String): String {
    val identity = byteArrayOf(issuer.length.toByte()) + issuer.toByteArray(Charsets.US_ASCII) +
      byteArrayOf(profileId.length.toByte()) + profileId.toByteArray(Charsets.US_ASCII)
    return try {
      "veilark-enrollment-v2-" + MessageDigest.getInstance("SHA-256").digest(identity)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    } finally { identity.fill(0) }
  }

  /** Check actual encoded floor and tombstone, without emitting any record content. */
  private fun floorPrefixLength(bytes: ByteArray, requireProfile: Boolean): Int {
    val input = DataInputStream(ByteArrayInputStream(bytes))
    assertEquals(0x56455232, input.readInt()); assertEquals(1, input.readUnsignedByte())
    repeat(3) {
      val length = input.readUnsignedByte()
      assertTrue(length in 1..48)
      repeat(length) { input.readUnsignedByte() }
    }
    repeat(4) { assertTrue(input.readLong() > 0) }
    repeat(32) { input.readUnsignedByte() }
    val prefix = bytes.size - input.available()
    val length = input.readInt()
    assertEquals(input.available(), length)
    if (requireProfile) assertTrue(length in 1..16384) else assertEquals(0, length)
    return prefix
  }

  companion object { private const val EXPECTED_NAME = "Veilark HTTPS acceptance" }
}
