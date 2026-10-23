package com.example.veilark.profile

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform primitives in an emulator-only private sandbox. No production trust
 * registry overrides, real profile files, alias deletions, network or power-loss claims.
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentV2PlatformInstrumentedTest {
  @Test fun actualEncryptedRoundTripRetainsFloorAndGenericApisReject() {
    val context = PlatformEnrollment.sandbox("roundtrip-${System.nanoTime()}")
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      assertEquals(EnrollmentV2RecordStore.ImportResult.INSERTED, store.import(handle, PlatformEnrollment.NOW))
      PlatformEnrollment.assertLoaded(store, handle)
      val file = PlatformEnrollment.recordFile(context)
      val original = file.readBytes()
      assertFalse(String(original, Charsets.ISO_8859_1).contains(String(handle.profileCopy(), Charsets.UTF_8)))
      val id = PlatformEnrollment.recordId()
      assertThrows(IllegalArgumentException::class.java) { SecureProfileStore.exists(context, id) }
      assertThrows(IllegalArgumentException::class.java) { SecureProfileStore.save(context, id, "untrusted raw profile") }
      assertThrows(IllegalArgumentException::class.java) { SecureProfileStore.load(context, id) }
      assertThrows(IllegalArgumentException::class.java) { SecureProfileStore.delete(context, id) }
      assertArrayEquals(original, file.readBytes())
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, PlatformEnrollment.NOW))
      assertArrayEquals(original, file.readBytes())
      assertTrue(store.delete(PlatformEnrollment.ISSUER, PlatformEnrollment.PROFILE))
      assertNull(store.load(PlatformEnrollment.ISSUER, PlatformEnrollment.PROFILE))
      assertTrue(file.isFile)
      assertEquals(EnrollmentV2RecordStore.ImportResult.RESTORED, store.import(handle, PlatformEnrollment.NOW))
      PlatformEnrollment.assertLoaded(store, handle)
    }
  }

  @Test fun corruptedCiphertextFailsClosedWithoutFloorMutation() {
    val context = PlatformEnrollment.sandbox("corruption-${System.nanoTime()}")
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      store.import(handle, PlatformEnrollment.NOW)
      val file = PlatformEnrollment.recordFile(context)
      val original = file.readBytes()
      val corrupt = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
      file.writeBytes(corrupt)
      try {
        assertThrows(IllegalArgumentException::class.java) { store.load(PlatformEnrollment.ISSUER, PlatformEnrollment.PROFILE) }
        assertThrows(IllegalArgumentException::class.java) { store.import(handle, PlatformEnrollment.NOW) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(PlatformEnrollment.ISSUER, PlatformEnrollment.PROFILE) }
        assertArrayEquals(corrupt, file.readBytes())
      } finally { file.writeBytes(original) }
      PlatformEnrollment.assertLoaded(store, handle)
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, PlatformEnrollment.NOW))
    }
  }

  @Test fun atomicFailWriteAndBackupArtifactsRecoverFullFloor() {
    val context = PlatformEnrollment.sandbox("atomic-${System.nanoTime()}")
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      store.import(handle, PlatformEnrollment.NOW)
      val file = PlatformEnrollment.recordFile(context)
      val original = file.readBytes()
      val atomic = AtomicFile(file)
      val stream = atomic.startWrite()
      stream.write(byteArrayOf(1, 2, 3))
      atomic.failWrite(stream)
      assertArrayEquals(original, file.readBytes())
      PlatformEnrollment.assertLoaded(store, handle)
      // Explicit simulated recovery artifacts, not a process-kill/power-loss experiment.
      File(file.path + ".bak").writeBytes(original)
      file.writeBytes(byteArrayOf(0, 0, 0))
      File(file.path + ".new").writeBytes(byteArrayOf(4, 5, 6))
      PlatformEnrollment.assertLoaded(store, handle)
      assertArrayEquals(original, file.readBytes())
      assertFalse(File(file.path + ".bak").exists())
      assertFalse(File(file.path + ".new").exists())
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, PlatformEnrollment.NOW))
    }
  }

  @Test fun independentLegacyFormatterAndDecoderRemainCompatible() {
    val context = PlatformEnrollment.sandbox("legacy-${System.nanoTime()}")
    val id = SecureProfileStore.CORE_ARGON
    val file = SecureProfileStore.profileFile(context.noBackupFilesDir, id)
    assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val key = (keyStore.getKey("veilark_profile_v1", null) as? SecretKey) ?: run {
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder("veilark_profile_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256).build())
        generateKey()
      }
    }
    val oldText = PlatformEnrollment.verified().use { handle ->
      val bytes = handle.profileCopy()
      try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
    }
    val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
    encrypt.init(Cipher.ENCRYPT_MODE, key); encrypt.updateAAD(id.toByteArray(Charsets.UTF_8))
    val encrypted = encrypt.doFinal(oldText.toByteArray(Charsets.UTF_8))
    // Independent original formatter: version, IV-length byte, IV, GCM ciphertext/tag.
    file.writeBytes(byteArrayOf(1, encrypt.iv.size.toByte()) + encrypt.iv + encrypted)
    assertEquals(oldText, SecureProfileStore.load(context, id))
    CoreArgonProfileStore.load(context).use { assertEquals("PUBLIC FIXTURE - NEVER CONNECT", it.name) }
    val newText = oldText.replace("PUBLIC FIXTURE - NEVER CONNECT", "PUBLIC CURRENT FORMAT - NEVER CONNECT")
    SecureProfileStore.save(context, id, newText)
    val bytes = file.readBytes()
    assertEquals(1, bytes[0].toInt())
    val ivLength = bytes[1].toInt() and 255
    assertTrue(ivLength in 12..16)
    val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
    decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(2, 2 + ivLength)))
    decrypt.updateAAD(id.toByteArray(Charsets.UTF_8))
    val plaintext = decrypt.doFinal(bytes.copyOfRange(2 + ivLength, bytes.size))
    try { assertEquals(newText, String(plaintext, Charsets.UTF_8)) } finally { plaintext.fill(0) }
    CoreArgonProfileStore.load(context).use { assertEquals("PUBLIC CURRENT FORMAT - NEVER CONNECT", it.name) }
  }

  @Test fun otherProcessLockRejectsUntilChildRelease() {
    val context = PlatformEnrollment.sandbox("process-lock-${System.nanoTime()}")
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      store.import(handle, PlatformEnrollment.NOW)
      val original = PlatformEnrollment.recordFile(context).readBytes()
      val lockPath = File(PlatformEnrollment.recordFile(context).parentFile, "enrollment-v2-store.lock")
      val instrumentation = InstrumentationRegistry.getInstrumentation()
      val builder = ProcessBuilder("/system/bin/app_process", "/system/bin",
        EnrollmentLockChild::class.java.name, lockPath.absolutePath).redirectErrorStream(true)
      builder.environment()["CLASSPATH"] = instrumentation.targetContext.applicationInfo.sourceDir + ":" +
        instrumentation.context.applicationInfo.sourceDir
      val child = builder.start()
      val executor = Executors.newSingleThreadExecutor()
      try {
        val ready = executor.submit<Boolean> {
          child.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null && line != "LOCKED") line = reader.readLine()
            line == "LOCKED"
          }
        }
        assertTrue("Lock child did not acquire lock", ready.get(15, TimeUnit.SECONDS))
        assertThrows(IllegalArgumentException::class.java) { store.import(handle, PlatformEnrollment.NOW) }
        assertArrayEquals(original, PlatformEnrollment.recordFile(context).readBytes())
        child.outputStream.write(10); child.outputStream.flush(); child.outputStream.close()
        assertTrue("Lock child did not exit", child.waitFor(10, TimeUnit.SECONDS))
        assertEquals(0, child.exitValue())
        assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, PlatformEnrollment.NOW))
        PlatformEnrollment.assertLoaded(store, handle)
      } finally {
        child.destroyForcibly()
        executor.shutdownNow()
      }
    }
  }
}

