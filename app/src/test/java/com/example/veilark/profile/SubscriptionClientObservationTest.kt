package com.example.veilark.profile

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class SubscriptionClientObservationTest {
  private val base = "https://sub.senyasenyavski.uk:2096"
  private val id = "_" + "a".repeat(23)

  @Test fun controlledEndpointsAreExactAndPrivacyScoped() {
    for (path in listOf("/managed/abcdefghijklmnop", "/trust/abcdefghijklmnop")) {
      assertTrue(SubscriptionClientObservation.isControlled(base + path, "sub.senyasenyavski.uk", 2096))
    }
    for (url in listOf(
      "https://sub.senyasenyavski.uk.evil.example:2096/trust/abcdefghijklmnop",
      "https://sub.senyasenyavski.uk/trust/abcdefghijklmnop",
      "$base/trust/abcdefghijklmnop?copy=1",
      "$base/trust/abcdefghijklmnop#copy",
      "$base/trust/%61bcdefghijklmnop",
      "$base/cover/abcdefghijklmnop",
      "http://sub.senyasenyavski.uk:2096/trust/abcdefghijklmnop",
      "https://user@sub.senyasenyavski.uk:2096/trust/abcdefghijklmnop"
    )) assertFalse(SubscriptionClientObservation.isControlled(url, "sub.senyasenyavski.uk", 2096))
  }

  @Test fun identityHeadersNeverSurviveARedirect() {
    val headers = mapOf("x-VEILARK-install-id" to id, "Accept-Language" to "ru")
    assertEquals(headers, SubscriptionClientObservation.headersForHop(headers, 0))
    assertEquals(mapOf("Accept-Language" to "ru"), SubscriptionClientObservation.headersForHop(headers, 1))
  }

  @Test fun legacyRandomIdentityIsPreservedIncludingLeadingUnderscore() {
    var stored: String? = null
    var writes = 0
    repeat(2) {
      assertEquals(id, SubscriptionClientObservation.installationId(
        current = { stored }, legacy = { id },
        persist = { stored = it; writes++; true }, generate = { error("must reuse existing ID") }
      ))
    }
    assertEquals(1, writes)
  }

  @Test fun persistenceFailureDoesNotAdvertiseAnEphemeralIdentity() {
    assertTrue(runCatching {
      SubscriptionClientObservation.installationId(
        current = { null }, persist = { false }, generate = { id }
      )
    }.isFailure)
  }

  @Test fun concurrentImportAndRefreshUseOnePersistedIdentity() {
    val stored = java.util.concurrent.atomic.AtomicReference<String?>()
    val generated = java.util.concurrent.atomic.AtomicInteger()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    try {
      val futures = (1..16).map {
        pool.submit<String> {
          SubscriptionClientObservation.installationId(
            current = { stored.get() }, persist = { stored.set(it); true },
            generate = { generated.incrementAndGet(); id }
          )
        }
      }
      futures.forEach { assertEquals(id, it.get(5, java.util.concurrent.TimeUnit.SECONDS)) }
      assertEquals(1, generated.get())
    } finally { pool.shutdownNow() }
  }

  @Test fun failedDurabilityIsRetriedEvenIfPreferencesAlreadyChangedInMemory() {
    var stored: String? = null
    var attempts = 0
    val persist: (String) -> Boolean = { stored = it; ++attempts >= 2 }
    assertTrue(runCatching {
      SubscriptionClientObservation.installationId(current = { stored }, persist = persist, generate = { id })
    }.isFailure)
    assertEquals(id, SubscriptionClientObservation.installationId(
      current = { stored }, persist = persist, generate = { error("must retry same ID") }
    ))
    assertEquals(2, attempts)
  }

  @Test fun labelsAreShortPrintableMetadata() {
    assertEquals("Pixel 8 Pro", SubscriptionClientObservation.ascii(" Pixel  8 Pro\n", "Android device"))
    assertEquals(64, SubscriptionClientObservation.ascii("a".repeat(100), "device").length)
  }
}
