package com.maks.music.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.maks.music.core.personality.MaksReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * 100% локальный синтез речи на встроенном Android TextToSpeech (ru-RU).
 * Заменяет Yandex SpeechKit TTS:
 *   - применяет speechRate / speechPitch из PersonalityEngine
 *   - сохраняет интонационные паузы sil<[ms]> через playSilentUtterance()
 *   - гарантированно приглушает музыку (duck/restore) на Main-потоке для ExoPlayer.
 */
class LocalTtsManager(
    context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit,
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = runCatching {
        TextToSpeech(context.applicationContext, this)
    }.getOrNull()

    @Volatile
    private var isReady: Boolean = false

    @Volatile
    private var lastUtteranceId: String? = null

    @Volatile
    private var duckActiveForSpeech: Boolean = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return
        val ru = Locale("ru", "RU")
        engine.language = ru
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
        isReady = true
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

    fun speak(reply: MaksReply) {
        val engine = tts ?: return
        if (!isReady) return
        val chunks = reply.speechChunks
        if (chunks.isEmpty()) return

        // Начинаем приглушать музыку сразу на Main-потоке за 100 мс до первого звука речи
        notifySpeechStarted()

        engine.setSpeechRate(reply.speechRate)
        engine.setPitch(reply.speechPitch)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        var finalId: String? = null
        chunks.forEachIndexed { idx, chunk ->
            val mode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val uid = "maks_${UUID.randomUUID()}"
            finalId = uid
            engine.speak(chunk.text, mode, params, uid)

            if (chunk.pauseAfterMs > 0L && idx < chunks.lastIndex) {
                val silId = "maks_sil_${UUID.randomUUID()}"
                finalId = silId
                engine.playSilentUtterance(chunk.pauseAfterMs, TextToSpeech.QUEUE_ADD, silId)
            }
        }
        lastUtteranceId = finalId
    }

    fun stop() {
        tts?.stop()
        notifySpeechFinished()
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        isReady = false
    }
}