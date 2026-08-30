package com.example.veilark

internal object QrDiagnostics {
  private const val MAX_FAILURE_DETAIL = 180
  private val linkPattern = Regex("(?i)(?:https?|tt)://\\S+")
  private val whitespacePattern = Regex("[\\r\\n\\t]+")

  fun safeFailureSummary(failure: Throwable): String {
    val root = generateSequence(failure) { it.cause }.last()
    val type = root.javaClass.simpleName.ifBlank { "Throwable" }
    val detail = root.message
      ?.replace(linkPattern, "<redacted link>")
      ?.replace(whitespacePattern, " ")
      ?.trim()
      ?.take(MAX_FAILURE_DETAIL)
      ?.takeIf(String::isNotEmpty)
    return if (detail == null) type else "$type: $detail"
  }
}
