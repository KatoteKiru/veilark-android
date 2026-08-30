package app.veilark.macos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramBotLinkTest {
  @Test
  fun acceptsTelegramHttpsHosts() {
    assertEquals(
      TelegramBotLink.DEFAULT_URL,
      TelegramBotLink.validate(TelegramBotLink.DEFAULT_URL)?.toString(),
    )
    assertEquals(
      "https://telegram.me/senyavpn_bot?start=client_macos",
      TelegramBotLink.validate("https://telegram.me/senyavpn_bot?start=client_macos")?.toString(),
    )
  }

  @Test
  fun rejectsUnsafeOrLookalikeUrls() {
    assertNull(TelegramBotLink.validate("http://t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("tg://resolve?domain=senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://t.me.evil.example/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://user@t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("not a url"))
  }
}
