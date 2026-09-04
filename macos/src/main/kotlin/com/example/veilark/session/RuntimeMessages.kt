package com.example.veilark.session

import java.util.Locale

internal object RuntimeMessages {
  private val russian: Boolean get() = Locale.getDefault().language == "ru"
  private fun text(ru: String, en: String): String = if (russian) ru else en

  val readSingBoxFailed get() = text("Не удалось прочитать защищённый каталог sing-box", "Could not read the protected sing-box catalog")
  val readTrustFailed get() = text("Не удалось прочитать защищённый каталог TrustTunnel", "Could not read the protected TrustTunnel catalog")
  val readPreferencesFailed get() = text("Не удалось прочитать защищённые настройки", "Could not read protected settings")
  val savePreferencesFailed get() = text("Не удалось сохранить защищённые настройки", "Could not save protected settings")
  val keychainUnavailable get() = text("Связка ключей macOS недоступна; изменения профилей заблокированы", "macOS Keychain is unavailable; profile changes are disabled")
  val waitForOperation get() = text("Подождите завершения текущей операции", "Wait for the current operation to finish")
  val disconnectBeforeHelper get() = text("Отключите VPN перед установкой helper", "Disconnect the VPN before installing the helper")
  val helperInstalled get() = text("VPN helper установлен", "VPN helper installed")
  val disconnectBeforeImport get() = text("Отключите VPN перед импортом подписки", "Disconnect the VPN before importing a subscription")
  val emptyImport get() = text("Пустой импорт", "The import is empty")
  val subscriptionEngineMismatch get() = text("Формат обновлённой подписки не соответствует выбранному ядру", "The updated subscription format does not match the selected engine")
  val disconnectBeforeRefresh get() = text("Отключите VPN перед обновлением подписки", "Disconnect the VPN before refreshing the subscription")
  val noRemoteSource get() = text("У выбранного профиля нет HTTPS-ссылки для обновления", "The selected profile has no HTTPS refresh URL")
  val subscriptionRefreshed get() = text("Подписка обновлена", "Subscription refreshed")
  val disconnectBeforeDelete get() = text("Отключите VPN перед удалением подписки", "Disconnect the VPN before deleting the subscription")
  val subscriptionNotSelected get() = text("Подписка не выбрана", "No subscription is selected")
  val subscriptionDeleted get() = text("Подписка удалена", "Subscription deleted")
  val connecting get() = text("Подключение…", "Connecting…")
  val reconnecting get() = text("Смена сети: переподключение…", "Network changed: reconnecting…")
  val handoverDegraded get() = text("Смена сети не восстановила туннель", "Network handover did not restore the tunnel")
  val engineMissing get() = text("Выбранное сетевое ядро не найдено", "The selected network engine is missing")
  val installHelperFirst get() = text("Сначала установите VPN helper в настройках", "Install the VPN helper in Settings first")
  val engineDidNotStart get() = text("Ядро не запустилось", "The network engine did not start")
  val routesFailed get() = text("Туннель не поднял маршруты. Обновите helper (пароль macOS) и подключитесь снова.", "The tunnel could not install routes. Update the helper with your macOS password and reconnect.")
  val singBoxResolutionFailed get() = text("sing-box не смог разрешить адрес сервера. Обновите подписку или используйте TrustTunnel.", "sing-box could not resolve the server address. Refresh the subscription or use TrustTunnel.")
  val connectCancelled get() = text("Подключение отменено", "Connection cancelled")
  val disconnectQueued get() = text("Отключение запрошено", "Disconnect requested")
  val connectFirst get() = text("Сначала подключите VPN", "Connect the VPN first")
  val engineNotRunning get() = text("Сетевое ядро не запущено", "The network engine is not running")
  val disconnectBeforeEngineSwitch get() = text("Отключите VPN перед сменой ядра", "Disconnect the VPN before switching engines")
  val disconnectBeforeRouting get() = text("Отключите VPN перед изменением маршрутизации", "Disconnect the VPN before changing routing")
  val unknownRoutingMode get() = text("Неизвестный режим маршрутизации", "Unknown routing mode")
  val chooseSingBoxForRouting get() = text("Выберите sing-box подписку перед настройкой ручных правил", "Select a sing-box subscription before configuring manual rules")
  val geoUpdated get() = text("GEO-данные обновлены", "GEO data updated")
  val geoUpdateFailed get() = text("Не удалось обновить GEO-данные; предыдущая версия сохранена", "Could not update GEO data; the previous version was retained")
  val routingUpdated get() = text("Маршрутизация обновлена", "Routing updated")
  val disconnectBeforeProfileSwitch get() = text("Отключите VPN перед сменой профиля", "Disconnect the VPN before switching profiles")
  val singBoxProfileMissing get() = text("Выбранный sing-box профиль отсутствует", "The selected sing-box profile is missing")
  val serverMissing get() = text("Выбранный сервер отсутствует в подписке", "The selected server is missing from the subscription")
  val trustProfileMissing get() = text("Выбранный TrustTunnel профиль отсутствует", "The selected TrustTunnel profile is missing")
  val chooseSingBox get() = text("Выберите sing-box профиль", "Select a sing-box profile")
  val geoFilesMissing get() = text("Файлы геомаршрутизации не установлены", "Geographic routing files are not installed")
  val geoIpInvalid get() = text("Файл GeoIP RU повреждён или несовместим", "The GeoIP RU file is damaged or incompatible")
  val chooseTrust get() = text("Выберите TrustTunnel профиль", "Select a TrustTunnel profile")
  val disconnected get() = text("Туннель отключён", "Tunnel disconnected")
  val engineExited get() = text("Сетевое ядро неожиданно завершилось", "The network engine exited unexpectedly")
  val tunnelStartFailed get() = text("Не удалось запустить туннель", "Could not start the tunnel")
  val helperNeedsRoot get() = text("Helper без root. Нажмите «Установить VPN helper» ещё раз.", "The helper has no root privileges. Install the VPN helper again.")
  val configRejected get() = text("Путь к конфигу отклонён. Обновите helper (пароль macOS) и подключитесь снова.", "The configuration path was rejected. Update the helper with your macOS password and reconnect.")
  val trustRoutesFailed get() = text("TrustTunnel не смог настроить маршруты TUN", "TrustTunnel could not configure TUN routes")
  val singBoxDnsRejected get() = text("sing-box отклонил DNS. Обновите приложение и подключитесь снова.", "sing-box rejected the DNS configuration. Update the app and reconnect.")
  val engineExitedImmediately get() = text("Ядро сразу завершилось", "The network engine exited immediately")
  val healthDnsHttpsOk get() = text("DNS и HTTPS доступны", "DNS and HTTPS are available")
  val healthHttpsOnly get() = text("HTTPS доступен; системный DNS требует проверки", "HTTPS is available; system DNS needs attention")
  val healthDnsOnly get() = text("DNS доступен, HTTPS не отвечает", "DNS is available, but HTTPS is not responding")
  val healthUnavailable get() = text("DNS и HTTPS не отвечают", "DNS and HTTPS are not responding")

