package com.example.veilark.ui

import java.util.Locale

object Strings {
  private val russian = Locale.getDefault().language == "ru"

  val appName get() = "Veilark"
  val connect get() = if (russian) "Подключить" else "Connect"
  val disconnect get() = if (russian) "Отключить" else "Disconnect"
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
  val paste get() = if (russian) "Вставить" else "Paste"
  val file get() = if (russian) "Файл" else "File"
  val profiles get() = if (russian) "Профили" else "Profiles"
  val log get() = if (russian) "Журнал" else "Log"
  val installHelper get() = if (russian) {
    "Установить VPN helper (пароль macOS)"
  } else {
    "Install VPN helper (macOS password)"
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
  val overview get() = if (russian) "Обзор" else "Overview"
  val routing get() = if (russian) "Маршрутизация" else "Routing"
  val diagnostics get() = if (russian) "Диагностика" else "Diagnostics"
  val settings get() = if (russian) "Настройки" else "Settings"
  val selectedProfile get() = if (russian) "Текущий профиль" else "Current profile"
  val networkCore get() = if (russian) "Сетевое ядро" else "Network core"
  val quickStatus get() = if (russian) "Состояние сети" else "Network status"
  val manageProfiles get() = if (russian) "Подписки и серверы" else "Subscriptions and servers"
  val addSubscription get() = if (russian) "Добавить подписку" else "Add subscription"
  val refreshSubscription get() = if (russian) "Обновить подписку" else "Refresh subscription"
  val deleteSubscription get() = if (russian) "Удалить подписку" else "Delete subscription"
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
    "TrustTunnel на macOS работает в полном туннеле. Геомаршрутизация доступна для sing-box."
  } else {
    "TrustTunnel on macOS uses full tunnel. Geo routing is available for sing-box."
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
  val routingSaved get() = if (russian) "Настройки маршрутизации сохранены" else "Routing settings saved"
  val checkUpdates get() = if (russian) "Проверить обновления" else "Check for updates"
  val downloadUpdate get() = if (russian) "Скачать и открыть" else "Download and open"
  val noUpdates get() = if (russian) "Установлена актуальная версия" else "Veilark is up to date"
}
