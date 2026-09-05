# 0.8.0-rc34

Исправлена надёжность сохранения идентификатора установки при импорте подписки Veilark. Повторный импорт и обновление используют ту же установку; сервер принимает все допустимые символы существующих идентификаторов. Сведения об устройстве не уходят сторонним сервисам или через перенаправления. VPN-ядра и маршруты не изменены.

Automatic registration requires a Veilark HTTPS update source. A standalone native protocol link has no account fetch and does not register by itself. Installation IDs are client assertions, not hardware attestation. Build and contract tests do not replace physical-device import or upgrade acceptance.
