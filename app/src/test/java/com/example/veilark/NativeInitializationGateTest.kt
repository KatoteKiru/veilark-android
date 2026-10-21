package com.example.veilark

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class NativeInitializationGateTest {
  @Test fun constructionDoesNotStartEngineAndRepeatedAdmissionInitializesOnce() {
    var calls = 0
    val gate = NativeInitializationGate { calls++ }
    assertEquals(0, calls)
    gate.requireReady()
    gate.requireReady()
    assertEquals(1, calls)
  }

  @Test fun concurrentAdmissionsShareOneCompletedInitialization() {
    val calls = AtomicInteger()
    val start = CountDownLatch(1)
    val done = CountDownLatch(12)
    val failure = AtomicReference<Throwable?>()
    val gate = NativeInitializationGate { calls.incrementAndGet() }
    val workers = List(12) {
      Thread {
        try { check(start.await(5, TimeUnit.SECONDS)); gate.requireReady() }
        catch (error: Throwable) { failure.compareAndSet(null, error) }
        finally { done.countDown() }
      }.apply { start() }
    }
    try {
      start.countDown()
      assertTrue(done.await(5, TimeUnit.SECONDS))
      assertNull(failure.get())
      assertEquals(1, calls.get())
    } finally { workers.forEach { it.join(5000) } }
  }

  @Test fun failedInitializationNeverRetriesOrReportsReady() {
    val original = LinkageError("synthetic native load failure")
    var calls = 0
    val gate = NativeInitializationGate { calls++; throw original }
    assertSame(original, assertThrows(LinkageError::class.java) { gate.requireReady() })
    val repeated = assertThrows(IllegalStateException::class.java) { gate.requireReady() }
    assertSame(original, repeated.cause)
    assertEquals(1, calls)
  }

  @Test fun recursiveAdmissionIsRejectedAndCannotRestartInitializer() {
    lateinit var gate: NativeInitializationGate
    var calls = 0
    gate = NativeInitializationGate { calls++; gate.requireReady() }
    val first = assertThrows(IllegalStateException::class.java) { gate.requireReady() }
    assertSame(first, assertThrows(IllegalStateException::class.java) { gate.requireReady() }.cause)
    assertEquals(1, calls)
  }
}
