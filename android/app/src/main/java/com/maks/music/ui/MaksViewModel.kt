package com.maks.music.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maks.music.MaksApplication
import com.maks.music.MaksBanner
import com.maks.music.core.MaksSettingsState
import com.maks.music.core.memory.MusicMemoryDb
import com.maks.music.media.LocalTrack
import com.maks.music.media.PlayerUiState
import com.maks.music.media.WaveSettings
import com.maks.music.voice.VoiceEngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MaksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MaksApplication

    val playerState: StateFlow<PlayerUiState> = app.musicController.uiState
    val settingsState: StateFlow<MaksSettingsState> = app.settingsStore.state
    val banner: StateFlow<MaksBanner?> = app.banner
    val voiceState: StateFlow<VoiceEngineState> = app.voiceEngine.state
    val micLevel: StateFlow<Float> = app.voiceEngine.micLevel
    val isTtsSpeaking: StateFlow<Boolean> = app.ttsManager.isSpeaking
    val lastEmotion: StateFlow<String> = app.lastEmotion
    // ── VISUALIZER / MUSIC ENERGY ──
    // FFT-энергия по низам (0..1) от LocalMusicController.visualizer.
    // UI читает и пульсирует аватаром/спектром под бит.
    val musicEnergy: StateFlow<Float> = app.musicController.musicEnergy

    fun refreshLibrary() {
        viewModelScope.launch {
            app.musicController.refreshLibrary()
        }
    }

    fun addMusicFolder(treeUri: Uri) {
        app.libraryScanner.addMusicFolder(treeUri)
        refreshLibrary()
    }

    suspend fun loadTrackThumbnail(track: LocalTrack): Bitmap? = withContext(Dispatchers.IO) {
        app.libraryScanner.loadCachedThumbnail(track)
    }

    fun submitCommand(text: String) = app.submitTextCommand(text)
    fun dismissBanner() = app.dismissBanner()
    fun shutdownApp() = app.shutdownApp()

    fun togglePlayPause() = app.musicController.togglePlayPause()
    fun nextTrack() = app.musicController.next(isUserSkip = true)
    fun prevTrack() = app.musicController.prev()
    fun seekTo(ms: Long) = app.musicController.seekTo(ms)
    fun toggleLike() = app.musicController.toggleLikeCurrent()
    fun toggleLikeTrack(track: LocalTrack) = app.musicController.toggleLikeTrack(track)
    fun dislike() = app.musicController.dislikeCurrent()
    fun toggleShuffle() = app.musicController.toggleShuffle()
    fun cycleRepeatMode() = app.musicController.cycleRepeatMode()
    fun selectEqPreset(index: Int) = app.musicController.selectEqPreset(index)
    fun setEqEnabled(enabled: Boolean) = app.musicController.setEqEnabled(enabled)
    fun setEqBandDb(bandIndex: Int, db: Float) = app.musicController.setEqBandDb(bandIndex, db)
    fun setBassBoostEnabled(enabled: Boolean) = app.musicController.setBassBoostEnabled(enabled)
    fun setStereoBalance(balance: Float) = app.musicController.setStereoBalance(balance)
    fun setCrossfadeSec(seconds: Float) = app.musicController.setCrossfadeSec(seconds)
    fun setVolumePercent(pct: Int) = app.musicController.setSystemVolumePercent(pct)
    fun setSleepTimer(minutes: Int?) = app.musicController.setSleepTimer(minutes)

    fun exportPreferencesToUri(uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val json = app.musicController.exportPreferencesJson()
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось открыть файл для записи")
                }
            }.isSuccess
            val msg = if (ok) "Лайки и дизлайки сохранены в JSON" else "Ошибка экспорта JSON"
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun importPreferencesFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: error("Не удалось прочитать файл")
                }
                app.musicController.importPreferencesJson(raw)
            }.getOrNull()

            val msg = if (result != null) {
                "Импорт: +${result.likedAdded} лайков, +${result.dislikedTracksAdded} дизлайков"
            } else {
                "Ошибка чтения JSON"
            }
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun playWave(lang: String? = null) = app.musicController.playWave(lang)
    fun playWaveWithSettings(settings: WaveSettings) = app.musicController.playWave(settings)
    fun playLiked() = app.musicController.playLocalLiked()
    fun playArtist(artist: String) = app.musicController.playArtist(artist)
    fun playFromLibrary(tracks: List<LocalTrack>, index: Int, label: String = "Медиатека") =
        app.musicController.playFromLibrary(tracks, index, label)
    fun playQueueIndex(index: Int) = app.musicController.playQueueIndex(index)


    fun setYandexToken(token: String) {
        app.settingsStore.setYandexToken(token)
        app.musicController.setYandexToken(token)
    }

    suspend fun testYandexConnection(): Result<String> = app.musicController.yandexClient.testConnection()
    fun getYandexCacheStats(): Pair<Int, Long> = app.musicController.yandexClient.getCacheStats()
    fun clearYandexCache(): Int = app.musicController.yandexClient.clearCache()
    fun isYandexTemporarilyDown(): Boolean = app.musicController.yandexClient.isTemporarilyDown()
    fun resetYandexCircuitBreaker() = app.musicController.yandexClient.resetCircuitBreaker()

    fun setYandexPrefetchCount(count: Int) = app.settingsStore.setYandexPrefetchCount(count)
    fun setYandexWifiOnly(enabled: Boolean) = app.settingsStore.setYandexWifiOnly(enabled)
    fun setYandexCacheLimitMb(limitMb: Int) {
        app.settingsStore.setYandexCacheLimitMb(limitMb)
        app.musicController.yandexClient.enforceCacheLimit()
    }
    fun setYandexHighQuality(high: Boolean) = app.settingsStore.setYandexHighQuality(high)

    fun setHotwordsEnabled(enabled: Boolean) = app.settingsStore.setHotwordsEnabled(enabled)
    fun setPauseListeningOnHeadset(enabled: Boolean) = app.settingsStore.setPauseListeningOnHeadset(enabled)
    fun setSilentControls(enabled: Boolean) = app.settingsStore.setSilentControls(enabled)
    fun setTtsSpeechRate(rate: Float) = app.settingsStore.setTtsSpeechRate(rate)
    fun setDuckLevelPercent(percent: Int) = app.settingsStore.setDuckLevelPercent(percent)

    fun setIgnoreShortTracksSec(sec: Int) {
        app.settingsStore.setIgnoreShortTracksSec(sec)
        refreshLibrary()
    }

    fun setDefaultWaveLanguage(lang: String?) = app.settingsStore.setDefaultWaveLanguage(lang)
    fun setResumeOnBluetooth(enabled: Boolean) = app.settingsStore.setResumeOnBluetooth(enabled)

    fun getBlacklistedEntries(): List<MusicMemoryDb.BlacklistedEntry> = app.memoryDb.getBlacklistedEntries()
    fun unblacklistEntry(entry: MusicMemoryDb.BlacklistedEntry) {
        if (entry.isArtist) app.memoryDb.unblacklistArtist(entry.id)
        else app.memoryDb.unblacklistTrack(entry.id)
    }

    fun triggerPushToTalk() = app.voiceEngine.triggerPushToTalk()

    fun setAgcEnabled(enabled: Boolean) {
        app.settingsStore.setAgcEnabled(enabled)
        val gain = app.settingsStore.state.value.micGainMultiplier
        runCatching {
            val eng = app.voiceEngine
            val m = eng.javaClass.methods.firstOrNull { it.name == "applyMicGainSettings" }
            m?.invoke(eng, enabled, gain)
        }
    }

    fun setMicGainMultiplier(multiplier: Float) {
        val clamped = multiplier.coerceIn(0.5f, 3.0f)
        app.settingsStore.setMicGainMultiplier(clamped)
        val agc = app.settingsStore.state.value.agcEnabled
        runCatching {
            val eng = app.voiceEngine
            val m = eng.javaClass.methods.firstOrNull { it.name == "applyMicGainSettings" }
            m?.invoke(eng, agc, clamped)
        }
    }

    fun setYandexDownloadDirUri(uri: String) {
        app.settingsStore.setYandexDownloadDirUri(uri)
    }
}
