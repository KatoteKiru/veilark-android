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
}
