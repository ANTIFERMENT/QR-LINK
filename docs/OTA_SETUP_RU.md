# QR LINK · настройка OTA

Репозиторий: https://github.com/ANTIFERMENT/QR-LINK

## Что уже настроено
- GitHub Actions: `.github/workflows/ota-release.yml`
- Генерация `update.json` с данными APK и SHA-256: `scripts/make_manifest.py`
- Сборка `:app:assembleRelease`, выравнивание, подпись и проверка APK.
- Релизы `v<versionName>` с файлами `QR-LINK.apk` и `update.json`.
- Триггеры: `push` в `main` при изменении Android-проекта, либо ручной `workflow_dispatch`.

## Что требуется для полноценного OTA

**1. Исходники.** Есть два варианта: (а) разместить обычные Gradle-исходники `app/`, `settings.gradle(.kts)`, `build.gradle(.kts)` в корне; (б) **загрузить в корень единственный файл `QR_LINK_SOURCE.zip`** с настоящим Android-проектом QR LINK, даже если проект лежит внутри папки в ZIP. Сценарий GitHub Actions автоматически импортирует ZIP, проверит его пути, исключит временные сборки и отдельно типовые файлы ключей, добавит интернет/пакетную установку и базовый Java-класс OTA, если нет собственного `QrLinkOta.java`. До появления реального Android-проекта сборка намеренно завершается ошибкой. Нельзя считать проверенные инструменты подтверждением успешной сборки QR LINK.

**2. Подпись.** В GitHub → Settings → Secrets and variables → Actions добавить:

| Secret | Значение |
| --- | --- |
| `QR_LINK_KEYSTORE_BASE64` | Base64 содержимого файла keystore, в одну строку |
| `QR_LINK_KEYSTORE_PASSWORD` | Пароль keystore |
| `QR_LINK_KEY_ALIAS` | Alias ключа |
| `QR_LINK_KEY_PASSWORD` | Пароль ключа |

Для уже установленного APK сопоставить сертификат исходной подписи и сертификат выпуска. С другим ключом Android обычно откажется обновлять приложение поверх старого. Если приложение собиралось `assembleDebug` в Code on the Go, следует отдельно получить именно **тот debug.keystore**, которым оно подписывалось. Не публиковать пароли или keystore в Issues/PR/чате. Сохранить офлайновый бэкап.

Чтобы сделать BASE64 в Termux (из **своего**, локально хранящегося keystore):

```bash
base64 -w 0 /путь/к/вашему/qr-link.keystore
```

Полученную строку вставить только в Secret, не в Git.

**3. Android-версия.** Сохранять `applicationId`, повышать `versionCode` (целое число) и `versionName` (например, 0.3.9). Один номер `versionName` = один неизменяемый Release.

**4. Публичный доступ.** Сейчас репозиторий PRIVATE. APK и манифест в его Releases не смогут анонимно скачиваться из приложения. Для OTA без токена следует сделать **этот** репозиторий публичным либо организовать отдельный публичный канал выдачи бинарников. Не встраивать GitHub Personal Access Token в APK.

**5. Клиентская интеграция.** В QR LINK нужно использовать HTTPS:

```text
https://github.com/ANTIFERMENT/QR-LINK/releases/latest/download/update.json
```

Сервер возвращает:

```json
{
  "schema": 1,
  "packageName": "APPLICATION_ID_FROM_SIGNED_APK",
  "versionCode": 39,
  "versionName": "0.3.9",
  "apkUrl": "https://github.com/ANTIFERMENT/QR-LINK/releases/download/v0.3.9/QR-LINK.apk",
  "sha256": "<64-character-sha256>",
  "sizeBytes": 12345678,
  "publishedAt": "2026-10-29T00:00:00+00:00",
  "notes": "Описание обновления"
}
```

Приложение должно сравнивать `versionCode` с установленным, проверять `packageName`, скачивать APK в фоне, проверять SHA-256, затем передать APK системному установщику Android с `content://` URI, который разрешён для чтения. На Android 8+ может потребоваться разрешение «Установка неизвестных приложений». На обычном устройстве подтверждение установки нельзя гарантированно пропустить.

**Важно:** workflow создаёт сервис публикации, но сам по себе не изменяет интерфейс QR LINK. Для кнопок и OTA-клиента нужны исходники конкретной версии приложения.

## Запуск

После загрузки исходников и добавления Secrets: https://github.com/ANTIFERMENT/QR-LINK/actions/workflows/ota-release.yml → Run workflow. Тесты Python-инструментов отдельно проверены: https://github.com/ANTIFERMENT/QR-LINK/actions/workflows/ota-tools-tests.yml .

После успешного выполнения: https://github.com/ANTIFERMENT/QR-LINK/releases

## Диагностика
- `app/ is missing`: нет Android-проекта.
- `Missing signing Secret`: не настроена подпись.
- `Expected one release APK`: несколько APK/flavors/splits, нужно уточнить выбор.
- Ошибка Gradle/SDK: сверить AGP и Gradle проекта, возможны отличия от Code on the Go.
- `404` на `latest/download/update.json`: Release ещё не создан либо репозиторий приватный.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`: подпись/идентификатор пакета отличаются.

## Важно про дизайн и интеграцию

Реальное приложение QR LINK v0.3.8 уже может иметь собственные классы и интерфейс OTA. Сценарий не переписывает существующие классы автоматически, чтобы не ломать передачу файлов и визуальную тему. Для встроенной кнопки в родных настройках и проверки обновлений при запуске нужна адресная модификация оригинальных исходников. Если класс уже встроен, проверь, что он смотрит на новый URL `ANTIFERMENT/QR-LINK`, а не на старый placeholder.

## Доступ к приватному репозиторию

GitHub-репозиторий сейчас PRIVATE. Не помещай GitHub API token в APK! Такой токен может быть извлечён из приложения. Для безопасной анонимной раздачи либо публикуй релизы и исходники в публичном репозитории, либо оставь этот репозиторий приватным и настрой отдельный публичный канал APK/JSON со своей проверкой происхождения артефактов.

Никогда не делай репозиторий публичным, пока не проверил, что внутри исходного ZIP нет секретов, приватных файлов или исходников, которые должны оставаться закрытыми.
