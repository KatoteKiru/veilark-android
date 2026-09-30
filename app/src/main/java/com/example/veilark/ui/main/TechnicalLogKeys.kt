package com.example.veilark.ui.main

import com.example.veilark.diagnostics.TechnicalLogEntry

/**
 * Returns one LazyColumn key per entry, in the same order as [entries].
 *
 * The technical log can legitimately contain identical entries (same
 * timestamp, level, component and message — for example a retry loop logging
 * within one millisecond). Using the entry content alone as a key crashes
 * LazyColumn with a duplicate-key error, so each key carries the occurrence
 * number of its content in chronological order. Appending newer entries never
 * changes the keys of older ones, which keeps item identity stable while the
 * log grows.
 */
internal fun technicalLogKeys(entries: List<TechnicalLogEntry>): List<String> {
  val occurrences = HashMap<String, Int>(entries.size)
  return entries.map { entry ->
    val content = "${entry.timestamp}|${entry.level}|${entry.component}|${entry.message}"
    val occurrence = (occurrences[content] ?: 0) + 1
    occurrences[content] = occurrence
    "$content#$occurrence"
  }
}
