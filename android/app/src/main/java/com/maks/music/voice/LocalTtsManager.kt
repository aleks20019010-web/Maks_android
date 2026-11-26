package com.maks.music.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.maks.music.core.SpeechTextCleaner
import com.maks.music.core.personality.MaksReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 100% ?????? ???????????? ?????? ???? ?? Sherpa-ONNX (Piper ru_RU-ruslan-medium).
 * ???????????? ????? sil<[ms]> ?? MaksReply.speechChunks, ????????? ?????????? ?????? (duck/restore)
 * ? ?????? ?????????? Android TTS ??? ????????? ??????.
 */
class LocalTtsManager(
    context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit,
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ttsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "maks-sherpa-tts").apply { isDaemon = true }
    }

    @Volatile
    private var sherpaTts: OfflineTts? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    private val speechToken = AtomicLong(0L)

    // Fallback Android TTS (used while Sherpa-ONNX is loading or if generation fails)
    private var fallbackTts: TextToSpeech? = runCatching {
        TextToSpeech(appContext, this)
    }.getOrNull()

    @Volatile
    private var isFallbackReady: Boolean = false

    @Volatile
    private var lastUtteranceId: String? = null

    @Volatile
    private var duckActiveForSpeech: Boolean = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        ttsExecutor.execute {
            initSherpaTts()
        }
    }

    private fun copyAssetDirIfNeeded(assetSubPath: String): String {
        val targetDir = File(appContext.filesDir, assetSubPath)
        val marker = File(targetDir, ".ready_full")
        if (marker.exists()) {
            return targetDir.absolutePath
        }
        copyAssetRecursive(assetSubPath, targetDir)
        marker.writeText("ok")
        return targetDir.absolutePath
    }

    private fun copyAssetRecursive(assetPath: String, outFile: File) {
        val assets = appContext.assets
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            outFile.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            outFile.mkdirs()
            for (child in children) {
                copyAssetRecursive("$assetPath/$child", File(outFile, child))
            }
        }
    }

    private fun initSherpaTts() {
        try {
            val modelDirName = "vits-piper-ru_RU-ruslan-medium"
            val modelDirPath = copyAssetDirIfNeeded(modelDirName)
            Log.i(TAG, "Sherpa-ONNX model dir ready at: $modelDirPath")

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = "$modelDirPath/ru_RU-ruslan-medium.onnx",
                tokens = "$modelDirPath/tokens.txt",
                dataDir = "$modelDirPath/espeak-ng-data",
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            )
            val config = OfflineTtsConfig(model = modelConfig)
            sherpaTts = OfflineTts(assetManager = null, config = config)
            Log.i(TAG, "Sherpa-ONNX TTS (ruslan) initialized successfully from disk")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Sherpa-ONNX TTS, will use Android TTS fallback", e)
            sherpaTts = null
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = fallbackTts ?: return
        engine.language = Locale("ru", "RU")
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                notifySpeechStarted()
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastUtteranceId) {
                    notifySpeechFinished()
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == lastUtteranceId) {
                    notifySpeechFinished()
                }
            }

            @Deprecated("Deprecated in Android API")
            override fun onError(utteranceId: String?) {
                notifySpeechFinished()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                notifySpeechFinished()
            }
        })
        isFallbackReady = true
    }

    private fun notifySpeechStarted() {
        mainHandler.post {
            _isSpeaking.value = true
            if (!duckActiveForSpeech) {
                duckActiveForSpeech = true
                onSpeechStart()
            }
        }
    }

    private fun notifySpeechFinished() {
        mainHandler.post {
            _isSpeaking.value = false
            if (duckActiveForSpeech) {
                duckActiveForSpeech = false
                onSpeechDone()
            }
        }
    }

    @Volatile var userSpeechRateMultiplier: Float = 1.0f

    fun speak(reply: MaksReply) {
        val chunks = reply.speechChunks.ifEmpty {
            val clean = reply.displayText.trim()
            if (clean.isEmpty()) emptyList() else listOf(SpeechTextCleaner.SpeechChunk(clean, 0L))
        }
        if (chunks.isEmpty()) return

        val token = speechToken.incrementAndGet()
        stopActiveAudio()

        val engine = sherpaTts
        if (engine != null) {
            val speed = (reply.speechRate * userSpeechRateMultiplier).coerceIn(0.75f, 1.5f)
            ttsExecutor.execute {
                speakWithSherpa(engine, reply, chunks, speed, token)
            }
        } else {
            speakWithFallback(reply)
        }
    }

    private fun speakWithSherpa(
        engine: OfflineTts,
        reply: MaksReply,
        chunks: List<SpeechTextCleaner.SpeechChunk>,
        speed: Float,
        token: Long,
    ) {
        if (speechToken.get() != token) return
        notifySpeechStarted()

        var track: AudioTrack? = null
        var producedAudio = false
        try {
            for (chunk in chunks) {
                if (speechToken.get() != token) break
                val text = chunk.text.trim()
                if (text.isEmpty()) continue

                val audio = engine.generate(text = text, sid = 0, speed = speed)
                if (speechToken.get() != token) break

                val samples = audio.samples
                val sampleRate = audio.sampleRate
                if (samples.isEmpty() || sampleRate <= 0) {
                    Log.w(TAG, "Sherpa-ONNX returned empty audio for chunk: $text")
                    continue
                }

                if (track == null) {
                    val minBuf = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT
                    ).coerceAtLeast(4096)

                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    activeTrack = track
                    track.play()
                }

                producedAudio = true
                writeFloatChunked(track, samples, token)

                if (chunk.pauseAfterMs > 0L && speechToken.get() == token) {
                    val silenceLen = ((sampleRate.toLong() * chunk.pauseAfterMs) / 1000L).toInt()
                    if (silenceLen > 0) {
                        writeFloatChunked(track, FloatArray(silenceLen), token)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error during Sherpa-ONNX playback", e)
        } finally {
            runCatching {
                track?.stop()
                track?.release()
            }
            if (activeTrack === track) {
                activeTrack = null
            }
            if (!producedAudio && speechToken.get() == token) {
                Log.w(TAG, "No audio produced by Sherpa-ONNX, falling back to Android TTS")
                mainHandler.post { speakWithFallback(reply) }
            } else if (speechToken.get() == token) {
                notifySpeechFinished()
            }
        }
    }

    private fun writeFloatChunked(track: AudioTrack, data: FloatArray, token: Long) {
        var offset = 0
        val step = 2048
        while (offset < data.size && speechToken.get() == token) {
            val count = minOf(step, data.size - offset)
            val written = track.write(data, offset, count, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private fun speakWithFallback(reply: MaksReply) {
        val engine = fallbackTts ?: return
        if (!isFallbackReady) return
        val textToSpeak = reply.displayText.trim()
        if (textToSpeak.isEmpty()) return

        notifySpeechStarted()
        engine.setSpeechRate((reply.speechRate * userSpeechRateMultiplier).coerceIn(0.75f, 1.5f))
        engine.setPitch(reply.speechPitch)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val uid = "maks_${UUID.randomUUID()}"
        lastUtteranceId = uid
        engine.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, uid)
    }

    private fun stopActiveAudio() {
        runCatching { fallbackTts?.stop() }
        val track = activeTrack
        activeTrack = null
        if (track != null) {
            runCatching {
                track.pause()
                track.flush()
                track.stop()
            }
        }
    }

    fun stop() {
        speechToken.incrementAndGet()
        stopActiveAudio()
        notifySpeechFinished()
    }

    fun shutdown() {
        speechToken.incrementAndGet()
        stopActiveAudio()
        ttsExecutor.execute {
            runCatching { sherpaTts?.release() }
            sherpaTts = null
        }
        ttsExecutor.shutdown()
        runCatching {
            fallbackTts?.stop()
            fallbackTts?.shutdown()
        }
        fallbackTts = null
        isFallbackReady = false
    }

    companion object {
        private const val TAG = "LocalTtsManager"
    }
}
