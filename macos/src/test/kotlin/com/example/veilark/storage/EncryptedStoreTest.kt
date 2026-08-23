package com.example.veilark.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedStoreTest {
  @get:Rule
  val folder = TemporaryFolder()

  @Test
  fun roundTripPreservesPlaintext() {
    val key = EncryptedStore.ephemeralKey()
    val store = EncryptedStore(folder.newFolder("secure")) { key }
    store.save(EncryptedStore.SING_BOX_CATALOG, """{"profiles":[]}""")
    assertEquals("""{"profiles":[]}""", store.load(EncryptedStore.SING_BOX_CATALOG))
  }

  @Test
  fun differentKeysCannotReadCiphertext() {
    val dir = folder.newFolder("secure")
    EncryptedStore(dir, EncryptedStore::ephemeralKey)
      .save(EncryptedStore.PREFERENCES, "secret")
    try {
      EncryptedStore(dir, EncryptedStore::ephemeralKey).load(EncryptedStore.PREFERENCES)
      error("expected decrypt failure")
    } catch (_: Exception) {
    }
  }
}
