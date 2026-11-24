package com.maks.music.core.personality

import com.maks.music.core.TimeBucket
import kotlin.math.abs

/**
 * Порт personality_core/self_state.py:
 * внутреннее состояние Макса (mood, energy, valence, arousal, drowsy).
 */
class SelfState(
    var mood: String = "neutral",
    var energy: Double = 1.0,
    var isDrowsy: Boolean = false,
    var valence: Double = 0.0,
    var arousal: Double = 0.4,
    var commandsAnswered: Int = 0,
    var lastCommandTsMs: Long = System.currentTimeMillis(),
) {
    fun onCommand(intent: String) {
        val now = System.currentTimeMillis()
        commandsAnswered++
        lastCommandTsMs = now
        energy = (energy - ENERGY_PER_COMMAND).coerceIn(0.0, 1.0)

        when (intent) {
            "THANKS", "MUSIC_LIKE" -> {
                energy = (energy + 0.04).coerceIn(0.0, 1.0)
                valence = (valence + 0.14).coerceIn(-1.0, 1.0)
                arousal = (arousal + 0.05).coerceIn(0.0, 1.0)
            }
            "SKIP", "MUSIC_DISLIKE" -> {
                energy = (energy - ENERGY_PER_SKIP).coerceIn(0.0, 1.0)
                valence = (valence - 0.04).coerceIn(-1.0, 1.0)
            }
            "NOT_UNDERSTOOD" -> {
                valence = (valence - 0.05).coerceIn(-1.0, 1.0)
            }
        }
        decayAndRecompute()
    }

    fun onTrackFinished() {
        valence = (valence + 0.05).coerceIn(-1.0, 1.0)
        decayAndRecompute()
    }

    fun pickStyle(hour: Int = TimeBucket.currentHour()): String {
        if (TimeBucket.isNight(hour)) return "night"
        if (valence >= 0.25) return "friendly"
        if (valence <= -0.25) return "formal"
        return "normal"
    }

    private fun decayAndRecompute() {
        val baseValence = when {
            energy >= 0.8 -> 0.35
            energy >= 0.5 -> 0.15
            energy >= 0.2 -> 0.0
            else -> -0.15
        }
        valence += (baseValence - valence) * 0.02
        arousal += (0.4 - arousal) * 0.02

        mood = when {
            valence >= 0.30 -> "happy"
            valence >= 0.10 -> "good"
            valence <= -0.20 -> "tired"
            abs(arousal - 0.4) > 0.3 -> "curious"
            else -> "neutral"
        }

        val h = TimeBucket.currentHour()
        isDrowsy = energy <= 0.15 || (h >= 23 || h < 5)
    }

    companion object {
        private const val ENERGY_PER_COMMAND = 0.0003
        private const val ENERGY_PER_SKIP = 0.0005
    }
}