  fun helperInstallFailed(reason: String?) = withReason("Не удалось установить VPN helper", "Could not install the VPN helper", reason)
  fun trustImported(count: Int) = text("Импортировано TrustTunnel профилей: $count", "Imported TrustTunnel profiles: $count")
  fun singBoxImported(count: Int) = text("Импортировано sing-box профилей: $count", "Imported sing-box profiles: $count")
  fun importFailed(reason: String?) = withReason("Ошибка импорта", "Subscription import failed", reason)
  fun refreshFailed(reason: String?) = withReason("Обновление подписки", "Subscription refresh failed", reason)
  fun healthDegraded(detail: String) = text("Туннель запущен, но проверка доступа требует внимания: $detail", "The tunnel is running, but connectivity needs attention: $detail")
  fun connected(engine: String, detail: String) = text("Туннель подключён ($engine); $detail", "Tunnel connected ($engine); $detail")
  fun connectFailed(reason: String?) = withReason("Ошибка подключения", "Connection failed", reason)
  fun healthChecked(detail: String) = text("Проверка сети: $detail", "Network check: $detail")
  fun disconnectFailed(reason: String?) = withReason("Не удалось остановить туннель", "Could not stop the tunnel", reason)

  fun localizedFailure(reason: String?, englishFallback: String): String {
    val value = reason?.trim().orEmpty()
    if (value.isEmpty()) return englishFallback
    return if (russian || value.none { it in '\u0400'..'\u04FF' }) value else englishFallback
  }

  private fun withReason(ru: String, en: String, reason: String?): String {
    val safeReason = reason?.trim().orEmpty()
    return if (russian) {
      if (safeReason.isEmpty()) ru else "$ru: $safeReason"
    } else {
      if (safeReason.isEmpty() || safeReason.any { it in '\u0400'..'\u04FF' }) en else "$en: $safeReason"
    }
  }
}
