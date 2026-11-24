package com.maks.music.media

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer

/**
 * Системный аудиопроцессор EQ Studio (5 полос + пресеты + аппаратный BassBoost).
 */
class EqualizerController {

    data class EqPreset(
        val id: String,
        val label: String,
        /** Уровни в дБ (-12..+12) для 5 полос: 60 Hz, 250 Hz, 1 kHz, 4 kHz, 14 kHz */
        val bandsDb: List<Float>,
    )

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    @Volatile
    var eqEnabled: Boolean = true
        private set

    @Volatile
    var currentPresetIndex: Int = 0
        private set

    @Volatile
    var currentBandsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f)
        private set

    @Volatile
    var bassBoostEnabled: Boolean = false
        private set

    @Volatile
    var stereoBalance: Float = 0f
        private set

    fun attachToAudioSession(audioSessionId: Int) {
        if (audioSessionId == 0) return
        release()
        runCatching {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = eqEnabled
            }
            pushBandsToHardware()
        }
        runCatching {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = bassBoostEnabled
                if (strengthSupported) {
                    setStrength(if (bassBoostEnabled) BASS_BOOST_STRENGTH else 0)
                }
            }
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        eqEnabled = enabled
        runCatching { equalizer?.enabled = enabled }
    }

    fun applyPreset(index: Int): EqPreset {
        val safeIdx = index.coerceIn(0, PRESETS.lastIndex)
        currentPresetIndex = safeIdx
        val preset = PRESETS[safeIdx]
        currentBandsDb = preset.bandsDb
        pushBandsToHardware()
        return preset
    }

    fun setBandGainDb(bandIndex: Int, db: Float) {
        if (bandIndex !in 0..4) return
        val clamped = db.coerceIn(-12f, 12f)
        val updated = currentBandsDb.toMutableList()
        while (updated.size < 5) updated.add(0f)
        updated[bandIndex] = clamped
        currentBandsDb = updated
        currentPresetIndex = -1 // Пользовательская кривая
        pushBandsToHardware()
    }

    fun setBassBoostEnabled(enabled: Boolean) {
        bassBoostEnabled = enabled
        runCatching {
            bassBoost?.let { bb ->
                bb.enabled = enabled
                if (bb.strengthSupported) {
                    bb.setStrength(if (enabled) BASS_BOOST_STRENGTH else 0)
                }
            }
        }
    }

    fun setStereoBalance(balance: Float) {
        stereoBalance = balance.coerceIn(-1f, 1f)
    }

    private fun pushBandsToHardware() {
        val eq = equalizer ?: return
        runCatching {
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) return@runCatching
            val range = eq.bandLevelRange
            val minLv = range[0].toInt()
            val maxLv = range[1].toInt()
            val span = (maxLv - minLv) / 2f
            val center = (maxLv + minLv) / 2f

            for (b in 0 until bands) {
                val normPos = if (bands > 1) b.toFloat() / (bands - 1) else 0f
                val curvePos = normPos * (currentBandsDb.size - 1)
                val lo = curvePos.toInt().coerceIn(0, currentBandsDb.lastIndex)
                val hi = (lo + 1).coerceIn(0, currentBandsDb.lastIndex)
                val frac = curvePos - lo
                val db = currentBandsDb[lo] + (currentBandsDb[hi] - currentBandsDb[lo]) * frac
                val normGain = (db / 12f).coerceIn(-1f, 1f)
                val level = (center + normGain * span).toInt().coerceIn(minLv, maxLv).toShort()
                eq.setBandLevel(b.toShort(), level)
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
    }

    companion object {
        private const val BASS_BOOST_STRENGTH: Short = 650

        val BAND_LABELS = listOf("60 Hz", "250 Hz", "1 kHz", "4 kHz", "14 kHz")

        val PRESETS = listOf(
            EqPreset("flat", "Ровный", listOf(0f, 0f, 0f, 0f, 0f)),
            EqPreset("deep", "Глубокий", listOf(5.0f, 2.5f, -0.5f, 1.0f, 2.0f)),
            EqPreset("vocal", "Вокал", listOf(-1.5f, 1.0f, 4.0f, 3.0f, 1.0f)),
            EqPreset("electronic", "Электро", listOf(4.5f, 2.0f, -1.0f, 2.5f, 4.5f)),
            EqPreset("rock", "Рок", listOf(3.5f, 1.5f, -1.5f, 2.5f, 4.0f)),
            EqPreset("classic", "Классика", listOf(1.5f, 0.5f, 0f, 1.5f, 3.0f)),
            EqPreset("club", "Клуб", listOf(4.5f, 3.0f, 1.5f, 2.0f, 1.5f)),
        )
    }
}
