package com.example.veilark.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GeoUpdateStatusTest {
  @get:Rule val folder = TemporaryFolder()
  private fun store() = GeoUpdateStatusStore(File(folder.root, "status.properties"))

  @Test fun failurePreservesLastSuccessfulUpdateAfterRestart() {
    val store = store()
    store.write(GeoUpdateStatus().started(1000).succeeded(2000))
    store.write(store.read().started(3000).failed())
    val restarted = store().read()
    assertEquals(3000L, restarted.lastAttemptAtMillis)
    assertEquals(2000L, restarted.lastSuccessAtMillis)
    assertEquals(GeoUpdateStatus.Result.FAILED, restarted.result)
  }

  @Test fun cancelledAttemptPreservesLastSuccess() {
    val status = GeoUpdateStatus().started(1000).succeeded(2000).started(3000).failed(true)
    assertEquals(GeoUpdateStatus.Result.CANCELLED, status.result)
    assertEquals(2000L, status.lastSuccessAtMillis)
  }

  @Test fun unfinishedAttemptSurvivesProcessDeath() {
    store().write(GeoUpdateStatus().started(1000))
    assertEquals(GeoUpdateStatus.Result.UPDATING, store().read().result)
    assertNull(store().read().lastSuccessAtMillis)
    assertEquals(GeoUpdateStatus.Result.INTERRUPTED, store().read().observed(false).result)
    assertEquals(GeoUpdateStatus.Result.UPDATING, store().read().observed(true).result)
  }

  @Test fun malformedMetadataDoesNotPreventReadingActiveAssets() {
    File(folder.root, "status.properties").writeText("version=1\nresult=SUCCESS\nattempt=-1")
    assertEquals(GeoUpdateStatus(), store().read())
  }

  @Test fun excessiveMetadataIsRejected() {
    File(folder.root, "status.properties").writeText("x".repeat(4097))
    assertEquals(GeoUpdateStatus(), store().read())
  }

  @Test fun publishedGenerationWinsOverInterruptedSecondaryMetadataWrite() {
    val observed = GeoUpdateStatus().started(1000).observed(false, 2000)
    assertEquals(GeoUpdateStatus.Result.SUCCESS, observed.result)
    assertEquals(2000L, observed.lastSuccessAtMillis)
  }

  @Test fun olderActiveGenerationDoesNotHideNewAttemptFailure() {
    val observed = GeoUpdateStatus().started(3000).failed().observed(false, 2000)
    assertEquals(GeoUpdateStatus.Result.FAILED, observed.result)
    assertEquals(2000L, observed.lastSuccessAtMillis)
  }

  @Test fun activeManifestRestoresSuccessWhenSecondaryMetadataIsMissing() {
    val observed = GeoUpdateStatus().observed(false, 2000)
    assertEquals(GeoUpdateStatus.Result.SUCCESS, observed.result)
    assertEquals(2000L, observed.lastSuccessAtMillis)
  }

  @Test fun statusContainsNoSourceUrlsOrTemporaryFiles() {
    store().write(GeoUpdateStatus().started(1000).succeeded(2000).copy(
      activeGeneration = "private-source", sourceGeneratedAt = "https://example.com/?token=secret",
    ))
    val text = File(folder.root, "status.properties").readText()
    assertFalse(text.contains("secret"))
    assertFalse(text.contains("private-source"))
    assertFalse(File(folder.root, "status.properties.tmp").exists())
  }
}
