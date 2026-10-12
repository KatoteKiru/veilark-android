package com.example.veilark.profile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SecureProfileStoreContractTest {
  @Test fun coreEnrollmentUsesAnAcceptedEncryptedStoreIdentity() {
    val root = File("private-files")
    assertEquals(File(root, "secure-profiles/veilark-core-beta-profile.vlk"),
      SecureProfileStore.profileFile(root, SecureProfileStore.CORE_BETA))
    for (id in listOf(SecureProfileStore.SING_BOX, SecureProfileStore.TRUST_TUNNEL,
        SecureProfileStore.SING_BOX_CATALOG, SecureProfileStore.TRUST_TUNNEL_CATALOG)) {
      assertEquals(File(root, "secure-profiles/$id.vlk"), SecureProfileStore.profileFile(root, id))
    }
  }

  @Test fun unknownIdentitiesAndPathTraversalRemainRejected() {
    for (id in listOf("core", "../veilark-core-beta-profile", "", "sing-box/../other")) {
      try { SecureProfileStore.profileFile(File("private-files"), id); fail("Unapproved identity accepted") }
      catch (_: IllegalArgumentException) { }
    }
  }
}
