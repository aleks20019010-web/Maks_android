package com.maks.music.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * ???????? L/R ??????-?????? ??? 16-bit PCM ? Media3 AudioProcessor ???????.
 * balance ? ????????? [-1f..1f]:
 *  -1f -> L = 1.0, R = 0.0
 *   0f -> L = 1.0, R = 1.0
 *  +1f -> L = 0.0, R = 1.0
 */
class StereoBalanceAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var balance: Float = 0f

    @Volatile
    private var leftGain: Float = 1f

    @Volatile
    private var rightGain: Float = 1f

    fun setBalance(newBalance: Float) {
        val clamped = newBalance.coerceIn(-1f, 1f)
        balance = clamped
        if (clamped <= 0f) {
            leftGain = 1f
            rightGain = (1f + clamped).coerceIn(0f, 1f)
        } else {
            leftGain = (1f - clamped).coerceIn(0f, 1f)
            rightGain = 1f
        }
    }

    fun getBalance(): Float = balance

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val isSupported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount == 2 &&
            inputAudioFormat.sampleRate > 0
        return if (isSupported) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val lGain = leftGain
        val rGain = rightGain

        // ???? ????? (1.0 / 1.0) ? ?????????? ??? ?????? ?????
        if (lGain >= 0.999f && rGain >= 0.999f) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            out.put(inputBuffer)
            out.flip()
            return
        }

        val frameBytes = 4 // 2 ?????? * 2 ????? (16-bit PCM)
        val usableBytes = (inputBuffer.remaining() / frameBytes) * frameBytes
        if (usableBytes <= 0) return

        val out = replaceOutputBuffer(usableBytes)
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        val frames = usableBytes / frameBytes
        for (i in 0 until frames) {
            val leftSample = src.short.toInt()
            val rightSample = src.short.toInt()
            val scaledL = (leftSample * lGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val scaledR = (rightSample * rGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out.putShort(scaledL)
            out.putShort(scaledR)
        }

        val leftover = min(inputBuffer.remaining(), 0)
        if (leftover > 0) {
            val limit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + leftover)
            out.put(inputBuffer)
            inputBuffer.limit(limit)
        }
        out.flip()
    }
}
