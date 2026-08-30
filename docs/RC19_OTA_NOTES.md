# 0.8.0-rc19 private OTA notes

Исправлена стабильность подключения и остановки: TrustTunnel теперь корректно
завершает неудачный запуск даже без обратного callback, отбрасывает запоздалые
события старой сессии, освобождает сетевой callback и допускает повторное
подключение; проверка задержки ограничена по числу узлов и времени, а служебные
sing-box подключения и остановка native core выполняются вне главного потока
Android.

This file is release input only. Building or verifying rc19 must not upload or
mutate the live OTA channel.
