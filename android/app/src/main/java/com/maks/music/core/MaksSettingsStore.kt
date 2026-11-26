package com.maks.music.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MaksSettingsState(
    val yandexToken: String = "",
    val yandexPrefetchCount: Int = 5,
    val yandexWifiOnly: Boolean = false,
    val yandexCacheLimitMb: Int = 0,
    val yandexDownloadDirUri: String = "",
    val yandexHighQuality: Boolean = true,
    val hotwordsEnabled: Boolean = true,
    val agcEnabled: Boolean = true,
    val micGainMultiplier: Float = 1.0f,
    val pauseListeningOnHeadset: Boolean = false,
    val silentControls: Boolean = false,
    val ttsSpeechRate: Float = 1.0f,
    val duckLevelPercent: Int = 15,
    val ignoreShortTracksSec: Int = 30,
    val defaultWaveLanguage: String? = null,
    val resumeOnBluetooth: Boolean = false,
)

class MaksSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val yandexLegacyPrefs = appContext.getSharedPreferences("yandex_music_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<MaksSettingsState> = _state.asStateFlow()

    private fun loadInitial(): MaksSettingsState {
        val token = prefs.getString(KEY_YA_TOKEN, null)
            ?: yandexLegacyPrefs.getString("oauth_token", "").orEmpty()
        return MaksSettingsState(
            yandexToken = token.trim(),
            yandexPrefetchCount = prefs.getInt(KEY_YA_PREFETCH, 5).coerceIn(1, 15),
            yandexWifiOnly = prefs.getBoolean(KEY_YA_WIFI_ONLY, false),
            yandexCacheLimitMb = prefs.getInt(KEY_YA_CACHE_LIMIT_MB, 0),
            yandexDownloadDirUri = prefs.getString(KEY_YA_DOWNLOAD_DIR, "").orEmpty(),
            yandexHighQuality = prefs.getBoolean(KEY_YA_HIGH_QUALITY, true),
            hotwordsEnabled = prefs.getBoolean(KEY_HOTWORDS, true),
            pauseListeningOnHeadset = prefs.getBoolean(KEY_PAUSE_HEADSET, false),
            silentControls = prefs.getBoolean(KEY_SILENT_CONTROLS, false),
            ttsSpeechRate = prefs.getFloat(KEY_TTS_RATE, 1.0f).coerceIn(0.75f, 1.35f),
            duckLevelPercent = prefs.getInt(KEY_DUCK_PERCENT, 15).coerceIn(5, 60),
            ignoreShortTracksSec = prefs.getInt(KEY_IGNORE_SHORT_SEC, 30),
            defaultWaveLanguage = prefs.getString(KEY_WAVE_LANG, null),
            resumeOnBluetooth = prefs.getBoolean(KEY_RESUME_BT, false),
        )
    }

    fun setYandexToken(token: String) {
        val clean = token.trim()
        prefs.edit().putString(KEY_YA_TOKEN, clean).apply()
        yandexLegacyPrefs.edit().putString("oauth_token", clean).apply()
        _state.update { it.copy(yandexToken = clean) }
    }

    fun setYandexPrefetchCount(count: Int) {
        val clamped = count.coerceIn(1, 15)
        prefs.edit().putInt(KEY_YA_PREFETCH, clamped).apply()
        _state.update { it.copy(yandexPrefetchCount = clamped) }
    }

    fun setYandexWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_YA_WIFI_ONLY, enabled).apply()
        _state.update { it.copy(yandexWifiOnly = enabled) }
    }

    fun setYandexCacheLimitMb(limitMb: Int) {
        prefs.edit().putInt(KEY_YA_CACHE_LIMIT_MB, limitMb).apply()
        _state.update { it.copy(yandexCacheLimitMb = limitMb) }
    }

    fun setYandexHighQuality(high: Boolean) {
        prefs.edit().putBoolean(KEY_YA_HIGH_QUALITY, high).apply()
        _state.update { it.copy(yandexHighQuality = high) }
    }

    fun setHotwordsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HOTWORDS, enabled).apply()
        _state.update { it.copy(hotwordsEnabled = enabled) }
    }

    fun setPauseListeningOnHeadset(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_HEADSET, enabled).apply()
        _state.update { it.copy(pauseListeningOnHeadset = enabled) }
    }

    fun setSilentControls(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SILENT_CONTROLS, enabled).apply()
        _state.update { it.copy(silentControls = enabled) }
    }

    fun setTtsSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.75f, 1.35f)
        prefs.edit().putFloat(KEY_TTS_RATE, clamped).apply()
        _state.update { it.copy(ttsSpeechRate = clamped) }
    }

    fun setDuckLevelPercent(percent: Int) {
        val clamped = percent.coerceIn(5, 60)
        prefs.edit().putInt(KEY_DUCK_PERCENT, clamped).apply()
        _state.update { it.copy(duckLevelPercent = clamped) }
    }

    fun setIgnoreShortTracksSec(sec: Int) {
        prefs.edit().putInt(KEY_IGNORE_SHORT_SEC, sec).apply()
        _state.update { it.copy(ignoreShortTracksSec = sec) }
    }

    fun setDefaultWaveLanguage(lang: String?) {
        prefs.edit().putString(KEY_WAVE_LANG, lang).apply()
        _state.update { it.copy(defaultWaveLanguage = lang) }
    }

    fun setResumeOnBluetooth(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_BT, enabled).apply()
        _state.update { it.copy(resumeOnBluetooth = enabled) }
    }

    companion object {
        const val PREFS_NAME = "maks_settings"
        private const val KEY_YA_TOKEN = "yandex_token"
        private const val KEY_YA_PREFETCH = "yandex_prefetch_count"
        private const val KEY_YA_WIFI_ONLY = "yandex_wifi_only"
        private const val KEY_YA_CACHE_LIMIT_MB = "yandex_cache_limit_mb"
        private const val KEY_YA_DOWNLOAD_DIR = "yandex_download_dir_uri"
        private const val KEY_YA_HIGH_QUALITY = "yandex_high_quality"
        private const val KEY_HOTWORDS = "hotwords_enabled"
        private const val KEY_PAUSE_HEADSET = "pause_listening_on_headset"
        private const val KEY_SILENT_CONTROLS = "silent_controls"
        private const val KEY_TTS_RATE = "tts_speech_rate"
        private const val KEY_DUCK_PERCENT = "duck_level_percent"
        private const val KEY_IGNORE_SHORT_SEC = "ignore_short_tracks_sec"
        private const val KEY_WAVE_LANG = "default_wave_language"
        private const val KEY_RESUME_BT = "resume_on_bluetooth"
    }
}
