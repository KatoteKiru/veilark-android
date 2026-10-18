package com.example.veilark.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileImportRequestTest {
  @Test fun acceptsGrantedDocumentViewAndShare() {
    for (action in listOf("android.intent.action.VIEW", "android.intent.action.SEND")) {
      assertTrue(FileImportRequest.accepts(action, "content://com.android.providers.downloads.documents/document/10", true))
      assertTrue(FileImportRequest.accepts(action, "content://provider/path%20name?version=1", true))
    }
  }
  @Test fun rejectsPathsNetworkUrisAndMissingGrant() {
    assertFalse(FileImportRequest.accepts("android.intent.action.VIEW", "content://provider/document/10", false))
    for (uri in listOf("file:///data/data/secret", "https://example.com/profile", "/sdcard/test.json",
      "content:///document/10", "content://user:secret@provider/10", "content://provider/10#fragment",
      "content://provider/\u0000", "content://provider/" + "x".repeat(8192))) {
      assertFalse(FileImportRequest.accepts("android.intent.action.VIEW", uri, true))
    }
    assertFalse(FileImportRequest.accepts("android.intent.action.SEND_MULTIPLE", "content://provider/a", true))
  }
}
