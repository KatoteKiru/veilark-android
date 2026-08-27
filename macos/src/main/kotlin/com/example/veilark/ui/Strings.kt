package com.example.veilark.ui

import java.util.Locale

object Strings {
  private val russian = Locale.getDefault().language == "ru"

  val appName get() = "Veilark"
  val macClient get() = if (russian) "Клиент для macOS" else "macOS client"
  val keyboardHint get() = if (russian) "⌘1–⌘5 — разделы" else "⌘1–⌘5 — sections"
  val selectedState get() = if (russian) "Выбрано" else "Selected"
  val notSelectedState get() = if (russian) "Не выбрано" else "Not selected"
  val connect get() = if (russian) "Подключить" else "Connect"
  val disconnect get() = if (russian) "Отключить" else "Disconnect"
  val cancelConnection get() = if (russian) "Отменить" else "Cancel"
  val connecting get() = if (russian) "Подключение…" else "Connecting…"
  val connected get() = if (russian) "Соединение защищено" else "Connection protected"
  val disconnected get() = if (russian) "VPN выключен" else "VPN is off"
  val failed get() = if (russian) "Не удалось подключиться" else "Could not connect"
  val singBox get() = "sing-box"
  val trustTunnel get() = "TrustTunnel"
  val importHint get() = if (russian) {
    "HTTPS-ссылка, буфер или файл подписки"
  } else {
    "HTTPS link, clipboard, or subscription file"
  }
  val importAction get() = if (russian) "Импортировать" else "Import"
  val importFailed get() = if (russian) "Не удалось импортировать подписку" else "Could not import the subscription"
  val paste get() = if (russian) "Вставить" else "Paste"
  val file get() = if (russian) "Файл" else "File"
  val profiles get() = if (russian) "Профили" else "Profiles"
  val log get() = if (russian) "Журнал" else "Log"
  val overview get() = if (russian) "Обзор" else "Overview"
  val overviewSubtitle get() = if (russian) "Подключение, ядро и текущий профиль" else "Connection, engine, and active profile"
  val routing get() = if (russian) "Маршрутизация" else "Routing"
  val diagnostics get() = if (russian) "Диагностика" else "Diagnostics"
  val settings get() = if (russian) "Настройки" else "Settings"
  val engine get() = if (russian) "Сетевое ядро" else "Network engine"
  val activeProfile get() = if (russian) "Активный профиль" else "Active profile"
  val connectionReady get() = if (russian) "Готово к безопасному подключению" else "Ready for a protected connection"
  fun engineActive(kind: com.example.veilark.engine.TunnelEngineKind) = if (russian) {
    "Активно: ${if (kind == com.example.veilark.engine.TunnelEngineKind.TRUST_TUNNEL) trustTunnel else singBox}"
  } else {
    "Active: ${if (kind == com.example.veilark.engine.TunnelEngineKind.TRUST_TUNNEL) trustTunnel else singBox}"
  }
  val trustTunnelSummary get() = if (russian) "Полный туннель TrustTunnel" else "TrustTunnel full tunnel"
  val singBoxSummary get() = if (russian) "Прокси-протоколы sing-box" else "sing-box proxy protocols"
  val disconnectBeforeEngineSwitch get() = if (russian) "Отключите туннель, чтобы сменить ядро" else "Disconnect the tunnel to switch engines"
  val openProfiles get() = if (russian) "Открыть профили" else "Open profiles"
  val openDiagnostics get() = if (russian) "Открыть диагностику" else "Open diagnostics"
  val profileEmptyBody get() = if (russian) "Добавьте ссылку, файл или вставьте профиль из буфера обмена." else "Add a link or file, or paste a profile from the clipboard."
  val profilesSubtitle get() = if (russian) "Импортируйте подписки и выберите узел для подключения" else "Import subscriptions and choose a connection node"
  val addProfile get() = if (russian) "Добавить профиль" else "Add profile"
  val savedConnections get() = if (russian) "Сохранённые подключения" else "Saved connections"
  val clipboardEmpty get() = if (russian) "Буфер обмена пуст" else "Clipboard is empty"
  val profileActions get() = if (russian) "Действия с профилем" else "Profile actions"
  val checkHealth get() = if (russian) "Проверить соединение" else "Check connection"
  val routingSubtitle get() = if (russian) "Проверяемые возможности текущего macOS-клиента" else "Verified capabilities of this macOS client"
  val trustRoutingDescription get() = if (russian) "TrustTunnel использует системный полный TUN-туннель." else "TrustTunnel uses a system-wide TUN tunnel."
  val singRoutingDescription get() = if (russian) "Для sing-box доступны полный туннель, геомаршрутизация и ручные правила доменов и CIDR." else "sing-box supports full tunnel, geo routing, and manual domain and CIDR rules."
  val geoRouting get() = if (russian) "Геомаршрутизация" else "Geo routing"
  val geoRoutingNotReady get() = if (russian) "Правила geoip/geosite ещё не подключены к macOS-модулю. Сейчас режим работает как полный туннель." else "geoip/geosite rules are not connected to the macOS module yet. The current mode is full tunnel."
  val routingBackendNotice get() = if (russian) "Интерфейс не показывает неподдерживаемые настройки как рабочие — они появятся после подключения маршрутизатора к сессии." else "Unsupported controls are not presented as active; they will appear after routing is connected to the session."
  val routingLiveNotice get() = if (russian) "Изменение маршрутов во время активного туннеля пока недоступно." else "Changing routes while the tunnel is active is not available yet."
  val routingMode get() = if (russian) "Режим маршрутизации" else "Routing mode"
  val routingUnavailable get() = if (russian) "Доступно только для sing-box" else "Available for sing-box only"
  val geoRuleSetsMissing get() = if (russian) "Файлы geoip/geosite не установлены — режим недоступен." else "geoip/geosite files are not installed — this mode is unavailable."
  val manualDirectPlaceholder get() = if (russian) "Домены или CIDR, по одному в строке" else "Domains or CIDRs, one per line"
  val manualVpnPlaceholder get() = if (russian) "Домены или CIDR через VPN, по одному в строке" else "Domains or CIDRs through VPN, one per line"
  val routingSaved get() = if (russian) "Настройки маршрутизации сохранены" else "Routing settings saved"
  val routingError get() = if (russian) "Не удалось сохранить маршрутизацию" else "Could not save routing"
  val saveRouting get() = if (russian) "Сохранить маршрутизацию" else "Save routing"
  val healthDetail get() = if (russian) "Последняя проверка" else "Last health check"
  val storageWarning get() = if (russian) "Проблема защищённого хранилища" else "Secure storage warning"
  val diagnosticsSubtitle get() = if (russian) "Технические события запуска и работы туннеля" else "Technical events from tunnel startup and operation"
  val copyLogs get() = if (russian) "Скопировать технический журнал" else "Copy technical log"
  val tunnelStatus get() = if (russian) "Туннель" else "Tunnel"
  val helperStatus get() = if (russian) "Helper" else "Helper"
  val engineStatus get() = if (russian) "Ядра" else "Engines"
  val missing get() = if (russian) "Не найдены" else "Missing"
  val logAppearsAfterAction get() = if (russian) "Здесь появятся технические события после импорта или подключения." else "Technical events appear here after an import or connection attempt."
  val settingsSubtitle get() = if (russian) "Компоненты, версии и безопасное обновление" else "Components, versions, and safe updates"
  val singBoxVersion get() = "sing-box 1.13.19"
  val trustTunnelVersion get() = "TrustTunnel 1.0.49"
  val privilegedHelper get() = if (russian) "Привилегированный helper" else "Privileged helper"
  val installed get() = if (russian) "Установлен" else "Installed"
  val updateChannelNotReady get() = if (russian) "OTA для macOS ещё не настроен" else "macOS OTA is not configured yet"
  val updateChannelVerified get() = if (russian) "OTA внутри приложения: подпись Ed25519 и SHA-256" else "In-app OTA with Ed25519 and SHA-256 verification"
  val updateChannelPreview get() = if (russian) {
    "Preview OTA внутри приложения; Apple notarization ещё не подключена"
  } else {
    "In-app preview OTA; Apple notarization is not configured yet"
  }
  val updateCheckFailed get() = if (russian) "Не удалось проверить обновления" else "Could not check for updates"
  val updateDownloadFailed get() = if (russian) "Не удалось загрузить обновление" else "Could not download the update"
  val signedUpdateReady get() = if (russian) "Подписанное обновление macOS готово к загрузке" else "A signed macOS update is ready to download"
  val about get() = if (russian) "О Veilark" else "About Veilark"
  val aboutDescription get() = if (russian) "Независимый клиент VPN для macOS" else "Independent VPN client for macOS"
  val installHelper get() = if (russian) {
    "Установить VPN helper (пароль macOS)"
  } else {
    "Install VPN helper (macOS password)"
  }
  val helperInstallFailed get() = if (russian) {
    "Не удалось установить VPN helper"
  } else {
    "Could not install the VPN helper"
  }
  val disconnectBeforeHelperInstall get() = if (russian) {
    "Отключите VPN перед установкой helper"
  } else {
    "Disconnect the VPN before installing the helper"
  }
  val helperMissing get() = if (russian) {
    "Для системного TUN нужен helper. Нажмите «Установить VPN helper»."
  } else {
    "System TUN needs the helper. Click “Install VPN helper”."
  }
  val enginesMissing get() = if (russian) {
    "Скачайте ядра: macos/scripts/fetch-engines.sh"
  } else {
    "Download engines: macos/scripts/fetch-engines.sh"
  }
  val noProfiles get() = if (russian) "Профилей пока нет" else "No profiles yet"
  val servers get() = if (russian) "Серверы" else "Servers"
  val automatic get() = if (russian) "Автовыбор" else "Automatic"
  val technicalLogEmpty get() = if (russian) "Событий пока нет" else "No events yet"
  val selectedProfile get() = if (russian) "Текущий профиль" else "Current profile"
  val networkCore get() = if (russian) "Сетевое ядро" else "Network core"
  val quickStatus get() = if (russian) "Состояние сети" else "Network status"
  val manageProfiles get() = if (russian) "Подписки и серверы" else "Subscriptions and servers"
  val addSubscription get() = if (russian) "Добавить подписку" else "Add subscription"
  val refreshSubscription get() = if (russian) "Обновить подписку" else "Refresh subscription"
  val deleteSubscription get() = if (russian) "Удалить подписку" else "Delete subscription"
  val deleteSubscriptionConfirm get() = if (russian) "Удалить выбранную подписку и все её подключения?" else "Delete the selected subscription and all of its connections?"
  val cancel get() = if (russian) "Отмена" else "Cancel"
  val copyLog get() = if (russian) "Скопировать журнал" else "Copy log"
  val clearLog get() = if (russian) "Очистить журнал" else "Clear log"
  val fullTunnel get() = if (russian) "Весь трафик через VPN" else "All traffic through VPN"
  val fullTunnelHint get() = if (russian) {
    "Предсказуемый режим без локальных исключений"
  } else {
    "Predictable mode without local exclusions"
  }
  val ruDirect get() = if (russian) "Россия напрямую" else "Russia direct"
  val ruDirectHint get() = if (russian) {
    "Российские домены и IP идут мимо VPN; остальное — через туннель"
  } else {
    "Russian domains and IPs bypass VPN; everything else uses the tunnel"
  }
  val trustRuDirectHint get() = if (russian) {
    "Российские IP-сети GeoIP идут напрямую; остальной интернет — через TrustTunnel"
  } else {
    "Russian GeoIP networks go direct; the rest of the internet uses TrustTunnel"
  }
  val manualRouting get() = if (russian) "Ручные правила" else "Manual rules"
  val manualRoutingHint get() = if (russian) {
    "Списки доменов и CIDR для прямого доступа или VPN"
  } else {
    "Domain and CIDR lists for direct access or VPN"
  }
  val directRules get() = if (russian) "Напрямую" else "Direct"
  val vpnRules get() = if (russian) "Через VPN" else "Through VPN"
  val save get() = if (russian) "Сохранить" else "Save"
  val noSelectedProfile get() = if (russian) "Профиль не выбран" else "No profile selected"
  val trustRoutingLimit get() = if (russian) {
    "TrustTunnel поддерживает полный туннель и прямой маршрут российских IP-сетей GeoIP."
  } else {
    "TrustTunnel supports full tunnel and direct routing for Russian GeoIP networks."
  }
  val installComponents get() = if (russian) "Системные компоненты" else "System components"
  val updateChannel get() = if (russian) "Обновления" else "Updates"
  val updatesUnavailable get() = if (russian) {
    "Безопасный OTA включится после Developer ID, notarization и подписанного канала обновлений."
  } else {
    "Secure OTA will be enabled after Developer ID, notarization, and a signed update channel are configured."
  }
  val quit get() = if (russian) "Завершить Veilark" else "Quit Veilark"
  val open get() = if (russian) "Открыть Veilark" else "Open Veilark"
  val notInstalled get() = if (russian) "Не установлен" else "Not installed"
  val ready get() = if (russian) "Готово" else "Ready"
  val checkUpdates get() = if (russian) "Проверить обновления" else "Check for updates"
  val installUpdate get() = if (russian) "Установить и перезапустить" else "Install and restart"
  val noUpdates get() = if (russian) "Установлена актуальная версия" else "Veilark is up to date"
}
