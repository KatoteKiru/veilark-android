package app.veilark.macos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeilarkWebLinkTest {
  @Test
  fun acceptsOnlyThePublicVeilarkWebApp() {
    assertEquals(VeilarkWebLink.DEFAULT_URL, VeilarkWebLink.validate(VeilarkWebLink.DEFAULT_URL)?.toString())
    assertNull(VeilarkWebLink.validate("http://sub.senyasenyavski.uk/tma/"))
    assertNull(VeilarkWebLink.validate("https://sub.senyasenyavski.uk/tma/admin"))
    assertNull(VeilarkWebLink.validate("https://sub.senyasenyavski.uk.evil.test/tma/"))
    assertNull(VeilarkWebLink.validate("https://user@sub.senyasenyavski.uk/tma/"))
    assertNull(VeilarkWebLink.validate("https://sub.senyasenyavski.uk/tma/?token=secret"))
  }
}
