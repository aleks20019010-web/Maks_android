# Макс Музыка — автономный локальный порт для Android

100% оффлайн нативное Android-приложение (Kotlin + Jetpack Compose + Media3 ExoPlayer + Vosk + встроенный Android TTS), в которое перенесено **только музыкальное ядро Макса**: без Яндекс.Музыки, без токенов, без Yandex SpeechKit, без погоды и управления ПК.

В `AndroidManifest.xml` даже **нет разрешения `INTERNET`** — приложение физически не ходит в сеть.

## Что перенесено

* **Музыкальный NLU (`core/nlu/`):**
  * `TextNormalizer`, `VolumeParser` (цифры и слова), `MusicIntentResolver` (по `config/intent_routes_music.json` + fuzzy), `MusicEntityExtractor` (локальная медиатека, `phon()`, падежи артистов, жанры важнее одноимённых треков, настроения, языковой фильтр «русская/зарубежная», «мои лайки»), `MusicDecisionEngine`, `NluContext`.
* **Ядро личности (`core/personality/`):**
  * Загружает оригинальные `responses/music_*.json`, `appraisal.json`, `wake.json`, `smalltalk.json`.
  * `SpeechTextCleaner` вычищает ударения `+` и преобразует паузы `sil<[150]>` в паузы встроенного Android `TextToSpeech` (`playSilentUtterance`), сохраняя интонацию и ночной стиль Макса.
* **Локальная память и оценка пропусков (`core/memory/`):**
  * `MusicMemoryDb` (SQLite WAL): история прослушиваний, веса артистов, привычки по времени суток (`morning`/`day`/`evening`/`night`), локальные лайки и чёрный список.
  * `AppraisalPolicy`: 3 скипа одного артиста подряд → Макс предлагает убрать артиста из волны (`Да` / `Нет`).
* **Локальный плеер (`media/`):**
  * `LocalLibraryScanner` на базе `MediaStore.Audio.Media` (поддержка MP3/FLAC/M4A/OGG + извлечение обложек).
  * `SmartWaveEngine` — локальная «Моя Волна» с весами, привычками, фильтром языка и защитой от повторов.
  * `MaksPlaybackService` (`Media3 / ExoPlayer` + `MediaSession` для кнопок наушников и Bluetooth), плавный `DuckingController` (100 мс вниз / 1000 мс вверх) и 7 пресетов `EqualizerController`.
* **Оффлайн голос и UI (`voice/`, `ui/`):**
  * Встроенный Android `TextToSpeech` (ru-RU).
  * `OfflineVoiceEngine` + `VoiceForegroundService` на `vosk-android` с аппаратным `AcousticEchoCanceler`: wake-word «Макс», быстрые hot-words без wake-word («дальше», «назад», «пауза», «продолжи», «стоп», «тише», «громче», «лайк», «не нравится») и Push-to-Talk.
  * Jetpack Compose UI с плазменным аватаром (`PlasmaAvatar`), зеркальным спектром, обложками, эквалайзером и чатом.

## Сборка APK прямо на GitHub (без Android Studio)

В репозиторий добавлен workflow `.github/workflows/build-android-apk.yml`:

1. Запушь изменения в ветку `main` (или `master`) — сборка запустится автоматически.
   Либо открой вкладку **Actions → Build Maks Music Android APK → Run workflow** и выбери:
   * `include_vosk_model = true` — автоматически скачает и вошьёт русскую оффлайн-модель `vosk-model-small-ru-0.22` (~40 МБ) прямо внутрь APK.
   * `build_type = debug` (ставится сразу без ключей) или `release`.
2. Дождись окончания прогона (прогоняются юнит-тесты `testDebugUnitTest` + собирается APK).
3. Внизу страницы прогона в блоке **Artifacts** скачай **`maks-music-android-apk`** и установи `.apk` на телефон.
4. При пуше тега вида `v1.0.0` (`git tag v1.0.0 && git push origin v1.0.0`) GitHub автоматически создаст **Release** с прикреплённым `.apk`.
5. *(Опционально для подписи своим релизным ключом)*: добавь в *Settings → Secrets and variables → Actions* секреты `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Если секреты не заданы, APK подписывается стандартным debug-ключом и всё равно ставится на телефон.

## Локальная сборка в Android Studio (опционально)

1. Открой папку `android/` в **Android Studio (Ladybug / 2024.2+)** с JDK 17.
2. Запусти юнит-тесты ядра (проверяют реальные JSON-ассеты Макса):
   ```bash
   gradle testDebugUnitTest
   ```
3. **Для оффлайн-распознавания голоса (Vosk) при локальной сборке:**
   * Скачай и распакуй `vosk-model-small-ru-0.22` с [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) в `android/app/src/main/assets/models/vosk-model-small-ru-0.22/` (либо на телефон в `Android/data/com.maks.music/files/models/vosk-model-small-ru-0.22/`).
4. **Для встроенного русского TTS:** убедись, что на телефоне скачан оффлайн русский пакет голоса (*Настройки → Система → Язык и ввод → Синтез речи*, Google Speech Services или RHVoice).
