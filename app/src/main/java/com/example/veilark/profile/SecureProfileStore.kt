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

  fun exists(context: Context, id: String): Boolean = encryptedFile(context, id).isFile

  fun save(context: Context, id: String, plaintext: String) {
    val bytes = plaintext.toByteArray(Charsets.UTF_8)
    try { saveBytes(context, id, bytes) } finally { bytes.fill(0) }
  }

  /** Borrowed input; caller retains ownership. Existing encrypted format/AAD unchanged. */
  internal fun saveBytes(context: Context, id: String, plaintext: ByteArray) {
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
    val plaintextBytes = loadBytes(context, id)
    return try { plaintextBytes.toString(Charsets.UTF_8) }
      finally { plaintextBytes.fill(0) }
  }

  /** Returns owned decrypted bytes; caller must erase after use. Reads never create keys. */
  internal fun loadBytes(context: Context, id: String): ByteArray {
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

  fun delete(context: Context, id: String): Boolean = encryptedFile(context, id).delete()

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
    profileFile(if (id == CORE_ARGON || isEnrollmentRecord(id)) context.noBackupFilesDir else context.filesDir, id)

  internal fun profileFile(filesDir: File, id: String): File {
    require(
      id == SING_BOX ||
        id == TRUST_TUNNEL ||
        id == TRUST_TUNNEL_CATALOG ||
        id == SING_BOX_CATALOG ||
        id == CORE_BETA ||
        id == CORE_ARGON || isEnrollmentRecord(id),
    ) {
      "Неизвестный тип профиля"
    }
    return File(filesDir, "secure-profiles/$id.vlk")
  }

  private fun isEnrollmentRecord(id: String): Boolean =
    id.matches(Regex("veilark-enrollment-v2-[0-9a-f]{64}"))

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
    if (isEnrollmentRecord(id)) "veilark_enrollment_v2" else KEY_ALIAS

  private const val KEY_ALIAS = "veilark_profile_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val FORMAT_VERSION: Byte = 1
}
