package com.example.veilark.diagnostics

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LogPersistenceGuardTest {
  @Test fun failedStorageIsContainedAndRetriedOnlyAfterBackoff() {
    var now = 0L
    var attempts = 0
    var notices = 0
    val guard = LogPersistenceGuard(clock = { now }, retryDelayNanos = 10L)
    assertFalse(guard.write({ notices++ }) { attempts++; throw IOException("disk full") })
    assertFalse(guard.write({ notices++ }) { attempts++ })
    assertEquals(1, attempts)
    assertEquals(1, notices)
    now = 10L
    assertTrue(guard.write({ notices++ }) { attempts++ })
    assertEquals(2, attempts)
    assertEquals(1, notices)
  }

  @Test fun permissionFailureDoesNotEscape() {
    assertFalse(LogPersistenceGuard().write({}) { throw SecurityException("denied") })
  }

  @Test(expected = CancellationException::class)
  fun coroutineCancellationIsNotSwallowed() {
    LogPersistenceGuard().write({}) { throw CancellationException() }
  }
}
