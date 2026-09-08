package com.example.veilark.storage

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.example.veilark.io.BoundedProcess

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
    val add = BoundedProcess.run(listOf(
      "security", "add-generic-password",
      "-s", SERVICE, "-a", ACCOUNT, "-w", hex,
    ), timeoutMillis = 30_000)
    check(add.exitCode == 0) {
      "Не удалось сохранить ключ шифрования в Keychain"
    }
    val stored = existing() ?: error("Keychain не вернул сохранённый ключ шифрования")
    return EncryptedStore.keyFromBytes(stored)
  }

  private fun existing(): ByteArray? {
    val result = BoundedProcess.run(listOf(
      "security", "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w",
    ), timeoutMillis = 30_000)
    return decodeExisting(result.exitCode, result.text)
  }

  internal fun decodeExisting(exitCode: Int, text: String): ByteArray? {
    // security(1) returns errSecItemNotFound (-25300 modulo 256) as 44.
    // A locked/denied/timed-out Keychain is NOT evidence that no key exists.
    if (exitCode == 44) return null
    check(exitCode == 0) { "Keychain недоступен; существующий ключ не изменён" }
    val output = text.trim()
    check(output.length == 64 && output.all { it in "0123456789abcdefABCDEF" }) {
      "Некорректный ключ Keychain; существующий ключ не изменён"
    }
    return output.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  }
}
