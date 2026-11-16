# ZeroTune

Лёгкий VPN-клиент для Android на ядре [Xray](https://github.com/XTLS/Xray-core). Сделан в первую очередь для Huawei и Honor, где система часто убивает VPN-приложения в фоне.

> **English version:** [README.md](README.md)

ZeroTune **не содержит и не предоставляет серверов**. Нужен свой ключ `vless://`.

## Зачем

В большинстве VPN-клиентов огромное количество настроек, которыми никто не пользуется. Здесь только самая базовая база, но удобная.

## Возможности

- Серверы **VLESS** (tcp / ws / grpc, TLS / Reality, Vision) в виде карточек, импорт из буфера обмена (можно сразу несколько ключей), переключение на лету
- **Проверка пинга** каждого сервера до подключения
- **Маршрутизация:** по приложениям (все / только выбранные / все, кроме выбранных) и по сайтам (напрямую / через VPN / блок, «российские сайты напрямую»)
- Настройка **MTU** (1280–1500)
- **Скорость и пинг** в приложении и в уведомлении (можно выключить)
- **Плитка в шторке**, которая показывает реальное состояние туннеля
- **Для Huawei / Honor:** инструкция по Диспетчеру запуска, экран диагностики, обработка смены сети, watchdog
- **Оформление:** готовые темы, свои цвета (цветовой круг, сохранённые цвета), анимированная кнопка включения
- **Вкладка «Логи»** (нижнее меню): события приложения и ядра Xray, фильтр по важности, копирование и отправка со скрытыми ключами, UUID и адресами серверов
- **Языки:** русский и английский

## Требования

- Android 7.0+ (API 24), только `arm64-v8a`
- Android Studio, JDK 17 (AGP 8.5.2, Kotlin 1.9.24, compileSdk 34)

## Сборка

Два бинарных файла в репозитории не хранятся, добавь их сам:

1. **Ядро Xray** (обязательно). Положи `libv2ray.aar` (сборка [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) в `app/libs/`.
2. **hev-socks5-tunnel** (по желанию, обычно быстрее). Собери [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) под `arm64-v8a` и положи `libhev-socks5-tunnel.so` в `app/src/main/jniLibs/arm64-v8a/`. JNI-класс должен называться `com.v2ray.ang.service.TProxyService` (Kotlin-часть лежит в `app/src/main/java/com/v2ray/ang/service/`). Без этой библиотеки приложение работает на встроенном TUN Xray. Экран «Диагностика» показывает, загрузилась ли она, а если нет — почему.
3. В Android Studio: **Build → Generate App Bundles or APKs → Generate APKs**.

Release-сборка по умолчанию подписывается debug-ключом. Для раздачи APK используй свой keystore.

## Структура

| Путь | Что там |
| --- | --- |
| `app/src/main/java/app/zerotune/` | приложение: интерфейс, VPN-сервис, плитка, сборка конфига Xray, маршрутизация, темы |
| `app/src/main/java/com/v2ray/ang/service/` | JNI-мост для hev-socks5-tunnel |
| `app/src/main/res/values*/strings.xml` | все тексты (английский в `values-en`, русский в `values`) |
| `CHANGELOG.md` | история версий |

## Благодарности

- [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — лицензии смотри в самих проектах
- Устройство JNI-моста повторяет то, что используется в [v2rayNG](https://github.com/2dust/v2rayNG)

Создано **nap_men** с помощью ИИ (Claude от Anthropic).

## Лицензия

ZeroTune распространяется по лицензии [MIT](LICENSE). Сторонние компоненты сохраняют свои лицензии (см. раздел с благодарностями).
