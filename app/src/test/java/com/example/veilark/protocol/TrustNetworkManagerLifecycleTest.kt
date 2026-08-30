package com.example.veilark.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TrustNetworkManagerLifecycleTest {
  @Test
  fun `start stop start registers twice and unregisters once`() {
    val lifecycle = TrustNetworkManagerLifecycle()
    var starts = 0
    var stops = 0

    lifecycle.ensure { starts += 1 }
    lifecycle.ensure { starts += 1 }
    lifecycle.stop { stops += 1 }
    lifecycle.ensure { starts += 1 }

    assertEquals(2, starts)
    assertEquals(1, stops)
  }

  @Test
  fun `failed stop still permits next start`() {
    val lifecycle = TrustNetworkManagerLifecycle()
    var starts = 0
    lifecycle.ensure { starts += 1 }

    assertThrows(IllegalStateException::class.java) {
      lifecycle.stop { error("unregister failed") }
    }
    lifecycle.ensure { starts += 1 }

    assertEquals(2, starts)
  }
}
