package com.example.veilark.profile

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

/** Only public synthetic keys/profiles; the transaction backend is deliberately in memory. */
class EnrollmentV2RecordStoreTest {
  private val now = 1792717200L
  private val issuer = "fixture-only"
  private val profileId = "fixture-profile"
  private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
  private val fixture by lazy {
    var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
    var found: File? = null
    while (directory != null) {
      val file = File(directory, "docs/fixtures/enrollment-v2-draft-20261023.json")
      if (file.isFile) { found = file; break }
      directory = directory.parentFile
    }
    CoreProfileJson.parse(requireNotNull(found).readText()) as Map<*, *>
  }

  @Test fun actualVerifierProvenanceRoundTripsWithDefensiveCopies() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified().use { handle ->
      assertEquals(EnrollmentV2RecordStore.ImportResult.INSERTED, store.import(handle, now))
      store.load(issuer, profileId)!!.use { record ->
        assertEquals(handle.issuer, record.issuer); assertEquals(handle.keyId, record.keyId)
        assertEquals(handle.profileId, record.profileId); assertEquals(handle.revision, record.revision)
        assertEquals(handle.issuedAt, record.issuedAt); assertEquals(handle.notBefore, record.notBefore)
        assertEquals(handle.expiresAt, record.expiresAt)
        assertArrayEquals(handle.profileCopy(), record.profileCopy())
        assertArrayEquals(handle.signedContentDigestCopy(), record.signedContentDigestCopy())
        val changed = record.profileCopy(); changed.fill(0)
        assertArrayEquals(handle.profileCopy(), record.profileCopy())
        assertEquals("EnrollmentV2RecordStore.StoredRecord(secrets redacted)", record.toString())
        record.close()
        assertThrows(IllegalStateException::class.java) { record.profileCopy() }
      }
    }
    assertTrue(backend.records.keys.single().matches(Regex("veilark-enrollment-v2-[0-9a-f]{64}")))
  }

  @Test fun retriesConflictRollbackAndHigherRevisionUseAuthoritativeRecord() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified(2).use { handle ->
      store.import(handle, now)
      val original = backend.records.values.single().copyOf()
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, now))
      assertEquals(1, backend.writes)
      assertArrayEquals(original, backend.records.values.single())
    }
    verified(1).use { rejects { store.import(it, now) } }
    verified(2, kid = "other-key").use { rejects { store.import(it, now) } }
    verified(3, kid = "other-key", name = "NEW PUBLIC FIXTURE").use { handle ->
      assertEquals(EnrollmentV2RecordStore.ImportResult.UPDATED, store.import(handle, now))
      store.load(issuer, profileId)!!.use {
        assertEquals(3L, it.revision); assertEquals("other-key", it.keyId)
        assertArrayEquals(handle.profileCopy(), it.profileCopy())
        assertArrayEquals(handle.signedContentDigestCopy(), it.signedContentDigestCopy())
      }
    }
  }

  @Test fun deletionRetainsFloorAndSameValidSignedContentRestores() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified(2).use { handle ->
      store.import(handle, now)
      assertTrue(store.delete(issuer, profileId)); assertFalse(store.delete(issuer, profileId))
      assertNull(store.load(issuer, profileId)); assertEquals(1, backend.records.size)
      verified(1).use { rejects { store.import(it, now) } }
      verified(2, kid = "different").use { rejects { store.import(it, now) } }
      assertEquals(EnrollmentV2RecordStore.ImportResult.RESTORED, store.import(handle, now))
      store.load(issuer, profileId)!!.use { assertArrayEquals(handle.profileCopy(), it.profileCopy()) }
    }
  }

  @Test fun beforeCommitFailureKeepsWholeOldStateAndRetryAdvances() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified(1).use { store.import(it, now) }
    val old = backend.records.values.single().copyOf()
    verified(2).use { handle ->
      backend.failNext = Failure.BEFORE
      rejects { store.import(handle, now) }
      assertArrayEquals(old, backend.records.values.single())
      store.load(issuer, profileId)!!.use { assertEquals(1L, it.revision) }
      assertEquals(EnrollmentV2RecordStore.ImportResult.UPDATED, store.import(handle, now))
    }
  }

  @Test fun afterCommitThrowReconcilesDurableNewStateWithoutDuplicateWrite() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified(1).use { store.import(it, now) }
    verified(2, name = "AFTER COMMIT PUBLIC FIXTURE").use { handle ->
      backend.failNext = Failure.AFTER
      rejects { store.import(handle, now) }
      store.load(issuer, profileId)!!.use {
        assertEquals(2L, it.revision); assertArrayEquals(handle.profileCopy(), it.profileCopy())
      }
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, now))
      assertEquals(2, backend.writes)
    }
  }

  @Test fun twoInstancesConcurrentImportsPreserveHighestRevisionAndIdentityIsolation() {
    val backend = MemoryBackend(); val first = EnrollmentV2RecordStore(backend)
    val second = EnrollmentV2RecordStore(backend)
    val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
    try {
      verified(2).use { low -> verified(3).use { high ->
        val a = executor.submit<Boolean> { start.await(); try { first.import(low, now); true } catch (_: IllegalArgumentException) { false } }
        val b = executor.submit<Boolean> { start.await(); second.import(high, now); true }
        start.countDown(); a.get(); assertTrue(b.get())
      } }
    } finally { executor.shutdownNow() }
    first.load(issuer, profileId)!!.use { assertEquals(3L, it.revision) }
    verified(1, iss = "other-issuer").use { first.import(it, now) }
    verified(1, pid = "other-profile").use { second.import(it, now) }
    assertEquals(3, backend.records.size)
    assertTrue(second.delete(issuer, "other-profile"))
    first.load("other-issuer", profileId)!!.use { assertEquals(1L, it.revision) }
    second.load(issuer, profileId)!!.use { assertEquals(3L, it.revision) }
  }

  @Test fun expiredUnusableClockAndClosedHandlesCannotAdvanceFloor() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified().use { handle ->
      for (clock in listOf(0L, 9_007_199_254_740_992L, 1792800000L, 1792713299L))
        rejects { store.import(handle, clock) }
      assertTrue(backend.records.isEmpty())
      store.import(handle, 1792713300L)
      handle.close()
      rejects { store.import(handle, now) }
    }
    // Import-window expiry is not credential/session expiry for an existing record.
    store.load(issuer, profileId)!!.close()
  }

  @Test fun malformedUnsupportedAndTrailingPersistedRecordsFailClosed() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    verified().use { store.import(it, now) }
    val key = backend.records.keys.single(); val original = backend.records[key]!!.copyOf()
    val unsupported = original.copyOf().also { it[4] = 2 }
    val wrongIssuer = original.copyOf().also { it[6] = 'X'.code.toByte() }
    for (broken in listOf(ByteArray(20 * 1024 + 1), original.copyOf(4), original + byteArrayOf(0), unsupported, wrongIssuer)) {
      backend.records[key] = broken.copyOf()
      rejects { store.load(issuer, profileId) }
      rejects { store.delete(issuer, profileId) }
      verified(2).use { rejects { store.import(it, now) } }
      assertArrayEquals(broken, backend.records[key])
    }
  }

  @Test fun expiryWhileWaitingForTransactionIsRecheckedAfterAuthoritativeRead() {
    val backend = MemoryBackend(); val store = EnrollmentV2RecordStore(backend)
    var clock = now
    verified(1).use { store.import(it) { clock } }
    val original = backend.records.values.single().copyOf()
    verified(2).use { handle ->
      // Deterministically model a queued transaction reaching expiry during read.
      backend.onRead = { clock = handle.expiresAt }
      rejects { store.import(handle) { clock } }
    }
    assertEquals(1, backend.writes)
    assertArrayEquals(original, backend.records.values.single())
  }

  private enum class Failure { BEFORE, AFTER }
  private class MemoryBackend : EnrollmentV2RecordStore.Backend {
    val records = mutableMapOf<String, ByteArray>()
    var writes = 0
    var failNext: Failure? = null
    var onRead: (() -> Unit)? = null
    @Synchronized override fun <T> transaction(recordKey: String, action: (EnrollmentV2RecordStore.Transaction) -> T): T =
      action(object : EnrollmentV2RecordStore.Transaction {
        override fun read(): ByteArray? {
          onRead?.invoke()
          return records[recordKey]?.copyOf()
        }
        override fun write(record: ByteArray) {
          val failure = failNext; failNext = null
          if (failure == Failure.BEFORE) error("synthetic before-commit failure")
          records[recordKey] = record.copyOf(); writes++
          if (failure == Failure.AFTER) error("synthetic after-commit failure")
        }
      })
  }

  private fun verified(revision: Int = 1, iss: String = issuer, pid: String = profileId,
    kid: String = "fixture-only", name: String? = null): EnrollmentV2DraftConsumer {
    val valid = fixture["valid"] as Map<*, *>; val inputs = fixture["public_test_inputs"] as Map<*, *>
    val oldProfile = valid["profile_utf8"] as String
    val profile = if (name == null) oldProfile else oldProfile.replace("PUBLIC FIXTURE - NEVER CONNECT", name)
    val payload = (valid["payload_utf8"] as String).replace("\"revision\":1", "\"revision\":$revision")
      .replace("\"iss\":\"fixture-only\"", "\"iss\":\"$iss\"")
      .replace("fixture-profile", pid).replace(b64(oldProfile.toByteArray()), b64(profile.toByteArray()))
    val header = (valid["protected_header_utf8"] as String).replace("fixture-only", kid)
    val signingInput = b64(header.toByteArray()) + "." + b64(payload.toByteArray())
    val signature = Signature.getInstance("Ed25519").run {
      initSign(pair.private); update(signingInput.toByteArray(Charsets.US_ASCII)); sign()
    }
    val plaintext = (signingInput + "." + b64(signature)).toByteArray(Charsets.US_ASCII)
    val aad = valid["aad_ascii"] as String; val iv = hex(inputs["iv_hex"] as String)
    // Synthetic public fixture CEK/IV only, never production encryption.
    val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(hex(inputs["cek_hex"] as String), "AES"), GCMParameterSpec(128, iv))
      updateAAD(aad.toByteArray(Charsets.US_ASCII)); doFinal(plaintext)
    }
    val wire = "$aad..${b64(iv)}.${b64(encrypted.copyOfRange(0, encrypted.size - 16))}.${b64(encrypted.takeLast(16).toByteArray())}"
    return EnrollmentV2DraftConsumer.verify(valid["uri"] as String, wire.toByteArray(), mapOf((iss to kid) to pair.public.encoded), now)
  }
  private fun rejects(action: () -> Any?) {
    val failure = assertThrows(IllegalArgumentException::class.java) { action() }
    assertEquals("Invalid enrollment record operation.", failure.message); assertNull(failure.cause)
  }
  private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
