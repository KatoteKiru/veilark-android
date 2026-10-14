package com.example.veilark.security

import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/** Exact RFC8410 Ed25519 SPKI; uses Tink's pure-Java verifier, including on API29. */
internal object Ed25519Verifier {
  private val spkiPrefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

  fun hasSupportedPublicKeyEncoding(publicKeyX509: ByteArray): Boolean =
    publicKeyX509.size == 44 && spkiPrefix.indices.all { publicKeyX509[it] == spkiPrefix[it] }

  fun verify(payload: ByteArray, signature: ByteArray, publicKeyX509: ByteArray): Boolean {
    if (!hasSupportedPublicKeyEncoding(publicKeyX509) || signature.size != 64) return false
    return try {
      Ed25519Verify(publicKeyX509.copyOfRange(spkiPrefix.size, publicKeyX509.size)).verify(signature, payload)
      true
    } catch (_: GeneralSecurityException) {
      false
    } catch (_: IllegalArgumentException) {
      false
    } catch (_: IllegalStateException) {
      false // A provider policy that forbids Ed25519 must never bypass verification.
    }
  }
}
