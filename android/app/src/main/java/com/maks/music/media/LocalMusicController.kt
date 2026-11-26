package com.maks.music.media

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.maks.music.core.memory.AppraisalPolicy
import com.maks.music.core.memory.MusicMemoryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

data class PlayerUiState(
    val currentTrack: LocalTrack? = null,
    val coverArt: Bitmap? = null,
    val currentLyrics: List<LyricLine> = emptyList(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isCurrentLiked: Boolean = false,
    val modeLabel: String = "Моя волна",
    val waveSettings: WaveSettings = WaveSettings(),
    val libraryCount: Int = 0,
    val allTracks: List<LocalTrack> = emptyList(),
    val likedTrackIds: Set<String> = emptySet(),
    val queue: List<LocalTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val eqPresetIndex: Int = 0,
    val eqEnabled: Boolean = true,
    val eqBandsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val bassBoostEnabled: Boolean = false,
    val stereoBalance: Float = 0f,
    val crossfadeSec: Float = 2.0f,
    val volumePercent: Int = 70,
    val sleepTimerMinutesLeft: Int? = null,
)

class LocalMusicController(
    private val context: Context,
    val scanner: LocalLibraryScanner,
    private val memoryDb: MusicMemoryDb,
    val appraisalPolicy: AppraisalPolicy,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var pendingPlayWhenBound: Boolean = false

    @Volatile
    private var crossfadeOutTriggeredForTrackId: String? = null

    // ── VISUALIZER / MUSIC ENERGY ──
    // audioSessionId от ExoPlayer, Visualizer даёт FFT-энергию по низам.
    // musicEnergy — 0..1, читается UI для аватара/пульсации.
    private var visualizer: android.media.audiofx.Visualizer? = null
    val musicEnergy = MutableStateFlow(0f)

    val equalizer = EqualizerController()
    val waveEngine = SmartWaveEngine(memoryDb)
    val ducking = DuckingController(scope) { vol ->
        runCatching { player?.volume = vol }
    }

    private val _uiState = MutableStateFlow(PlayerUiState(volumePercent = getSystemVolumePercent()))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    var onAppraisalTriggered: ((AppraisalPolicy.AppraisalDecision) -> Unit)? = null
    var onLikeStateChanged: ((Boolean) -> Unit)? = null

    private var queue: List<LocalTrack> = emptyList()
    private var originalQueue: List<LocalTrack> = emptyList()
    private var currentIndex: Int = -1
    private var currentWaveSettings: WaveSettings = WaveSettings()
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onTrackCompletedNaturally()
            }
        }
    }

    private fun ensurePlaybackServiceStarted() {
        runCatching {
            val intent = Intent(context, MaksPlaybackService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    @OptIn(UnstableApi::class)
    fun bindPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
        exoPlayer.addListener(playerListener)
        equalizer.attachToAudioSession(exoPlayer.audioSessionId)

        // ── VISUALIZER: FFT-энергия по низам для аватара ──
        runCatching {
            visualizer?.release()
            visualizer = android.media.audiofx.Visualizer(exoPlayer.audioSessionId).apply {
                captureSize = android.media.audiofx.Visualizer.getCaptureSizeRange()[0] // 128 байт — минимум нагрузки
                setDataCaptureListener(
                    object : android.media.audiofx.Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            waveform: ByteArray?,
                            rate: Int,
                        ) = Unit

                        override fun onFftDataCapture(
                            v: android.media.audiofx.Visualizer?,
                            fft: ByteArray?,
                            rate: Int,
                        ) {
                            if (fft == null || fft.size < 16) return
                            // Берём только низкие и нижне-средние частоты (бас + ритм-секция)
                            var sum = 0f
                            for (i in 2 until 14 step 2) {
                                val re = fft[i].toFloat()
                                val im = fft[i + 1].toFloat()
                                sum += kotlin.math.hypot(re, im)
                            }
                            val normalized = (sum / 420f).coerceIn(0f, 1f)
                            musicEnergy.value = normalized
                        }
                    },
                    android.media.audiofx.Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true,
                )
                enabled = true
            }
        }

        startProgressTicker()
        if (pendingPlayWhenBound && currentTrack() != null) {
            pendingPlayWhenBound = false
            playCurrentIndex(useFadeIn = false)
        }
    }

    fun unbindPlayer() {
        progressJob?.cancel()
        player?.removeListener(playerListener)
        equalizer.release()
        runCatching { visualizer?.release() }
        visualizer = null
        player = null
    }

    suspend fun refreshLibrary(): Int {
        val (scanned, matchedLikedIds) = withContext(Dispatchers.IO) {
            runCatching { memoryDb.seedInitialLikesIfNeeded() }
            val list = scanner.scan()
            val likedIds = runCatching { memoryDb.getLikedTrackIds() }.getOrDefault(emptySet())
            val likedKeys = runCatching { memoryDb.getLikedTrackKeys() }.getOrDefault(emptySet())
            val matched = list
                .filter { track ->
                    track.id in likedIds ||
                        MusicMemoryDb.trackKey(track.artist, track.title) in likedKeys
                }
                .map { it.id }
                .toSet()
            list to matched
        }
        val cur = currentTrack()
        val likedNow = cur?.id in matchedLikedIds
        _uiState.update {
            it.copy(
                libraryCount = scanned.size,
                allTracks = scanned,
                likedTrackIds = matchedLikedIds,
                isCurrentLiked = likedNow,
            )
        }
        onLikeStateChanged?.invoke(likedNow)
        return scanned.size
    }

    suspend fun exportPreferencesJson(): String = withContext(Dispatchers.IO) {
        memoryDb.exportPreferencesJson()
    }

    suspend fun importPreferencesJson(rawJson: String): MusicMemoryDb.ImportResult {
        val result = withContext(Dispatchers.IO) {
            memoryDb.importPreferencesJson(rawJson)
        }
        refreshLibrary()
        return result
    }

    fun setCrossfadeSec(seconds: Float) {
        val clamped = seconds.coerceIn(0f, 3.0f)
        _uiState.update { it.copy(crossfadeSec = clamped) }
    }

    val yandexClient = YandexMusicClient(context)
    private val yandexBridge = YandexQueueBridge(yandexClient)
    @Volatile private var isWaveMode: Boolean = false

    fun setYandexToken(token: String) = yandexClient.setToken(token)
    fun getYandexToken(): String = yandexClient.getToken()
    fun isYandexConfigured(): Boolean = yandexClient.isConfigured()

    private fun triggerWavePrefetchIfNeeded() {
        if (!isWaveMode) return
        yandexBridge.ensureWavePrefetch(
            scope = scope,
            stationId = "user:onyourwave",
            currentQueue = { queue },
            currentIndex = { currentIndex },
            localTracksProvider = { scanner.allTracks() },
            blacklistedIdsProvider = { memoryDb.getBlacklistedTrackIds() },
            onInsertNextTrack = { insertAt, track ->
                scope.launch(Dispatchers.Main) {
                    if (!isWaveMode) return@launch
                    val mutable = queue.toMutableList()
                    val safeIdx = insertAt.coerceIn(0, mutable.size)
                    // ?? ????????? ????????, ???? ???? ???? ??? ? ????????? ???????
                    if (mutable.any { it.id == track.id }) return@launch
                    mutable.add(safeIdx, track)
                    queue = mutable
                    _uiState.update { it.copy(queue = queue) }
                }
            },
        )
    }

    fun playWave(languageFilter: String? = null): Boolean =
        playWave(WaveSettings(languageFilter = languageFilter))

    fun playWave(settings: WaveSettings): Boolean {
        currentWaveSettings = settings
        isWaveMode = true
        yandexBridge.resetWaveSession()
        val list = waveEngine.buildWaveQueue(scanner.allTracks(), settings)
        if (list.isEmpty()) return false
        _uiState.update { it.copy(waveSettings = settings) }
        startQueue(list, settings.displayLabel())
        triggerWavePrefetchIfNeeded()
        return true
    }

    fun playArtist(artist: String): Boolean {
        isWaveMode = false
        yandexBridge.resetWaveSession()
        var list = scanner.findByArtist(artist, memoryDb.getBlacklistedTrackIds())
        if (list.isEmpty() && yandexClient.isConfigured() && !yandexClient.isTemporarilyDown()) {
            list = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    yandexBridge.searchAndDownloadTracks(artist, scanner.allTracks(), limit = 5)
                }.orEmpty()
            }
        }
        if (list.isEmpty()) return false
        startQueue(list, artist)
        return true
    }

    fun playGenre(genre: String): Boolean {
        val list = scanner.findByGenre(genre, memoryDb.getBlacklistedTrackIds())
        if (list.isEmpty()) return false
        startQueue(list, "Жанр · ${genre.removePrefix("genre:")}")
        return true
    }

    fun playMood(mood: String): Boolean {
        val list = scanner.findByMood(mood, memoryDb.getBlacklistedTrackIds())
            .ifEmpty { waveEngine.buildWaveQueue(scanner.allTracks()) }
        if (list.isEmpty()) return false
        startQueue(list, mood)
        return true
    }

    fun playSearch(query: String): LocalTrack? {
        isWaveMode = false
        yandexBridge.resetWaveSession()
        var list = scanner.searchLocal(query, memoryDb.getBlacklistedTrackIds())
        if (list.isEmpty() && yandexClient.isConfigured() && !yandexClient.isTemporarilyDown()) {
            list = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    yandexBridge.searchAndDownloadTracks(query, scanner.allTracks(), limit = 5)
                }.orEmpty()
            }
        }
        if (list.isEmpty()) return null
        startQueue(list, "Поиск · $query")
        return list.first()
    }

    fun playLocalLiked(): Boolean {
        val likedIds = memoryDb.getLikedTrackIds()
        val likedKeys = memoryDb.getLikedTrackKeys()
        if (likedIds.isEmpty() && likedKeys.isEmpty()) return false
        val list = scanner.allTracks()
            .filter { track ->
                track.id in likedIds ||
                    MusicMemoryDb.trackKey(track.artist, track.title) in likedKeys
            }
            .shuffled()
        if (list.isEmpty()) return false
        startQueue(list, "Мои лайки")
        return true
    }

    fun playFromLibrary(tracks: List<LocalTrack>, startIndex: Int, label: String = "Медиатека") {
        if (tracks.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        originalQueue = tracks
        if (_uiState.value.isShuffle) {
            val chosen = tracks[safeIdx]
            val rest = tracks.filterIndexed { i, _ -> i != safeIdx }.shuffled()
            queue = listOf(chosen) + rest
            currentIndex = 0
        } else {
            queue = tracks
            currentIndex = safeIdx
        }
        _uiState.update {
            it.copy(
                modeLabel = label,
                queue = queue,
                currentIndex = currentIndex,
            )
        }
        playCurrentIndex(useFadeIn = false)
    }

    fun playQueueIndex(index: Int) {
        if (index !in queue.indices) return
        currentIndex = index
        _uiState.update { it.copy(currentIndex = currentIndex) }
        playCurrentIndex(useFadeIn = false)
    }

    fun toggleShuffle() {
        val nextShuffle = !_uiState.value.isShuffle
        val cur = currentTrack()
        if (queue.isNotEmpty() && cur != null) {
            if (nextShuffle) {
                originalQueue = queue
                val rest = queue.filterIndexed { idx, _ -> idx != currentIndex }.shuffled()
                queue = listOf(cur) + rest
                currentIndex = 0
            } else if (originalQueue.isNotEmpty()) {
                val restoredIdx = originalQueue.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
                queue = originalQueue
                currentIndex = restoredIdx
            }
        }
        _uiState.update {
            it.copy(
                isShuffle = nextShuffle,
                queue = queue,
                currentIndex = currentIndex,
            )
        }
    }

    fun cycleRepeatMode() {
        val nextMode = when (_uiState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _uiState.update { it.copy(repeatMode = nextMode) }
    }

    fun setSleepTimer(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _uiState.update { it.copy(sleepTimerMinutesLeft = null) }
            return
        }
        _uiState.update { it.copy(sleepTimerMinutesLeft = minutes) }
        sleepTimerJob = scope.launch {
            var remaining = minutes
            while (remaining > 0 && isActive) {
                delay(60_000L)
                remaining--
                _uiState.update { it.copy(sleepTimerMinutesLeft = remaining.takeIf { r -> r > 0 }) }
            }
            pause()
        }
    }

    fun next(isUserSkip: Boolean = true): LocalTrack? {
        val current = currentTrack()
        val p = player
        if (isUserSkip && current != null && p != null) {
            val secPlayed = (p.currentPosition / 1000L).toInt()
            val durSec = (current.durationMs / 1000L).toInt()
            if (durSec > 0 && secPlayed < (durSec * 0.7)) {
                scope.launch(Dispatchers.IO) {
                    memoryDb.recordSkip(current.id, current.artist, current.title, secPlayed)
                }
                val appraisal = appraisalPolicy.onTrackSkipped(current.artist)
                if (appraisal.shouldReact) {
                    onAppraisalTriggered?.invoke(appraisal)
                }
            }
        }

        val shouldFadeIn = !isUserSkip && _uiState.value.crossfadeSec > 0f

        if (queue.isEmpty()) {
            return if (playWave(currentWaveSettings)) currentTrack() else null
        }

        if (!isUserSkip && _uiState.value.repeatMode == RepeatMode.ONE) {
            playCurrentIndex(useFadeIn = shouldFadeIn)
            return currentTrack()
        }

        if (currentIndex + 1 < queue.size) {
            currentIndex++
            _uiState.update { it.copy(currentIndex = currentIndex) }
            playCurrentIndex(useFadeIn = shouldFadeIn)
            return currentTrack()
        }

        if (_uiState.value.repeatMode == RepeatMode.ALL) {
            currentIndex = 0
            _uiState.update { it.copy(currentIndex = currentIndex) }
            playCurrentIndex(useFadeIn = shouldFadeIn)
            return currentTrack()
        }

        val nextWave = waveEngine.buildWaveQueue(scanner.allTracks(), currentWaveSettings)
        if (nextWave.isEmpty()) return null
        startQueue(nextWave, _uiState.value.modeLabel, useFadeIn = shouldFadeIn)
        return currentTrack()
    }

    fun prev(): LocalTrack? {
        val p = player
        if (p != null && p.currentPosition > 4_000L) {
            seekTo(0L)
            return currentTrack()
        }
        if (queue.isEmpty()) return null
        currentIndex = if (currentIndex > 0) currentIndex - 1 else queue.lastIndex
        _uiState.update { it.copy(currentIndex = currentIndex) }
        playCurrentIndex(useFadeIn = false)
        return currentTrack()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        val p = player
        if (currentTrack() == null) {
            playWave()
        } else if (p != null) {
            ducking.setCrossfadeImmediate(1.0f)
            p.play()
        } else {
            pendingPlayWhenBound = true
            ensurePlaybackServiceStarted()
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) pause() else resume()
    }

    fun stop() {
        player?.stop()
        ducking.setCrossfadeImmediate(1.0f)
        _uiState.update { it.copy(isPlaying = false, positionMs = 0L) }
    }

    fun seekTo(positionMs: Long) {
        crossfadeOutTriggeredForTrackId = null
        ducking.setCrossfadeImmediate(1.0f)
        player?.seekTo(positionMs)
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    fun likeCurrent(): LocalTrack? {
        val track = currentTrack() ?: return null
        appraisalPolicy.onTrackFinishedOrLiked(track.artist)
        scope.launch(Dispatchers.IO) {
            memoryDb.addLike(track.id, track.artist, track.title)
        }
        _uiState.update {
            it.copy(
                isCurrentLiked = true,
                likedTrackIds = it.likedTrackIds + track.id,
            )
        }
        onLikeStateChanged?.invoke(true)
        return track
    }

    fun toggleLikeTrack(track: LocalTrack): Boolean {
        val nowLiked = track.id !in _uiState.value.likedTrackIds
        if (nowLiked) {
            appraisalPolicy.onTrackFinishedOrLiked(track.artist)
        }
        scope.launch(Dispatchers.IO) {
            if (nowLiked) memoryDb.addLike(track.id, track.artist, track.title)
            else memoryDb.removeLike(track.artist, track.title)
        }
        val isCurrent = _uiState.value.currentTrack?.id == track.id
        _uiState.update { state ->
            val updatedSet = if (nowLiked) state.likedTrackIds + track.id else state.likedTrackIds - track.id
            state.copy(
                likedTrackIds = updatedSet,
                isCurrentLiked = if (isCurrent) nowLiked else state.isCurrentLiked,
            )
        }
        if (isCurrent) {
            onLikeStateChanged?.invoke(nowLiked)
        }
        return nowLiked
    }

    fun toggleLikeCurrent(): Boolean {
        val track = currentTrack() ?: return false
        return toggleLikeTrack(track)
    }

    fun dislikeCurrent(): LocalTrack? {
        val track = currentTrack() ?: return null
        appraisalPolicy.onTrackDisliked(track.artist)
        scope.launch(Dispatchers.IO) {
            memoryDb.blacklistTrack(track.id, track.artist, track.title)
        }
        _uiState.update {
            it.copy(
                isCurrentLiked = false,
                likedTrackIds = it.likedTrackIds - track.id,
            )
        }
        onLikeStateChanged?.invoke(false)
        next(isUserSkip = false)
        return track
    }

    fun blacklistArtist(artist: String) {
        scope.launch(Dispatchers.IO) {
            memoryDb.blacklistArtist(artist)
        }
        if (currentTrack()?.artist.equals(artist, ignoreCase = true)) {
            next(isUserSkip = false)
        }
    }

    fun volumeUp(): Int = stepSystemVolume(+1)
    fun volumeDown(): Int = stepSystemVolume(-1)

    private fun stepSystemVolume(direction: Int): Int {
        val am = audioManager
            ?: return setSystemVolumePercent((getSystemVolumePercent() + direction * 7).coerceIn(0, 100))
        val newPercent = runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = (max / 15).coerceAtLeast(1)
            val target = (cur + direction * step).coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            ((target * 100f) / max).toInt().coerceIn(0, 100)
        }.getOrElse {
            (getSystemVolumePercent() + direction * 7).coerceIn(0, 100)
        }
        _uiState.update { it.copy(volumePercent = newPercent) }
        return newPercent
    }

    fun setSystemVolumePercent(percent: Int): Int {
        val clamped = percent.coerceIn(0, 100)
        runCatching {
            val am = audioManager ?: return@runCatching
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((clamped / 100f) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        _uiState.update { it.copy(volumePercent = clamped) }
        return clamped
    }

    fun getSystemVolumePercent(): Int = runCatching {
        val am = audioManager ?: return@runCatching 70
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }.getOrDefault(70)

    fun selectEqPreset(index: Int) {
        equalizer.applyPreset(index)
        _uiState.update {
            it.copy(
                eqPresetIndex = equalizer.currentPresetIndex,
                eqBandsDb = equalizer.currentBandsDb,
            )
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        equalizer.setEqEnabled(enabled)
        _uiState.update { it.copy(eqEnabled = equalizer.eqEnabled) }
    }

    fun setEqBandDb(bandIndex: Int, db: Float) {
        equalizer.setBandGainDb(bandIndex, db)
        _uiState.update {
            it.copy(
                eqPresetIndex = equalizer.currentPresetIndex,
                eqBandsDb = equalizer.currentBandsDb,
            )
        }
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        equalizer.setBassBoostEnabled(enabled)
        _uiState.update { it.copy(bassBoostEnabled = equalizer.bassBoostEnabled) }
    }

    fun setStereoBalance(balance: Float) {
        equalizer.setStereoBalance(balance)
        _uiState.update { it.copy(stereoBalance = equalizer.stereoBalance) }
    }

    fun currentTrack(): LocalTrack? = queue.getOrNull(currentIndex)

    private fun startQueue(newQueue: List<LocalTrack>, modeLabel: String, useFadeIn: Boolean = false) {
        originalQueue = newQueue
        queue = if (_uiState.value.isShuffle) newQueue.shuffled() else newQueue
        currentIndex = 0
        _uiState.update {
            it.copy(
                modeLabel = modeLabel,
                queue = queue,
                currentIndex = currentIndex,
            )
        }
        playCurrentIndex(useFadeIn = useFadeIn)
    }

    private fun buildMediaItem(track: LocalTrack, artworkBytes: ByteArray?): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else if (track.albumId > 0L) {
            val albumArtUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                track.albumId,
            )
            metadataBuilder.setArtworkUri(albumArtUri)
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.contentUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap): ByteArray? = runCatching {
        val maxSide = 512
        val scaled = if (bitmap.width > maxSide || bitmap.height > maxSide) {
            val ratio = minOf(maxSide.toFloat() / bitmap.width, maxSide.toFloat() / bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }.getOrNull()

    private fun playCurrentIndex(useFadeIn: Boolean = false) {
        val track = currentTrack() ?: return
        crossfadeOutTriggeredForTrackId = null

        val p = player
        if (p == null) {
            pendingPlayWhenBound = true
            ensurePlaybackServiceStarted()
            val initialLiked = track.id in _uiState.value.likedTrackIds
            _uiState.update {
                it.copy(
                    currentTrack = track,
                    coverArt = null,
                    currentLyrics = emptyList(),
                    durationMs = track.durationMs,
                    positionMs = 0L,
                    isCurrentLiked = initialLiked,
                )
            }
            onLikeStateChanged?.invoke(initialLiked)
            return
        }

        waveEngine.markPlayed(track.id)
        triggerWavePrefetchIfNeeded()

        val crossfadeMs = (_uiState.value.crossfadeSec * 1000f).toLong()
        if (useFadeIn && crossfadeMs > 0L) {
            ducking.setCrossfadeImmediate(0.02f)
        } else {
            ducking.setCrossfadeImmediate(1.0f)
        }

        val initialItem = buildMediaItem(track, artworkBytes = null)
        p.setMediaItem(initialItem)
        p.prepare()
        p.play()

        if (useFadeIn && crossfadeMs > 0L) {
            ducking.fadeCrossfade(1.0f, crossfadeMs)
        }

        val initialLiked = track.id in _uiState.value.likedTrackIds
        _uiState.update {
            it.copy(
                currentTrack = track,
                coverArt = null,
                currentLyrics = emptyList(),
                durationMs = track.durationMs,
                positionMs = 0L,
                isCurrentLiked = initialLiked,
            )
        }
        onLikeStateChanged?.invoke(initialLiked)

        scope.launch(Dispatchers.IO) {
            runCatching { memoryDb.recordPlay(track.id, track.artist, track.title) }
            val liked = runCatching { memoryDb.isLiked(track.artist, track.title) }.getOrDefault(false)
            val cover = scanner.loadCoverArt(track)
            val artBytes = cover?.let { bitmapToJpegBytes(it) }
            val lyrics = runCatching { LyricsExtractor.extract(context, track.contentUri) }.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                if (currentTrack()?.id == track.id) {
                    _uiState.update {
                        it.copy(
                            coverArt = cover,
                            currentLyrics = lyrics,
                            isCurrentLiked = liked,
                        )
                    }
                    onLikeStateChanged?.invoke(liked)

                    if (artBytes != null && p.mediaItemCount > 0) {
                        val updatedItem = buildMediaItem(track, artworkBytes = artBytes)
                        p.replaceMediaItem(0, updatedItem)
                    }
                }
            }
        }
    }

    private fun onTrackCompletedNaturally() {
        val track = currentTrack()
        if (track != null) {
            appraisalPolicy.onTrackFinishedOrLiked(track.artist)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    memoryDb.recordFinish(track.id, track.artist, track.title, (track.durationMs / 1000L).toInt())
                }
            }
        }
        next(isUserSkip = false)
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { d -> d > 0 } ?: _uiState.value.durationMs
                    _uiState.update {
                        it.copy(
                            positionMs = pos,
                            durationMs = dur,
                        )
                    }

                    val crossfadeMs = (_uiState.value.crossfadeSec * 1000f).toLong()
                    val curTrack = currentTrack()
                    if (crossfadeMs > 0L && curTrack != null && dur > crossfadeMs * 2L + 4_000L) {
                        val remaining = dur - pos
                        if (remaining in 300L..crossfadeMs && crossfadeOutTriggeredForTrackId != curTrack.id) {
                            crossfadeOutTriggeredForTrackId = curTrack.id
                            ducking.fadeCrossfade(0.02f, remaining)
                        }
                    }
                }
                delay(150L)
            }
        }
    }
}