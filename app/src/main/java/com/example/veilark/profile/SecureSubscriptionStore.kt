package com.example.veilark.profile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureSubscriptionStore {
  private const val KEY_ALIAS = "veilark_subscription_url"
  private const val PREFERENCES = "secure_profile"
  private const val VALUE = "subscription_url"
  private const val TRUST_VALUE = "trust_subscription_url"

  fun save(context: Context, url: String) {
    saveValue(context, VALUE, url)
  }

  fun saveTrust(context: Context, url: String) {
    saveValue(context, TRUST_VALUE, url)
  }

  private fun saveValue(context: Context, name: String, url: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val encrypted = cipher.doFinal(url.toByteArray(Charsets.UTF_8))
    val payload = cipher.iv + encrypted
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .putString(name, Base64.encodeToString(payload, Base64.NO_WRAP))
      .apply()
  }

  fun load(context: Context): String? = loadValue(context, VALUE)

  fun loadTrust(context: Context): String? = loadValue(context, TRUST_VALUE)

  private fun loadValue(context: Context, name: String): String? = runCatching {
    val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
      .getString(name, null) ?: return null
    val payload = Base64.decode(encoded, Base64.NO_WRAP)
    require(payload.size > IV_SIZE)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      getOrCreateKey(),
      GCMParameterSpec(128, payload.copyOfRange(0, IV_SIZE)),
    )
    cipher.doFinal(payload.copyOfRange(IV_SIZE, payload.size)).toString(Charsets.UTF_8)
  }.getOrNull()

  fun clear(context: Context) {
    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
      .edit()
      .remove(VALUE)
      .apply()
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

  private const val IV_SIZE = 12
}
