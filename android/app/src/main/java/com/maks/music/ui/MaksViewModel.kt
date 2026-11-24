package com.maks.music.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maks.music.MaksApplication
import com.maks.music.MaksBanner
import com.maks.music.media.LocalTrack
import com.maks.music.media.PlayerUiState
import com.maks.music.voice.VoiceEngineState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MaksViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MaksApplication

    val playerState: StateFlow<PlayerUiState> = app.musicController.uiState
    val banner: StateFlow<MaksBanner?> = app.banner
    val voiceState: StateFlow<VoiceEngineState> = app.voiceEngine.state
    val micLevel: StateFlow<Float> = app.voiceEngine.micLevel
    val isTtsSpeaking: StateFlow<Boolean> = app.ttsManager.isSpeaking
    val lastEmotion: StateFlow<String> = app.lastEmotion

    fun refreshLibrary() {
        viewModelScope.launch {
            app.musicController.refreshLibrary()
        }
    }

    fun addMusicFolder(treeUri: Uri) {
        app.libraryScanner.addMusicFolder(treeUri)
        refreshLibrary()
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
    fun setVolumePercent(pct: Int) = app.musicController.setSystemVolumePercent(pct)
    fun setSleepTimer(minutes: Int?) = app.musicController.setSleepTimer(minutes)

    fun playWave(lang: String? = null) = app.musicController.playWave(lang)
    fun playLiked() = app.musicController.playLocalLiked()
    fun playArtist(artist: String) = app.musicController.playArtist(artist)
    fun playFromLibrary(tracks: List<LocalTrack>, index: Int, label: String = "Медиатека") =
        app.musicController.playFromLibrary(tracks, index, label)
    fun playQueueIndex(index: Int) = app.musicController.playQueueIndex(index)

    fun triggerPushToTalk() = app.voiceEngine.triggerPushToTalk()
}