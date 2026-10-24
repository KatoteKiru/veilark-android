package com.example.veilark.profile

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Intent/ClipData behavior; public synthetic tokens only, no network. */
@RunWith(AndroidJUnit4::class)
class ImportIntentConsumptionInstrumentedTest {
  @Test fun recognizedBearerIsTransferredOnceAndRemovedFromActivityIntent() {
    val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    val links = listOf("veilark://enroll/v2?t=$token#k=$token", "veilark://import?t=$token",
      "veilark://import?url=https%3A%2F%2Fexample.com%2Fsubscription")
    for (link in links) {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
        clipData = ClipData.newPlainText("subscription", link)
        putExtra(Intent.EXTRA_TEXT, link)
      }
      val expected = ImportDeepLink.parse(intent)
      assertNotNull("Synthetic import should be recognized", expected)
      assertEquals(expected, ImportDeepLink.consume(intent))
      assertNull(intent.data)
      assertNull(intent.clipData)
      assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
      assertNull(intent.action)
      assertNull(ImportDeepLink.consume(intent))
      // A recreated Activity receiving this consumed intent cannot replay it.
      assertNull(ImportDeepLink.parse(Intent(intent)))
    }
  }

  @Test fun fileAndUnrecognizedIntentsRetainTheirPermissionsAndPayload() {
    for (uri in listOf("content://example.com/profile.json", "veilark://enroll/v3?t=invalid")) {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("profile", data)
      }
      val before = intent.clipData
      assertNull(ImportDeepLink.consume(intent))
      assertEquals(uri, intent.data.toString())
      assertEquals(Intent.ACTION_VIEW, intent.action)
      assertSame(before, intent.clipData)
      assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }
    assertNull(ImportDeepLink.consume(null))
  }
}
