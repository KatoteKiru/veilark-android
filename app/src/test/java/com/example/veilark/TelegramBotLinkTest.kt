package com.example.veilark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramBotLinkTest {
  @Test
  fun acceptsConfiguredTelegramHttpsHosts() {
    assertEquals(
      "https://t.me/senyavpn_bot?start=client_android",
      TelegramBotLink.validate("https://t.me/senyavpn_bot?start=client_android")?.toString(),
    )
    assertEquals(
      "https://telegram.me/senyavpn_bot?start=client_android",
      TelegramBotLink.validate("https://telegram.me/senyavpn_bot?start=client_android")?.toString(),
    )
  }

  @Test
  fun rejectsNonHttpsOrLookalikeHosts() {
    assertNull(TelegramBotLink.validate("http://t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("tg://resolve?domain=senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://t.me.evil.example/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://user@t.me/senyavpn_bot"))
    assertNull(TelegramBotLink.validate("https://t.me:443/senyavpn_bot?start=client_android"))
    assertNull(TelegramBotLink.validate("https://t.me/another_bot?start=client_android"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=another_client"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=client_android&admin=true"))
    assertNull(TelegramBotLink.validate("https://t.me/senyavpn_bot?start=client_android#fragment"))
    assertNull(TelegramBotLink.validate("not a url"))
  }
}
