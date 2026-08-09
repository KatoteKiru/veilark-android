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

  fun exists(context: Context, id: String): Boolean = encryptedFile(context, id).isFile

  fun save(context: Context, id: String, plaintext: String) {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val destination = encryptedFile(context, id)
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
      getOrCreateKey(),
      GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
    )
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    return cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size))
      .toString(Charsets.UTF_8)
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

  private fun encryptedFile(context: Context, id: String): File {
    require(
      id == SING_BOX ||
        id == TRUST_TUNNEL ||
        id == TRUST_TUNNEL_CATALOG ||
        id == SING_BOX_CATALOG,
    ) {
      "Неизвестный тип профиля"
    }
    return File(context.filesDir, "secure-profiles/$id.vlk")
  }

  private fun getOrCreateKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(
        KeyGenParameterSpec.Builder(
          KEY_ALIAS,
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

  private const val KEY_ALIAS = "veilark_profile_v1"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private const val FORMAT_VERSION: Byte = 1
}
