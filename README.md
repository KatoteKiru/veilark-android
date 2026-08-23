# Veilark for Android

Veilark — независимый Android-клиент для пользовательских VPN-профилей. В
приложении есть два сетевых движка: `sing-box` для совместимых прокси-протоколов
и `TrustTunnel` для ссылок `tt://`. Репозиторий содержит приложение, адаптеры,
тесты и инструкции воспроизводимой сборки. Серверная часть и VPN-доступ в проект
не входят.

## Возможности

- Android 10+ (`minSdk 29`), `arm64-v8a` и `armeabi-v7a`;
- независимые режимы sing-box 1.13.14 и TrustTunnel 1.0.49;
- несколько подписок и профилей с обновлением, переключением и удалением;
- импорт из ссылки, буфера обмена, файла и QR-кода;
- ссылки VLESS, Trojan, Hysteria 2, VMess, Shadowsocks, TUIC и AnyTLS;
- совместимое подмножество JSON sing-box/Xray и YAML Clash/Mihomo;
- ссылки TrustTunnel `tt://` и списки таких ссылок;
- раздельное туннелирование по приложениям для обоих движков;
- ручные правила доменов и CIDR для sing-box;
- Quick Settings tile, технический журнал и ручная проверка задержки;
- зашифрованное хранение профилей через Android Keystore.

Импорт намеренно ограничен теми форматами, которые приложение может проверить
и безопасно преобразовать. Произвольный граф конфигурации sing-box, все плагины
Shadowsocks и каждый диалект сторонних панелей не заявлены как совместимые.

## Публичная и частная сборки

| Вариант | Application ID | Встроенные профили | Самообновление |
|---|---|---:|---:|
| `oss` | `app.veilark.android` | нет | нет |
| `private` | задаётся локально | из локальных файлов | подписанный частный канал |

Обе сборки используют один код приложения. Приватные адреса, подписки и ключи
хранятся только в игнорируемом `private.properties` и не входят в Git. Публичная
сборка дополнительно удаляет разрешение установки APK и update `FileProvider` из
итогового манифеста.

## Сборка OSS

Требуются JDK 17 и Android SDK с API 36.

```bash
./gradlew testOssDebugUnitTest lintOssRelease assembleOssDebug
```

Устанавливаемый debug APK появится в
`app/build/outputs/apk/oss/debug/app-oss-debug.apk`. Для подписанного release APK
задайте четыре переменные окружения:

```text
VEILARK_OSS_KEYSTORE
VEILARK_OSS_STORE_PASSWORD
VEILARK_OSS_KEY_ALIAS
VEILARK_OSS_KEY_PASSWORD
```

После этого выполните `./gradlew assembleOssRelease`. Закрытый ключ нельзя
добавлять в репозиторий. Полный порядок релиза описан в
[`docs/RELEASING.md`](docs/RELEASING.md).

## Проверка импортов

JVM-тесты покрывают отдельные URI, base64-списки, JSON sing-box/Xray,
Clash/Mihomo YAML и смешанные списки с `tt://`. Instrumentation-тест использует
публичный localhost fixture из TrustTunnelClient и вызывает настоящее native
ядро. Он требует ARM-устройство:

```bash
./gradlew assembleOssDebugAndroidTest
./gradlew connectedOssDebugAndroidTest
```

Обычный x86_64 Android Emulator не подходит: поставляемые native-библиотеки
имеют только ARM ABI.

## Безопасность и приватность

OSS-вариант не содержит VPN-аккаунтов, конфигурации аналитики, рекламных SDK и
частного OTA.
Приложение обращается к адресам, которые импортировал пользователь, а также к
явно запускаемым пользователем проверкам соединения. Технический журнал
редактирует чувствительные значения. Это клиент, а не гарантия анонимности:
результат зависит от импортированного сервера и его оператора.

Уязвимости следует сообщать по правилам из [`SECURITY.md`](SECURITY.md), не через
публичную задачу.

## Поддержать проект

Veilark остаётся бесплатным и открытым. Если приложение оказалось полезным,
можно поддержать дальнейшую разработку и тестирование:

- [ЮMoney](https://yoomoney.ru/fundraise/1JR7FR9V605.260823)
- USDT в сети TON: `UQA-PCRmPUXwmpNd7Zoys4rRHbz6pA8AZd7EuX54gEk_sBkS`

Для перевода USDT выбирайте только сеть TON. Перевод через другую сеть может
быть потерян.

## Лицензии

Код Veilark распространяется на условиях GPL-3.0-or-later. В APK включён
sing-box (GPL-3.0-or-later) и адаптированный Android-клиент TrustTunnel
(Apache-2.0). Точные версии, исходные коммиты, патчи и хеши находятся в
`vendor/`; дополнительные сведения — в
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Veilark — самостоятельный проект и не является официальным приложением
SagerNet, sing-box, AdGuard или TrustTunnel.

## English

Veilark is an independent Android client for user-supplied VPN profiles. The
open-source build ships no account, server, subscription, or private update
channel. See the sections above for the supported import subset, build commands,
security model, and exact third-party provenance.
