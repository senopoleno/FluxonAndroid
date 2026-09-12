<p align="center">
  <img src="art/logo.svg" width="120" height="120" alt="Fluxon Icon" />
</p>

<div align="center">

# Fluxon

</div>

<p align="center">
  OpenFlux-клиент для Android. Форк OpenFluxAndroid.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+-3DDC84.svg?logo=android" alt="Platform"/>
  <img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License"/>
</p>

---

## Преимущества форка

- **Стабильность в мобильных сетях (LTE/5G)**:
  - Снижен MTU (до 1400) для исключения фрагментации и потери пакетов в сетях сотовых операторов.
  - Надежный DNS: встроенный локальный кэшер `pdnsd` с резервными резолверами (Cloudflare и Google) и сниженным таймаутом ожидания вместо зависания при плохом сигнале.
  - Исправлена маршрутизация собственного трафика приложения для исключения петель и разрывов.
- **Удобное раздельное туннелирование**:
  - Быстрое добавление российских сервисов (банки, Госуслуги, маркетплейсы) в исключения одной кнопкой, чтобы они работали напрямую без ограничений.
  - Поиск по списку установленных приложений.
- **Импорт и экспорт без ограничений**:
  - Сканирование QR-кодов прямо из скриншотов в галерее — второй экран или камера больше не требуются.
  - Экспорт настроек картинкой с QR-кодом через системное меню «Поделиться» или прямой ссылкой `openflux://`.
- **Интерфейс**:
  - Отображение входящей и исходящей скорости соединения в реальном времени.
  - Поддержка динамических тематических значков (Themed Icons) для Android 13+.
  - Корректная работа светлой и темной тем оформления.

---

## Установка

Готовый APK доступен на странице [Releases](https://github.com/senopoleno/FluxonAndroid/releases).

Требуется Android 8.0 (API 26) или выше.

---

## Сборка

```bash
git clone https://github.com/senopoleno/FluxonAndroid.git
cd FluxonAndroid
./gradlew assembleRelease
```

Требования:
- JDK 17+
- Android SDK (API 35, Build Tools 35.0.0)

Собранный файл:
`app/build/outputs/apk/release/app-release.apk`

---

## Лицензия

GPLv3.
