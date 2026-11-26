package com.maks.music.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * Плавный аудиоконтроллер (Audio Ducking + Crossfade):
 *   - duck(): мягкое приглушение по S-кривой за 160 мс до 15% громкости
 *   - restore(): плавный возврат за 450 мс после окончания речи
 *   - fadeCrossfade(): независимый канал фейда (1.5–3 сек) для переходов между треками.
 */
class DuckingController(
    private val scope: CoroutineScope,
    private val setPlayerVolume: (Float) -> Unit,
) {
    @Volatile
    var userVolume: Float = 1.0f
        private set

    @Volatile
    var defaultDuckLevel: Float = DUCK_LEVEL

    @Volatile
    private var duckMultiplier: Float = 1.0f

    @Volatile
    private var crossfadeMultiplier: Float = 1.0f

    @Volatile
    private var activeDuckCount: Int = 0

    private var duckJob: Job? = null
    private var crossfadeJob: Job? = null

    fun setUserVolume(level: Float) {
        userVolume = level.coerceIn(0f, 1f)
        applyCombinedVolume()
    }

    fun duck(duckFactor: Float = defaultDuckLevel) {
        activeDuckCount++
        val target = duckFactor.coerceIn(0f, 1f)
        animateDuckTo(target, durationMs = FADE_DUCK_MS)
    }

    fun restore() {
        if (activeDuckCount <= 0) return
        activeDuckCount--
        if (activeDuckCount > 0) return
        animateDuckTo(1.0f, durationMs = FADE_RESTORE_MS)
    }

    fun forceRestore() {
        activeDuckCount = 0
        animateDuckTo(1.0f, durationMs = FADE_RESTORE_MS)
    }

    fun setCrossfadeImmediate(level: Float) {
        crossfadeJob?.cancel()
        crossfadeMultiplier = level.coerceIn(0f, 1f)
        applyCombinedVolume()
    }

    fun fadeCrossfade(target: Float, durationMs: Long) {
        val clamped = target.coerceIn(0f, 1f)
        if (durationMs <= STEP_MS) {
            setCrossfadeImmediate(clamped)
            return
        }
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch {
            val start = crossfadeMultiplier
            val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val eased = easeInOutSine(t)
                crossfadeMultiplier = (start + (clamped - start) * eased).coerceIn(0f, 1f)
                applyCombinedVolume()
                if (i < steps) delay(STEP_MS)
            }
            crossfadeMultiplier = clamped
            applyCombinedVolume()
        }
    }

    private fun animateDuckTo(target: Float, durationMs: Long) {
        duckJob?.cancel()
        duckJob = scope.launch {
            val start = duckMultiplier
            val steps = (durationMs / STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val eased = easeInOutSine(t)
                duckMultiplier = (start + (target - start) * eased).coerceIn(0f, 1f)
                applyCombinedVolume()
                if (i < steps) delay(STEP_MS)
            }
            duckMultiplier = target
            applyCombinedVolume()
        }
    }

    private fun applyCombinedVolume() {
        val effective = (userVolume * duckMultiplier * crossfadeMultiplier).coerceIn(0f, 1f)
        setPlayerVolume(effective)
    }

    private fun easeInOutSine(t: Float): Float =
        ((1.0 - cos(PI * t.coerceIn(0f, 1f))) / 2.0).toFloat()

    companion object {
        private const val DUCK_LEVEL = 0.15f
        private const val FADE_DUCK_MS = 160L
        private const val FADE_RESTORE_MS = 450L
        private const val STEP_MS = 16L
    }
}