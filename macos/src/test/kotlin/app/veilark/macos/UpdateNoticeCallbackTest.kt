package app.veilark.macos

import org.junit.Test
import org.junit.Assert.assertEquals

class UpdateNoticeCallbackTest {
  @Test fun notificationClicksRespectApplicationHandlerLifetime() {
    var opened = 0
    MacNativeChrome.setUpdateNoticeHandler { opened++ }
    try {
      MacNativeChrome.onUpdateNoticeOpened()
      assertEquals(1, opened)
    } finally { MacNativeChrome.setUpdateNoticeHandler(null) }
    MacNativeChrome.onUpdateNoticeOpened()
    assertEquals(1, opened)
  }
}
