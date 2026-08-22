package com.example.veilark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrDiagnosticsTest {
  @Test
  fun reportsRootCauseWithoutLeakingLinks() {
    val failure = IllegalStateException(
      "wrapper",
      IllegalArgumentException("camera failed for tt://secret-token\nretry"),
    )

    val summary = QrDiagnostics.safeFailureSummary(failure)

    assertTrue(summary.startsWith("IllegalArgumentException: camera failed"))
    assertTrue(summary.contains("<ссылка скрыта>"))
    assertFalse(summary.contains("secret-token"))
    assertFalse(summary.contains('\n'))
  }

  @Test
  fun limitsFailureDetails() {
    val summary = QrDiagnostics.safeFailureSummary(IllegalStateException("x".repeat(500)))
    assertTrue(summary.length <= "IllegalStateException: ".length + 180)
  }
}