/** Run each method in a separate instrumentation invocation around target force-stop.
 * The fixed sandbox is intentional; this proves process restart, not new instances alone.
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentV2RestartInstrumentedTest {
  @Test fun persistForRestart() {
    val context = PlatformEnrollment.sandbox("restart", reset = true)
    println("ENROLLMENT_RESTART phase=persist pid=${android.os.Process.myPid()}")
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      assertEquals(EnrollmentV2RecordStore.ImportResult.INSERTED, store.import(handle, PlatformEnrollment.NOW))
      PlatformEnrollment.assertLoaded(store, handle)
    }
  }
  @Test fun reopenAfterRestart() {
    val context = PlatformEnrollment.sandbox("restart")
    println("ENROLLMENT_RESTART phase=reopen pid=${android.os.Process.myPid()}")
    assertTrue("Restart record missing", PlatformEnrollment.recordFile(context).isFile)
    val store = PlatformEnrollment.store(context)
    PlatformEnrollment.verified().use { handle ->
      PlatformEnrollment.assertLoaded(store, handle)
      assertEquals(EnrollmentV2RecordStore.ImportResult.UNCHANGED, store.import(handle, PlatformEnrollment.NOW))
    }
  }
}

private object PlatformEnrollment {
  const val NOW = 1792717200L
  const val ISSUER = "fixture-only"
  const val PROFILE = "fixture-profile"
  fun sandbox(name: String, reset: Boolean = false): Context {
    check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Emulator-only platform test" }
    val real = ApplicationProvider.getApplicationContext<Context>()
    val parent = File(real.noBackupFilesDir, "enrollment-v2-platform-tests")
    val root = File(parent, name)
    check(root.canonicalFile.parentFile == parent.canonicalFile)
    if (reset && root.exists()) check(root.deleteRecursively())
    return object : ContextWrapper(real) {
      override fun getFilesDir(): File = File(root, "files")
      override fun getNoBackupFilesDir(): File = File(root, "no-backup")
      override fun getApplicationContext(): Context = this
    }
  }
  fun store(context: Context) = EnrollmentV2RecordStore(EnrollmentV2Store.AndroidBackend(context))
  fun recordId(): String {
    val bytes = byteArrayOf(ISSUER.length.toByte()) + ISSUER.toByteArray(Charsets.US_ASCII) +
      byteArrayOf(PROFILE.length.toByte()) + PROFILE.toByteArray(Charsets.US_ASCII)
    return "veilark-enrollment-v2-" + MessageDigest.getInstance("SHA-256").digest(bytes)
      .joinToString("") { "%02x".format(it.toInt() and 255) }
  }
  fun recordFile(context: Context): File = SecureProfileStore.profileFile(context.noBackupFilesDir, recordId())
  fun verified(): EnrollmentV2DraftConsumer {
    val text = InstrumentationRegistry.getInstrumentation().context.assets
      .open("enrollment-v2-public-fixture.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val fixture = CoreProfileJson.parse(text) as Map<*, *>
    val valid = fixture["valid"] as Map<*, *>
    val inputs = fixture["public_test_inputs"] as Map<*, *>
    fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val publicKey = hex("302a300506032b6570032100") + hex(inputs["ed25519_public_hex"] as String)
    return EnrollmentV2DraftConsumer.verify(valid["uri"] as String,
      (valid["jwe_ascii"] as String).toByteArray(Charsets.US_ASCII), mapOf((ISSUER to "fixture-only") to publicKey), NOW)
  }
  fun assertLoaded(store: EnrollmentV2RecordStore, handle: EnrollmentV2DraftConsumer) {
    store.load(ISSUER, PROFILE)!!.use {
      assertEquals(1L, it.revision)
      assertEquals(handle.issuer, it.issuer); assertEquals(handle.keyId, it.keyId)
      assertArrayEquals(handle.profileCopy(), it.profileCopy())
      assertArrayEquals(handle.signedContentDigestCopy(), it.signedContentDigestCopy())
    }
  }
}
