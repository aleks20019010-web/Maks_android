package com.maks.music.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Плавное приглушение (ducking) музыки при речи TTS и прослушке команды:
 *   - duck(): быстрый fade за 100 мс до 10% громкости
 *   - restore(): плавный fade обратно за 900 мс после окончания речи.
 */
class DuckingController(
    private val scope: CoroutineScope,
    private val setPlayerVolume: (Float) -> Unit,
) {
    @Volatile
    var userVolume: Float = 1.0f
        private set

    @Volatile
    private var currentVolume: Float = 1.0f

    @Volatile
    private var activeDuckCount: Int = 0

    private var fadeJob: Job? = null

    fun setUserVolume(level: Float) {
        userVolume = level.coerceIn(0f, 1f)
        if (activeDuckCount <= 0) {
            fadeTo(userVolume, durationMs = 80L)
        }
    }

    fun duck(duckFactor: Float = DUCK_LEVEL) {
        activeDuckCount++
        val target = (userVolume * duckFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        fadeTo(target, durationMs = FADE_DUCK_MS)
    }

    fun restore() {
        if (activeDuckCount <= 0) return
        activeDuckCount--
        if (activeDuckCount > 0) return
        fadeTo(userVolume, durationMs = FADE_RESTORE_MS)
    }

    fun forceRestore() {
        activeDuckCount = 0
        fadeTo(userVolume, durationMs = FADE_RESTORE_MS)
    }

    private fun fadeTo(target: Float, durationMs: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val start = currentVolume
            val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val frac = i.toFloat() / steps
                val v = (start + (target - start) * frac).coerceIn(0f, 1f)
                currentVolume = v
                setPlayerVolume(v)
                if (i < steps) delay(STEP_MS)
            }
            currentVolume = target
            setPlayerVolume(target)
        }
    }

    companion object {
        private const val DUCK_LEVEL = 0.10f
        private const val FADE_DUCK_MS = 100L
        private const val FADE_RESTORE_MS = 900L
        private const val STEP_MS = 25L
    }
}