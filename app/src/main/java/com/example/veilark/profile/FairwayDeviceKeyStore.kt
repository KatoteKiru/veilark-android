package com.example.veilark.profile

import android.content.Context
import com.google.crypto.tink.subtle.X25519
import java.security.MessageDigest

/** A persisted X25519 identity bound to both the account scope and this install. */
internal object FairwayDeviceKeyStore {
  @Synchronized fun loadOrCreate(context: Context, scope: String, installId: String): ByteArray {
    require(scope.matches(Regex("[A-Za-z0-9_-]{32}")))
    require(installId.matches(Regex("[A-Za-z0-9_-]{24}")))
    val scopeHash = digest("fairway-device-scope-v1\u0000$scope")
    val installHash = digest("fairway-device-install-v1\u0000$installId")
    val id = "veilark-device-key-v1-" + scopeHash.hex()
    val record = if (SecureProfileStore.deviceKeyExists(context, id)) {
      SecureProfileStore.loadDeviceKey(context, id)
    } else {
      val key = X25519.generatePrivateKey()
      try {
        val encoded = byteArrayOf(1) + scopeHash + installHash + key + X25519.publicFromPrivate(key)
        try { SecureProfileStore.saveDeviceKey(context, id, encoded) } finally { encoded.fill(0) }
      } finally { key.fill(0) }
      // Do not use an identity until the encrypted record is durably persisted and readable.
      SecureProfileStore.loadDeviceKey(context, id)
    }
    try {
      check(record.size == 129 && record[0] == 1.toByte())
      check(MessageDigest.isEqual(scopeHash, record.copyOfRange(1, 33)))
      check(MessageDigest.isEqual(installHash, record.copyOfRange(33, 65)))
      val secret = record.copyOfRange(65, 97)
      try {
        check(MessageDigest.isEqual(X25519.publicFromPrivate(secret), record.copyOfRange(97, 129)))
        return secret
      } catch (failure: Throwable) { secret.fill(0); throw failure }
    } finally { record.fill(0) }
  }

  private fun digest(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
