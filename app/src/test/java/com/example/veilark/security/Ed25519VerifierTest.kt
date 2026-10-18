package com.example.veilark.security

import com.example.veilark.update.AppUpdate
import com.example.veilark.update.UpdateManager
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.*
import org.junit.Test

class Ed25519VerifierTest {
  private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
  private val rfcKey = hex("302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
  private val rfcSignature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")

  @Test fun verifiesRfc8032EmptyMessageVectorWithoutPlatformEd25519() {
    assertTrue(Ed25519Verifier.verify(byteArrayOf(), rfcSignature, rfcKey))
    assertFalse(Ed25519Verifier.verify(byteArrayOf(1), rfcSignature, rfcKey))
  }

  @Test fun everySingleSignatureByteMutationIsRejected() {
    for (index in rfcSignature.indices) {
      val changed = rfcSignature.copyOf()
      changed[index] = (changed[index].toInt() xor 1).toByte()
      assertFalse("Mutation $index accepted", Ed25519Verifier.verify(byteArrayOf(), changed, rfcKey))
    }
  }

  @Test fun rejectsMalformedKeyAlgorithmLengthAndSignatureLength() {
    for (length in listOf(0, 43, 45)) {
      assertFalse(Ed25519Verifier.verify(byteArrayOf(), rfcSignature, rfcKey.copyOf(length)))
    }
    for (length in listOf(0, 63, 65)) {
      assertFalse(Ed25519Verifier.verify(byteArrayOf(), rfcSignature.copyOf(length), rfcKey))
    }
    for (index in 0 until 12) {
      val changed = rfcKey.copyOf()
      changed[index] = (changed[index].toInt() xor 1).toByte()
      assertFalse(Ed25519Verifier.verify(byteArrayOf(), rfcSignature, changed))
    }
  }

  @Test fun preservesExistingOtaCanonicalPayloadAndJdkSignatureInteroperability() {
    val update = AppUpdate(80, "0.9.0-beta10", "https://updates.example.test/app.apk",
      "A".repeat(64), 12345, "Повышена стабильность соединения.\nUTF-8 release notes")
    val payload = UpdateManager.canonicalPayloadV2(update)
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val signer = Signature.getInstance("Ed25519")
    signer.initSign(pair.private)
    signer.update(payload)
    val signed = signer.sign()
    assertTrue(Ed25519Verifier.verify(payload, signed, pair.public.encoded))
    assertFalse(Ed25519Verifier.verify(payload + byteArrayOf(0), signed, pair.public.encoded))
    val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded
    assertFalse(Ed25519Verifier.verify(payload, signed, otherKey))
  }
}
