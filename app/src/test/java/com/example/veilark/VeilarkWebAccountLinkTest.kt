package com.example.veilark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeilarkWebAccountLinkTest {
  @Test
  fun acceptsOnlyTheCanonicalHttpsCabinetUrl() {
    assertEquals(
      "https://sub.senyasenyavski.uk/tma/",
      VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk/tma/")?.toString(),
    )
  }

  @Test
  fun rejectsLookalikeOrModifiedDestinations() {
    assertNull(VeilarkWebAccountLink.validate("http://sub.senyasenyavski.uk/tma/"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk.evil.example/tma/"))
    assertNull(VeilarkWebAccountLink.validate("https://user@sub.senyasenyavski.uk/tma/"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk:2096/tma/"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk/tma"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk/tma/admin"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk/tma/?next=admin"))
    assertNull(VeilarkWebAccountLink.validate("https://sub.senyasenyavski.uk/tma/#token"))
    assertNull(VeilarkWebAccountLink.validate("not a url"))
  }
}
