package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TrustDirectExclusionUpdateTest {
  @Test
  fun blockedNativeUpdateDoesNotBlockInvalidationAndLateResultIsDiscarded() {
    val monitor = Any()
    var current = true
    var published = false
    val updateEntered = CountDownLatch(1)
    val releaseUpdate = CountDownLatch(1)
    val finished = CountDownLatch(1)

    val worker = Thread {
      TrustDirectExclusionUpdate(
        isCurrent = { synchronized(monitor) { current } },
        update = {
          updateEntered.countDown()
          assertTrue(releaseUpdate.await(2, TimeUnit.SECONDS))
          true
        },
        publish = { published = it },
      ).run(listOf("203.0.113.0/24"))
      finished.countDown()
    }
    worker.start()

    assertTrue(updateEntered.await(2, TimeUnit.SECONDS))
    // This is the Stop/Switch analogue: the session fence is immediately
    // reachable while native work is blocked outside the monitor.
    synchronized(monitor) { current = false }
    releaseUpdate.countDown()

    assertTrue(finished.await(2, TimeUnit.SECONDS))
    assertFalse(published)
  }

  @Test
  fun publishesOnlyForAStillCurrentSession() {
    var published: Boolean? = null

    TrustDirectExclusionUpdate(
      isCurrent = { true },
      update = { true },
      publish = { published = it },
    ).run(listOf("2001:db8::/32"))

    assertEquals(true, published)
  }
}
