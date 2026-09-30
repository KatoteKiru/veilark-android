package com.example.veilark.ui.main

import com.example.veilark.diagnostics.TechnicalLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalLogKeysTest {
  @Test
  fun identicalEntriesReceiveUniqueKeys() {
    val entry = TechnicalLogEntry(1_000L, "WARN", "vpn", "retry")
    val keys = technicalLogKeys(listOf(entry, entry, entry))
    assertEquals(3, keys.toSet().size)
  }

  @Test
  fun keysAreUniqueAcrossMixedEntries() {
    val entries = listOf(
      TechnicalLogEntry(1L, "INFO", "a", "x"),
      TechnicalLogEntry(1L, "INFO", "a", "x"),
      TechnicalLogEntry(1L, "ERROR", "a", "x"),
      TechnicalLogEntry(2L, "INFO", "a", "x"),
      TechnicalLogEntry(1L, "INFO", "b", "x"),
      TechnicalLogEntry(1L, "INFO", "a", "y"),
    )
    val keys = technicalLogKeys(entries)
    assertEquals(entries.size, keys.size)
    assertEquals(entries.size, keys.toSet().size)
  }

  @Test
  fun appendingEntriesKeepsExistingKeysStable() {
    val first = TechnicalLogEntry(5L, "INFO", "core", "started")
    val older = technicalLogKeys(listOf(first, first))
    val newer = technicalLogKeys(listOf(first, first, first, TechnicalLogEntry(6L, "INFO", "core", "ok")))
    assertEquals(older, newer.take(2))
  }

  @Test
  fun emptyLogHasNoKeys() {
    assertEquals(emptyList<String>(), technicalLogKeys(emptyList()))
  }
}
