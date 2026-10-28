package com.example.veilark.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureProfileStore {
  const val SING_BOX = "sing-box"
  const val TRUST_TUNNEL = "trust-tunnel"
  const val TRUST_TUNNEL_CATALOG = "trust-tunnel-catalog"
  const val SING_BOX_CATALOG = "sing-box-catalog"
  const val CORE_BETA = "veilark-core-beta-profile"
  const val CORE_ARGON = "veilark-argon-profile"
  const val CORE_ARGON_RELAY = "veilark-argon-relay-profile"

  fun exists(context: Context, id: String): Boolean {
    requireLegacyId(id)
    return encryptedFile(context, id).isFile
  }

  fun save(context: Context, id: String, plaintext: String) {
    requireLegacyId(id)
    val bytes = plaintext.toByteArray(Charsets.UTF_8)
    try { encryptBytes(context, id, bytes) } finally { bytes.fill(0) }
  }

  /** Enrollment backend only: caller must hold its transaction lock. No generic delete API. */
  internal fun saveEnrollmentBytes(context: Context, id: String, plaintext: ByteArray) {
    require(isEnrollmentRecord(id)) { "Неизвестный тип профиля" }
    encryptBytes(context, id, plaintext)
  }

  internal fun loadEnrollmentBytes(context: Context, id: String): ByteArray {
    require(isEnrollmentRecord(id)) { "Неизвестный тип профиля" }
    return decryptBytes(context, id)
  }

  internal fun deviceKeyExists(context: Context, id: String): Boolean {
    require(isDeviceKeyRecord(id))
    return encryptedFile(context, id).isFile
  }

  internal fun saveDeviceKey(context: Context, id: String, plaintext: ByteArray) {
    require(isDeviceKeyRecord(id))
    encryptBytes(context, id, plaintext)
  }

  internal fun loadDeviceKey(context: Context, id: String): ByteArray {
    require(isDeviceKeyRecord(id))
    return decryptBytes(context, id)
  }

  /** Borrowed input; caller retains ownership. Existing encrypted format/AAD unchanged. */
  private fun encryptBytes(context: Context, id: String, plaintext: ByteArray) {
    val destination = encryptedFile(context, id) // Validate identity before touching the key.
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(id))
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    val ciphertext = cipher.doFinal(plaintext)
    destination.parentFile?.mkdirs()
    val atomicFile = AtomicFile(destination)
    val output = atomicFile.startWrite()
    try {
      output.write(FORMAT_VERSION.toInt())
      output.write(cipher.iv.size)
      output.write(cipher.iv)
      output.write(ciphertext)
      output.flush()
      atomicFile.finishWrite(output)
    } catch (failure: Throwable) {
      atomicFile.failWrite(output)
      throw failure
    }
  }

  fun load(context: Context, id: String): String {
    requireLegacyId(id)
    val plaintextBytes = decryptBytes(context, id)
    return try { plaintextBytes.toString(Charsets.UTF_8) }
      finally { plaintextBytes.fill(0) }
  }

  /** Returns owned decrypted bytes; caller must erase after use. Reads never create keys. */
  private fun decryptBytes(context: Context, id: String): ByteArray {
    val payload = AtomicFile(encryptedFile(context, id)).readFully()
    require(payload.size > 2 && payload[0] == FORMAT_VERSION) {
      "Формат защищённого профиля не поддерживается"
    }
    val ivSize = payload[1].toInt() and 0xFF
    require(ivSize in 12..16 && payload.size > 2 + ivSize) {
      "Защищённый профиль повреждён"
    }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      existingKey(id),
      GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
    )
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    return cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size))
  }

  fun delete(context: Context, id: String): Boolean {
    requireLegacyId(id)
    return encryptedFile(context, id).delete()
  }

  fun migrateLegacy(context: Context) {
    migrate(context, SING_BOX, File(context.filesDir, "profiles/active.json"))
    migrate(context, TRUST_TUNNEL, File(context.filesDir, "trusttunnel/active.toml"))
  }

  private fun migrate(context: Context, id: String, legacy: File) {
    if (!legacy.isFile) return
    if (!exists(context, id)) save(context, id, legacy.readText())
    legacy.delete()
  }

  private fun encryptedFile(context: Context, id: String): File =
    profileFile(if (id == CORE_ARGON || id == CORE_ARGON_RELAY || isManagedFairwayRecord(id) || isEnrollmentRecord(id) || isDeviceKeyRecord(id)) context.noBackupFilesDir else context.filesDir, id)

  internal fun profileFile(filesDir: File, id: String): File {
    require(
      id == SING_BOX ||
        id == TRUST_TUNNEL ||
        id == TRUST_TUNNEL_CATALOG ||
        id == SING_BOX_CATALOG ||
        id == CORE_BETA ||
        id == CORE_ARGON || id == CORE_ARGON_RELAY || isManagedFairwayRecord(id) || isEnrollmentRecord(id) || isDeviceKeyRecord(id),
    ) {
      "Неизвестный тип профиля"
    }
    return File(filesDir, "secure-profiles/$id.vlk")
  }

  private fun isEnrollmentRecord(id: String): Boolean =
    id.matches(Regex("veilark-enrollment-v2-[0-9a-f]{64}"))

  private fun isDeviceKeyRecord(id: String): Boolean =
    id.matches(Regex("veilark-device-key-v1-[0-9a-f]{64}"))

  private fun isManagedFairwayRecord(id: String): Boolean =
    id.matches(Regex("veilark-managed-fairway-[0-9a-f]{64}"))

  private fun requireLegacyId(id: String) {
    require(id in setOf(SING_BOX, TRUST_TUNNEL, SING_BOX_CATALOG, TRUST_TUNNEL_CATALOG, CORE_BETA, CORE_ARGON, CORE_ARGON_RELAY) || isManagedFairwayRecord(id)) {
      "Неизвестный тип профиля"
    }
  }

  private fun getOrCreateKey(id: String): SecretKey {
    val alias = keyAlias(id)
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build(),
      )
      generateKey()
    }
  }

  /** Reading never creates a replacement key for an existing encrypted profile. */
  private fun existingKey(id: String): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return store.getKey(keyAlias(id), null) as? SecretKey
      ?: error("Ключ защищённого профиля недоступен")
  }

  private fun keyAlias(id: String): String =
    if (isDeviceKeyRecord(id)) "veilark_device_key_v1"
    else if (isEnrollmentRecord(id)) "veilark_enrollment_v2" else KEY_ALIAS

  private const val KEY_ALIAS = "veilark_profile_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val FORMAT_VERSION: Byte = 1
}
