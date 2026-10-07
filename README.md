# Диктовка — плавающий виджет

Мини-приложение для Android: зажми → надиктуй → отпусти → текст уже в буфере обмена.

## Механика

| Состояние | Иконка | Что происходит |
|---|---|---|
| Ожидание | 🎤 | Готов к записи |
| Запись | 🔴 | Пишет аудио пока держишь |
| Обработка | ⏳ | Отправляет на сервер |
| Готово | ✅ | Текст скопирован, через 3 сек → 🎤 |

**Тащить** виджет можно за ту же иконку — просто двигай палец не отпуская.

## Сборка

### 1. Вставь свои значения в `app/build.gradle.kts`

```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://voicebot-iwdm.onrender.com\"")
buildConfigField("String", "APP_SECRET_TOKEN", "\"твой_APP_SECRET_TOKEN\"")
```

Токен берёшь из переменной окружения `APP_SECRET_TOKEN` на Render.

### 2. Собери APK в Android Studio

```
Build → Build Bundle(s) / APK(s) → Build APK(s)
```

Или через командную строку:
```bash
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/`.

### 3. Права при первом запуске

Приложение запросит два разрешения:
- **Микрофон** — стандартный диалог
- **Поверх других окон** — откроет настройки системы, нужно включить вручную

## Архитектура

```
MainActivity.kt          — запрашивает права, запускает сервис и сворачивается
FloatingWidgetService.kt — foreground service, виджет, обработка тачей, состояния
AudioRecorder.kt         — запись в m4a через MediaRecorder
ApiClient.kt             — POST /api/dictate на Render, парсинг JSON
```

## Бэкенд

Использует endpoint `/api/dictate` из iГрамотей:
- `POST multipart/form-data` с полем `file`
- Header `X-App-Token: <token>`
- Ответ: `{"status": "success", "text": "..."}` 

Весь AI (Whisper + Llama) работает на сервере — в APK нет никаких ключей.
