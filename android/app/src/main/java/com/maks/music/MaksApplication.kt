package com.maks.music

import android.app.Application
import android.content.Intent
import com.maks.music.core.AndroidAssetProvider
import com.maks.music.core.memory.AppraisalPolicy
import com.maks.music.core.memory.MusicMemoryDb
import com.maks.music.core.nlu.MusicDecisionEngine
import com.maks.music.core.nlu.MusicEntityExtractor
import com.maks.music.core.nlu.MusicIntentResolver
import com.maks.music.core.nlu.NluContext
import com.maks.music.core.nlu.NluPipeline
import com.maks.music.core.nlu.TextNormalizer
import com.maks.music.core.nlu.VolumeParser
import com.maks.music.core.personality.PersonalityEngine
import com.maks.music.core.router.MusicRouter
import com.maks.music.media.LocalLibraryScanner
import com.maks.music.media.LocalMusicController
import com.maks.music.media.MaksPlaybackService
import com.maks.music.voice.LocalTtsManager
import com.maks.music.voice.OfflineVoiceEngine
import com.maks.music.voice.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MaksBanner(
    val id: Long = System.nanoTime(),
    val text: String,
    val emotion: String = "neutral",
    val isPendingConfirmation: Boolean = false,
)

/**
 * Единый локальный DI-контейнер Макса (100% оффлайн музыкальное ядро).
 */
class MaksApplication : Application() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var memoryDb: MusicMemoryDb
        private set
    lateinit var libraryScanner: LocalLibraryScanner
        private set
    lateinit var personalityEngine: PersonalityEngine
        private set
    lateinit var musicController: LocalMusicController
        private set
    lateinit var ttsManager: LocalTtsManager
        private set
    lateinit var router: MusicRouter
        private set
    lateinit var voiceEngine: OfflineVoiceEngine
        private set

    private val _banner = MutableStateFlow<MaksBanner?>(null)
    val banner: StateFlow<MaksBanner?> = _banner.asStateFlow()

    private val _lastEmotion = MutableStateFlow("neutral")
    val lastEmotion: StateFlow<String> = _lastEmotion.asStateFlow()

    private val _exitAppEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitAppEvents: SharedFlow<Unit> = _exitAppEvents.asSharedFlow()

    private var bannerAutoHideJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val assets = AndroidAssetProvider(applicationContext.assets)

        memoryDb = MusicMemoryDb(this)
        libraryScanner = LocalLibraryScanner(this, assets)
        personalityEngine = PersonalityEngine(assets)
        val appraisal = AppraisalPolicy()

        musicController = LocalMusicController(
            context = this,
            scanner = libraryScanner,
            memoryDb = memoryDb,
            appraisalPolicy = appraisal,
        )

        ttsManager = LocalTtsManager(
            context = this,
            onSpeechStart = { musicController.ducking.duck() },
            onSpeechDone = { musicController.ducking.restore() },
        )

        val volumeParser = VolumeParser(assets)
        val pipeline = NluPipeline(
            normalizer = TextNormalizer(assets),
            intentResolver = MusicIntentResolver(assets, volumeParser),
            entityExtractor = MusicEntityExtractor(assets) { libraryScanner },
            decisionEngine = MusicDecisionEngine(assets),
        )

        val nluContext = NluContext()

        router = MusicRouter(
            pipeline = pipeline,
            volumeParser = volumeParser,
            nluContext = nluContext,
            personality = personalityEngine,
            music = musicController,
            onReply = { userText, reply ->
                if (reply.displayText.isNotBlank()) {
                    _lastEmotion.value = reply.emotion
                    val isPending = userText == null && nluContext.hasPending()
                    showBanner(
                        text = reply.displayText,
                        emotion = reply.emotion,
                        isPendingConfirmation = isPending,
                    )
                    ttsManager.speak(reply)
                }
            }
        )

        voiceEngine = OfflineVoiceEngine(
            context = this,
            isTtsSpeaking = { ttsManager.isSpeaking.value },
            onWakeDetected = {
                mainScope.launch {
                    val wakeReply = personalityEngine.respond("WAKE", fallbackText = "Слушаю")
                    _lastEmotion.value = "curious"
                    showBanner(wakeReply.displayText, "curious", isPendingConfirmation = false)
                    ttsManager.speak(wakeReply)
                }
            },
            onCommandRecognized = { recognizedText, isHotWord ->
                mainScope.launch {
                    if (isShutdownPhrase(recognizedText)) {
                        shutdownApp()
                    } else {
                        router.handleCommand(recognizedText, silentControls = isHotWord)
                    }
                }
            },
            onListeningWindowChanged = { active ->
                mainScope.launch {
                    if (active) musicController.ducking.duck() else musicController.ducking.restore()
                }
            }
        )
        voiceEngine.preloadModelAsync()
    }

    fun submitTextCommand(text: String) {
        if (isShutdownPhrase(text)) {
            shutdownApp()
            return
        }
        router.handleCommand(text, silentControls = false)
    }

    private fun isShutdownPhrase(raw: String): Boolean {
        val clean = raw.lowercase().trim().trimEnd('.', '!', '?')
        return clean in SHUTDOWN_PHRASES
    }

    /**
     * Полное выключение Макса:
     * останавливает музыку, микрофон, оба фоновых сервиса и закрывает задачу приложения.
     */
    fun shutdownApp() {
        bannerAutoHideJob?.cancel()
        _banner.value = null
        runCatching { ttsManager.stop() }
        runCatching { voiceEngine.stop() }
        runCatching { musicController.stop() }
        runCatching { musicController.ducking.forceRestore() }

        runCatching {
            stopService(Intent(this, VoiceForegroundService::class.java))
        }
        runCatching {
            stopService(Intent(this, MaksPlaybackService::class.java))
        }
        _exitAppEvents.tryEmit(Unit)
    }

    fun dismissBanner() {
        bannerAutoHideJob?.cancel()
        _banner.value = null
    }

    private fun showBanner(text: String, emotion: String, isPendingConfirmation: Boolean) {
        bannerAutoHideJob?.cancel()
        _banner.value = MaksBanner(
            text = text,
            emotion = emotion,
            isPendingConfirmation = isPendingConfirmation,
        )
        val timeoutMs = if (isPendingConfirmation) 30_000L else 5_500L
        bannerAutoHideJob = mainScope.launch {
            delay(timeoutMs)
            _banner.value = null
        }
    }

    override fun onTerminate() {
        voiceEngine.release()
        ttsManager.shutdown()
        super.onTerminate()
    }

    companion object {
        private val SHUTDOWN_PHRASES = setOf(
            "выключись", "выключить макса", "выключи макса",
            "закройся", "заверши работу", "спать", "иди спать", "отключись"
        )
    }
}