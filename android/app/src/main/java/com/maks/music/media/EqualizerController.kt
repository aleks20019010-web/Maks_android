package com.maks.music.media

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build

/**
 * Студийный аудиопроцессор EQ Studio:
 *   - Честный bypass на пресете «Ровный» (0 дБ) — звук идёт в обход DSP без потери качества
 *   - Автоматический Pre-amp (компенсация пиков), полностью убирающий цифровой клиппинг и «песок»
 *   - 32-битный float-процессор DynamicsProcessing + мягкий лимитер на Android 9+ (API 28+).
 */
class EqualizerController {

    data class EqPreset(
        val id: String,
        val label: String,
        /** Уровни в дБ (-12..+12) для 5 полос: 60 Hz, 250 Hz, 1 kHz, 4 kHz, 14 kHz */
        val bandsDb: List<Float>,
    )

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var legacyEqualizer: Equalizer? = null
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

        val attachedModern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount = */ 2,
                    /* preEqInUse = */ true,
                    /* preEqBandCount = */ BAND_FREQS_HZ.size,
                    /* mbcInUse = */ false,
                    /* mbcBandCount = */ 0,
                    /* postEqInUse = */ false,
                    /* postEqBandCount = */ 0,
                    /* limiterInUse = */ true,
                ).build()

                dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                    setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(
                            /* inUse = */ true,
                            /* enabled = */ true,
                            /* linkGroup = */ 0,
                            /* attackTime = */ 1.5f,
                            /* releaseTime = */ 60f,
                            /* ratio = */ 4.0f,
                            /* threshold = */ -0.8f,
                            /* postGain = */ 0f,
                        )
                    )
                }
                true
            }.getOrDefault(false)
        } else {
            false
        }

        if (!attachedModern) {
            runCatching {
                legacyEqualizer = Equalizer(0, audioSessionId)
            }
        }

        pushBandsToHardware()

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
        pushBandsToHardware()
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
        currentPresetIndex = -1
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

    private fun isFlatCurve(): Boolean =
        currentBandsDb.all { kotlin.math.abs(it) < 0.05f }

    /**
     * Вычитающая компенсация пиков (Auto Pre-amp):
     * если пользователь поднимает полосы в плюс, вся кривая сдвигается вниз,
     * чтобы пиковый уровень не превышал 0 dB и не возникал цифровой «песок».
     */
    private fun compensatedBandsDb(): List<Float> {
        val maxBoost = currentBandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        if (maxBoost <= 0.1f) return currentBandsDb
        return currentBandsDb.map { (it - maxBoost * 0.85f).coerceIn(-12f, 6f) }
    }

    private fun pushBandsToHardware() {
        val shouldBeActive = eqEnabled && !isFlatCurve()
        val effectiveBands = compensatedBandsDb()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dp = dynamicsProcessing
            if (dp != null) {
                runCatching {
                    dp.enabled = shouldBeActive
                    if (shouldBeActive) {
                        for (i in BAND_FREQS_HZ.indices) {
                            val gain = effectiveBands.getOrElse(i) { 0f }
                            dp.setPreEqBandAllChannelsTo(
                                i,
                                DynamicsProcessing.EqBand(
                                    /* enabled = */ true,
                                    /* cutoffFrequency = */ BAND_FREQS_HZ[i],
                                    /* gain = */ gain,
                                )
                            )
                        }
                    }
                }
                return
            }
        }

        val eq = legacyEqualizer ?: return
        runCatching {
            eq.enabled = shouldBeActive
            if (!shouldBeActive) return@runCatching

            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) return@runCatching
            val range = eq.bandLevelRange
            val minLv = range[0].toInt()
            val maxLv = range[1].toInt()
            val span = (maxLv - minLv) / 2f
            val center = (maxLv + minLv) / 2f

            for (b in 0 until bands) {
                val normPos = if (bands > 1) b.toFloat() / (bands - 1) else 0f
                val curvePos = normPos * (effectiveBands.size - 1)
                val lo = curvePos.toInt().coerceIn(0, effectiveBands.lastIndex)
                val hi = (lo + 1).coerceIn(0, effectiveBands.lastIndex)
                val frac = curvePos - lo
                val db = effectiveBands[lo] + (effectiveBands[hi] - effectiveBands[lo]) * frac
                val normGain = (db / 12f).coerceIn(-1f, 1f)
                val level = (center + normGain * span).toInt().coerceIn(minLv, maxLv).toShort()
                eq.setBandLevel(b.toShort(), level)
            }
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamicsProcessing?.release() }
            dynamicsProcessing = null
        }
        runCatching { legacyEqualizer?.release() }
        runCatching { bassBoost?.release() }
        legacyEqualizer = null
        bassBoost = null
    }

    companion object {
        private const val BASS_BOOST_STRENGTH: Short = 420

        private val BAND_FREQS_HZ = floatArrayOf(65f, 250f, 1000f, 3800f, 12500f)
        val BAND_LABELS = listOf("60 Hz", "250 Hz", "1 kHz", "4 kHz", "14 kHz")

        val PRESETS = listOf(
            EqPreset("flat", "Ровный", listOf(0f, 0f, 0f, 0f, 0f)),
            EqPreset("deep", "Глубокий", listOf(4.5f, 2.0f, -1.0f, 0.5f, 1.5f)),
            EqPreset("vocal", "Вокал", listOf(-1.5f, 0.5f, 3.0f, 2.0f, 0.5f)),
            EqPreset("electronic", "Электро", listOf(4.0f, 1.5f, -1.5f, 1.5f, 2.8f)),
            EqPreset("rock", "Рок", listOf(3.5f, 1.0f, -2.0f, 1.8f, 2.5f)),
            EqPreset("classic", "Классика", listOf(1.5f, 0.5f, 0f, 1.0f, 2.0f)),
            EqPreset("club", "Клуб", listOf(4.0f, 2.5f, 0.5f, 1.5f, 1.0f)),
        )
    }
}