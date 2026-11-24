package com.maks.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.sin

/**
 * Тонкая зеркальная аудиоволна с прогрессом (в стиле Midnight Obsidian + Violet-Cyan).
 * Позволяет перематывать трек касанием или перетаскиванием по волне.
 */
@Composable
fun MirrorSpectrumVisualizer(
    isActive: Boolean,
    intensity: Float,
    accentColor: Color,
    progress: Float = 0f,
    trackSeed: Int = 1,
    onSeekFraction: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var time by remember { mutableFloatStateOf(0f) }
    val latestOnSeek by rememberUpdatedState(onSeekFraction)

    LaunchedEffect(isActive) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { ns ->
                if (lastNs != 0L) {
                    val dt = ((ns - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
                    time += dt * if (isActive) 4.4f else 0.7f
                }
                lastNs = ns
            }
        }
    }

    // Детерминированный профиль волны для текущего трека (64 тонких столбика)
    val barCount = 64
    val profile = remember(trackSeed) {
        FloatArray(barCount) { i ->
            val t = i.toFloat() / (barCount - 1)
            val env = 0.25f + 0.75f * sin(t * Math.PI.toFloat())
            val h1 = abs(sin((i + trackSeed * 0.37f) * 0.62f))
            val h2 = abs(sin((i * 1.41f + trackSeed * 0.91f) * 0.31f))
            val spike = if ((i + trackSeed) % 11 == 0) 0.32f else 0f
            ((0.12f + (h1 * 0.55f + h2 * 0.35f) * env) + spike).coerceIn(0.10f, 1.0f)
        }
    }

    val interactiveModifier = if (onSeekFraction != null) {
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        latestOnSeek?.invoke((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) {
                        latestOnSeek?.invoke((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
    } else {
        modifier
    }

    Canvas(modifier = interactiveModifier) {
        if (size.width <= 4f || size.height <= 4f) return@Canvas
        val clampedProgress = progress.coerceIn(0f, 1f)
        val gap = (size.width * 0.0085f).coerceIn(2f, 4.5f)
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1.8f)
        val midY = size.height * 0.5f

        val playedBrush = Brush.horizontalGradient(
            colors = listOf(MaksColors.VioletAccent, MaksColors.LavenderGlow, accentColor),
            startX = 0f,
            endX = size.width.coerceAtLeast(1f),
        )
        val unplayedColor = Color(0xFF282D46)

        for (i in 0 until barCount) {
            val frac = i.toFloat() / (barCount - 1)
            val baseAmp = profile[i]
            val liveRipple = if (isActive) {
                val boost = 0.72f + 0.28f * intensity.coerceIn(0f, 1f)
                val osc = 0.80f + 0.24f * sin(time + i * 0.42f) * sin(time * 0.65f - i * 0.19f)
                (baseAmp * osc * boost).coerceIn(0.10f, 1f)
            } else {
                (baseAmp * 0.72f).coerceIn(0.08f, 0.85f)
            }

            val barH = (size.height * liveRipple).coerceAtLeast(2.5f)
            val x = i * (barWidth + gap)
            val top = midY - barH * 0.5f

            if (frac <= clampedProgress) {
                drawRoundRect(
                    brush = playedBrush,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f),
                )
            } else {
                drawRoundRect(
                    color = unplayedColor,
                    topLeft = Offset(x, top),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f),
                )
            }
        }
    }
}
