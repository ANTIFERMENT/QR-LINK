# QR LINK

Android-приложение QR LINK: передача файлов и OTA-обновления.

## Состояние
Репозиторий создан **приватным**. GitHub Actions сможет собирать APK и создавать приватные Releases, однако **анонимное обновление приложения из приватного GitHub Release не работает**. Для OTA без авторизации требуется публичный релизный канал (например, сделать этот репозиторий публичным либо публиковать артефакты в отдельном публичном репозитории).

> Здесь подготовлена инфраструктура сборки. Пока не добавлены `app/`, Gradle-конфигурация, signing secrets и исходники OTA-интерфейса, реальный APK не может быть собран.

## Сборка и публикация

Workflow: [QR LINK · Android OTA](.github/workflows/ota-release.yml).

1. Разместить **содержимое** исходного Android Gradle-проекта в корне репозитория (`app/`, `settings.gradle`, `build.gradle` и др.). Нельзя загружать весь исходный проект одним ZIP, если ожидается сборка через Actions.
2. Внести четыре секретных параметра в **Settings → Secrets and variables → Actions**: `QR_LINK_KEYSTORE_BASE64`, `QR_LINK_KEYSTORE_PASSWORD`, `QR_LINK_KEY_ALIAS`, `QR_LINK_KEY_PASSWORD`. Не публиковать секреты в коде, Issues и чате.
3. Убедиться в совпадении `applicationId` и сертификата подписи с ранее установленным приложением. Переход с debug-ключа на release-ключ обычно потребует переустановки.
4. В `app/build.gradle` увеличивать `versionCode` и `versionName` для новых релизов.
5. Выполнить **Actions → QR LINK · Android OTA → Run workflow**. После успешной сборки будет создан Release `v<versionName>` с `QR-LINK.apk` и `update.json`.
6. Настроить публичную раздачу, иначе приложение без GitHub-токена не сможет скачать приватные assets.

Адрес будущего манифеста для **публичного** репозитория:

`https://github.com/ANTIFERMENT/QR-LINK/releases/latest/download/update.json`

В манифесте: `schema`, `packageName`, `versionCode`, `versionName`, `apkUrl`, `sha256`, `sizeBytes`, `publishedAt`, `notes`.

## Ограничения

- GitHub workflow и генератор манифеста сами по себе не добавляют кнопку OTA в приложение: код загрузки и установки должен быть интегрирован в Android-проект.
- Для обновления поверх установленного APK необходима совместимая подпись и неизменный `applicationId`, а Android может потребовать подтверждение установки.
- Храните резервную копию keystore и паролей отдельно от GitHub: потеря signing key может исключить бесшовные обновления.

См. [настройку и ограничения](docs/OTA_SETUP_RU.md).
