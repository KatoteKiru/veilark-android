package com.example.veilark.storage

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedStore(
  private val directory: File,
  private val keyProvider: () -> SecretKey,
) {
  fun exists(id: String): Boolean = file(id).isFile

  fun save(id: String, plaintext: String) {
    require(id.matches(ID)) { "Неизвестный идентификатор хранилища" }
    directory.mkdirs()
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    val payload = byteArrayOf(FORMAT, cipher.iv.size.toByte()) + cipher.iv + ciphertext
    val target = file(id)
    val temp = File(target.absolutePath + ".tmp")
    temp.writeBytes(payload)
    if (!temp.renameTo(target)) {
      target.writeBytes(payload)
      temp.delete()
    }
  }

  fun load(id: String): String {
    val payload = file(id).readBytes()
    require(payload.size > 2 && payload[0] == FORMAT) { "Формат защищённого профиля не поддерживается" }
    val ivSize = payload[1].toInt() and 0xFF
    require(ivSize in 12..16 && payload.size > 2 + ivSize) { "Защищённый профиль повреждён" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(
      Cipher.DECRYPT_MODE,
      keyProvider(),
      GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
    )
    cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
    return cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size)).toString(Charsets.UTF_8)
  }

  fun delete(id: String): Boolean = file(id).delete()

  private fun file(id: String): File {
    require(id.matches(ID)) { "Неизвестный идентификатор хранилища" }
    return File(directory, "$id.vlk")
  }

  companion object {
    const val SING_BOX_CATALOG = "sing-box-catalog"
    const val TRUST_TUNNEL_CATALOG = "trust-tunnel-catalog"
    const val PREFERENCES = "preferences"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val FORMAT: Byte = 1
    private val ID = Regex("""[a-z0-9-]+""")

    fun ephemeralKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun keyFromBytes(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
  }
}

object MacKeychain {
  private const val SERVICE = "app.veilark.macos.profiles"
  private const val ACCOUNT = "aes-256-gcm"

  fun loadOrCreateKey(): SecretKey {
    existing()?.let { return EncryptedStore.keyFromBytes(it) }
    val generated = ByteArray(32).also(SecureRandom()::nextBytes)
    val hex = generated.joinToString("") { "%02x".format(it) }
    val add = ProcessBuilder(
      "security", "add-generic-password", "-U",
      "-s", SERVICE, "-a", ACCOUNT, "-w", hex,
    ).redirectErrorStream(true).start()
    add.waitFor()
    return EncryptedStore.keyFromBytes(existing() ?: generated)
  }

  private fun existing(): ByteArray? {
    val process = ProcessBuilder(
      "security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w",
    ).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
    if (process.waitFor() != 0 || output.length != 64 || output.any { it !in "0123456789abcdefABCDEF" }) {
      return null
    }
    return output.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
