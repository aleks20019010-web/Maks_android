package com.maks.music.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Порт плавного приглушения (ducking) из workers/music/player.py:
 *   - duck(): быстрый fade за 100 мс до 28% (чтобы голос Макса/микрофон не перебивались музыкой)
 *   - restore(): плавный fade обратно за 1000 мс после окончания речи.
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
    private var isDucked: Boolean = false

    private var fadeJob: Job? = null

    fun setUserVolume(level: Float) {
        userVolume = level.coerceIn(0f, 1f)
        if (!isDucked) {
            fadeTo(userVolume, durationMs = 80L)
        }
    }

    fun duck(duckFactor: Float = DUCK_LEVEL) {
        isDucked = true
        val target = (userVolume * duckFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        fadeTo(target, durationMs = FADE_DUCK_MS)
    }

    fun restore() {
        if (!isDucked) return
        isDucked = false
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
        private const val DUCK_LEVEL = 0.25f
        private const val FADE_DUCK_MS = 100L
        private const val FADE_RESTORE_MS = 1000L
        private const val STEP_MS = 25L
    }
}
