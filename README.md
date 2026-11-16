# ZeroTune

A lightweight Android VPN client built on the [Xray](https://github.com/XTLS/Xray-core) core. Made first of all for Huawei and Honor phones, where background VPN apps are often killed by the system.

> **Русская версия:** [README.ru.md](README.ru.md)

ZeroTune does **not** include or provide any servers. You bring your own `vless://` key.

## Why

Most VPN clients have a huge number of settings nobody uses. ZeroTune keeps only the essential basics and makes them convenient.

## Features

- **VLESS** servers (tcp / ws / grpc, TLS / Reality, Vision) as cards, import from the clipboard (several keys at once), live switching
- **Ping check** for every server before connecting
- **Routing:** per app (all / only selected / all except selected) and per site (direct / via VPN / block, "Russian sites direct")
- **MTU** setting (1280–1500)
- **Speed and ping** in the app and in the notification (can be turned off)
- **Quick settings tile** that follows the real tunnel state
- **Huawei / Honor helpers:** launch-manager guide, diagnostics screen, network-change handling, watchdog
- **Appearance:** presets, custom colors (color wheel, saved colors), animated power button
- **Logs tab** (bottom menu): app and Xray core events, filter by importance, copy/share with keys, UUIDs and server addresses hidden
- **Languages:** English and Russian

## Requirements

- Android 7.0+ (API 24), `arm64-v8a` only
- Android Studio, JDK 17 (AGP 8.5.2, Kotlin 1.9.24, compileSdk 34)

## Building

The repository does not contain the two binary dependencies. Add them yourself:

1. **Xray core** (required). Put `libv2ray.aar` (a build of [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) into `app/libs/`.
2. **hev-socks5-tunnel** (optional, usually faster). Build [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) for `arm64-v8a` and put `libhev-socks5-tunnel.so` into `app/src/main/jniLibs/arm64-v8a/`. The JNI class must be `com.v2ray.ang.service.TProxyService` (the Kotlin side is in `app/src/main/java/com/v2ray/ang/service/`). Without this library the app falls back to the built-in Xray TUN. The *Diagnostics* screen shows whether it was loaded and why not.
3. In Android Studio: **Build → Generate App Bundles or APKs → Generate APKs**.

Release builds are signed with the debug key by default. Use your own keystore if you distribute the APK.

## Project layout

| Path | What is there |
| --- | --- |
| `app/src/main/java/app/zerotune/` | the app: UI, VPN service, quick tile, Xray config builder, routing, themes |
| `app/src/main/java/com/v2ray/ang/service/` | JNI bridge for hev-socks5-tunnel |
| `app/src/main/res/values*/strings.xml` | all texts (English in `values-en`, Russian in `values`) |
| `CHANGELOG.md` | version history (in Russian) |

## Credits

- [Xray-core](https://github.com/XTLS/Xray-core), [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — see each project for its license
- The JNI bridge layout follows the one used by [v2rayNG](https://github.com/2dust/v2rayNG)

Created by **nap_men** with the help of AI (Claude by Anthropic).

## License

ZeroTune is released under the [MIT License](LICENSE). The bundled third-party components keep their own licenses (see Credits).
