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
    assertEquals(
      TelegramBotLink.DEFAULT_SUPPORT_URL,
      TelegramBotLink.validate(
        TelegramBotLink.DEFAULT_SUPPORT_URL,
        TelegramBotLink.Destination.SUPPORT,
      )?.toString(),
    )
  }

  @Test
  fun rejectsUnsafeOrLookalikeUrls() {
    assertNull(TelegramBotLink.validate("http://t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("tg://resolve?domain=senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://t.me.evil.example/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://user@t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://t.me/another_bot?start=client_macos"))
    assertNull(TelegramBotLink.validate("https://t.me:443/senyavpn_bot?start=client_macos"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=support"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=client_macos&next=evil"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=client_macos#fragment"))
    assertNull(TelegramBotLink.validate("not a url"))
  }

  @Test
  fun readsConfigurableSubscriptionUrl() {
    val key = "veilark.telegramBotUrl"
    val before = System.getProperty(key)
    try {
      System.setProperty(key, "https://telegram.me/senyavpn_bot?start=client_macos")
      assertEquals(
        "https://telegram.me/senyavpn_bot?start=client_macos",
        TelegramBotLink.configuredUrl(),
      )
    } finally {
      if (before == null) System.clearProperty(key) else System.setProperty(key, before)
    }
  }
}
