package com.example.veilark.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CorePreAuthDetailTest {
  @Test fun invalidFutureOrOldProviderFieldsNeverAliasValidCategories() {
    for (raw in listOf(-4L, -1L, 8L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1_0000_0004L)) {
      assertNull(CorePreAuthDetail.fromNative(raw))
    }
    assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), CorePreAuthDetail.entries.map { it.code })
    assertEquals(CorePreAuthDetail.IO, CorePreAuthDetail.fromNative(4))
    assertEquals(CorePreAuthDetail.HANDSHAKE, CorePreAuthDetail.fromNative(5))
  }

  @Test fun detailIsTerminalOnlyOptionalAndNeverReadFromReleasedOrPreviousHandle() = runBlocking {
    var status = 0
    var reason = 11
    var detail = -4L
    val queriedHandles = mutableListOf<Long>()
    val api = object : CoreBetaHandleApi {
      override fun status(handle: Long) = status
      override fun failure(handle: Long) = reason
      override fun progress(handle: Long, field: Int): Long {
        assertEquals(10, field)
        queriedHandles += handle
        return detail
      }
      override fun stop(handle: Long) = 0
      override fun release(handle: Long) = 0
    }
    val owner = CoreBetaLifecycle(api) { }
    assertNull(owner.preAuthFailureDetail())
    owner.startBorrowed({ 7L }, { })
    for (liveStatus in listOf(0, 1, 2, 3)) {
      status = liveStatus
      assertNull(owner.preAuthFailureDetail())
    }
    assertTrue(queriedHandles.isEmpty())
    status = -3; reason = 52
    assertNull(owner.preAuthFailureDetail())
    assertTrue(queriedHandles.isEmpty())
    reason = 11
    assertNull(owner.preAuthFailureDetail()) // 6aad does not support field10.
    detail = 5
    assertEquals(CorePreAuthDetail.HANDSHAKE, owner.preAuthFailureDetail())
    assertTrue(owner.stopAndRelease())
    val before = queriedHandles.size
    assertNull(owner.preAuthFailureDetail())
    assertEquals(before, queriedHandles.size)
    owner.startBorrowed({ 9L }, { })
    detail = 4
    assertEquals(CorePreAuthDetail.IO, owner.preAuthFailureDetail())
    assertEquals(9L, queriedHandles.last())
    assertTrue(owner.stopAndRelease())
  }
}
