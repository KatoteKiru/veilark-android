package app.veilark.macos

import org.junit.Assert.*
import org.junit.Test

class StartupDiagnosticsTest {
  @Test fun unexpectedErrorRecordNeverIncludesExceptionMessageOrCauseSecrets() {
    val error = IllegalStateException("https://private.invalid/bearer-secret", RuntimeException("password=secret"))
    val record = StartupDiagnostics.safeRecord(error)
    assertTrue(record.contains("IllegalStateException"))
    assertFalse(record.contains("bearer-secret"))
    assertFalse(record.contains("password"))
    assertTrue(record.length <= 4096)
  }
}
