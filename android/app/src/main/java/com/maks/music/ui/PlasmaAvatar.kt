package com.maks.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AvatarMode {
    IDLE, SLEEP, LISTENING, SPEAKING, MUSIC, HAPPY, CURIOUS, ERROR
}

private data class AvatarPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val core: Color,
)

/**
 * Проработанное объёмное плазменное 3D-ядро Макса (Jetpack Compose Canvas):
 *   - многослойная сфера с мягким атмосферным свечением (без прямоугольной обрезки)
 *   - вращающиеся прецизионные орбитальные дуги и светящиеся узлы
 *   - 8 высокодетализированных 3D-лент с двухпроходным bloom-свечением и глубиной по оси Z
 *   - аудио-реактивное гармоническое кольцо-корона
 *   - 18 орбитальных микрочастиц с перспективным масштабированием
 *   - объёмное белое энергоядро и стеклянный блик.
 */
@Composable
fun PlasmaAvatar(
    mode: AvatarMode,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    var smoothAudio by remember { mutableFloatStateOf(0f) }

    val targetSpeed = when (mode) {
        AvatarMode.SLEEP -> 0.30f
        AvatarMode.IDLE -> 0.72f
        AvatarMode.LISTENING -> 1.30f
        AvatarMode.CURIOUS -> 1.45f
        AvatarMode.SPEAKING -> 1.55f
        AvatarMode.MUSIC -> 1.25f
        AvatarMode.HAPPY -> 1.18f
        AvatarMode.ERROR -> 0.85f
    }

    val latestAudio by rememberUpdatedState(audioLevel.coerceIn(0f, 1f))

    LaunchedEffect(targetSpeed) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastNs != 0L) {
                    val dt = ((frameNs - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
                    phase += dt * targetSpeed
                    smoothAudio += (latestAudio - smoothAudio) * (dt * 10f).coerceAtMost(1f)
                }
                lastNs = frameNs
            }
        }
    }

    val targetPalette = remember(mode) { paletteFor(mode) }
    val c1 by animateColorAsState(targetPalette.primary, tween(550), label = "plasma_c1")
    val c2 by animateColorAsState(targetPalette.secondary, tween(550), label = "plasma_c2")
    val c3 by animateColorAsState(targetPalette.accent, tween(550), label = "plasma_c3")
    val cCore by animateColorAsState(targetPalette.core, tween(550), label = "plasma_core")

    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        if (minDim <= 4f) return@Canvas

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val center = Offset(cx, cy)

        val energy = smoothAudio.coerceIn(0f, 1f)
        val breathe = sin(phase * 1.9f) * 0.025f
        val maxSafeRadius = minDim * 0.49f
        val sphereR = (minDim * 0.315f * (1f + energy * 0.09f + breathe)).coerceAtLeast(2f)
        val haloR = (sphereR * 1.48f).coerceAtMost(maxSafeRadius)

        // 1. Мягкая космическая туманность вокруг сферы (строго вписана в холст, без обрезки)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c1.copy(alpha = 0.30f + energy * 0.16f),
                0.48f to c2.copy(alpha = 0.16f + energy * 0.08f),
                0.78f to c3.copy(alpha = 0.05f),
                1.0f to Color.Transparent,
                center = center,
                radius = haloR,
            ),
            radius = haloR,
            center = center,
        )

        // 2. Тёмное объёмное стеклянное тело сферы + внутреннее френелевское свечение края
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color(0xFF0A1228).copy(alpha = 0.88f),
                0.68f to Color(0xFF060B1B).copy(alpha = 0.82f),
                0.88f to c1.copy(alpha = 0.24f + energy * 0.12f),
                0.97f to c3.copy(alpha = 0.38f + energy * 0.18f),
                1.0f to Color.Transparent,
                center = center,
                radius = sphereR * 1.04f,
            ),
            radius = sphereR * 1.04f,
            center = center,
        )

        // 3. Прецизионное внешнее орбитальное HUD-кольцо с двумя дугами и бегущими огнями
        val ringR = (sphereR * 1.18f).coerceAtMost(maxSafeRadius * 0.96f)
        val ringTopLeft = Offset(cx - ringR, cy - ringR)
        val ringSize = Size(ringR * 2f, ringR * 2f)
        val ringDeg1 = (phase * 26f) % 360f
        val ringDeg2 = (-phase * 19f + 135f) % 360f

        drawCircle(
            color = c1.copy(alpha = 0.14f),
            radius = ringR,
            center = center,
            style = Stroke(width = (minDim * 0.0045f).coerceAtLeast(1f)),
        )
        drawArc(
            color = c3.copy(alpha = 0.42f + energy * 0.25f),
            startAngle = ringDeg1,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = (minDim * 0.009f).coerceAtLeast(1.4f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )
        drawArc(
            color = c2.copy(alpha = 0.36f + energy * 0.22f),
            startAngle = ringDeg2,
            sweepAngle = 54f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = (minDim * 0.007f).coerceAtLeast(1.2f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )

        // Светящиеся узлы на концах орбитальной дуги
        val nodeRad = (ringDeg1 + 72f) * (PI.toFloat() / 180f)
        val nodePos = Offset(cx + cos(nodeRad) * ringR, cy + sin(nodeRad) * ringR)
        val nodeGlowR = (minDim * 0.032f).coerceAtLeast(3f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cCore.copy(alpha = 0.95f), c3.copy(alpha = 0.45f), Color.Transparent),
                center = nodePos,
                radius = nodeGlowR,
            ),
            radius = nodeGlowR,
            center = nodePos,
            blendMode = BlendMode.Screen,
        )

        // 4. Аудио-реактивное гармоническое кольцо-корона вокруг экватора сферы
        val coronaPath = Path()
        val coronaSteps = 72
        for (s in 0..coronaSteps) {
            val a = (s.toFloat() / coronaSteps) * (2f * PI.toFloat())
            val wave1 = sin(a * 5f + phase * 3.4f) * (0.025f + energy * 0.075f)
            val wave2 = cos(a * 3f - phase * 2.2f) * (0.018f + energy * 0.045f)
            val rr = (sphereR * (0.96f + wave1 + wave2)).coerceAtMost(maxSafeRadius * 0.94f)
            val px = cx + cos(a) * rr
            val py = cy + sin(a) * rr
            if (s == 0) coronaPath.moveTo(px, py) else coronaPath.lineTo(px, py)
        }
        drawPath(
            path = coronaPath,
            color = c3.copy(alpha = 0.14f + energy * 0.22f),
            style = Stroke(
                width = (minDim * 0.020f).coerceAtLeast(2.4f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )
        drawPath(
            path = coronaPath,
            color = cCore.copy(alpha = 0.26f + energy * 0.34f),
            style = Stroke(
                width = (minDim * 0.006f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )

        // 5. 8 высокодетализированных 3D плазменных шелковых лент (72 шага + двухпроходный bloom)
        val ribbonCount = 8
        val steps = 72
        val bloomStrokeW = (minDim * 0.024f + energy * minDim * 0.012f).coerceAtLeast(3f)
        val coreStrokeW = (minDim * 0.0085f + energy * minDim * 0.0045f).coerceAtLeast(1.3f)

        for (i in 0 until ribbonCount) {
            val tiltX = (i.toFloat() / ribbonCount) * PI.toFloat() + sin(phase * 0.45f + i) * 0.18f
            val tiltZ = (i * 0.62f) + phase * (0.28f + (i % 3) * 0.11f)
            val spin = phase * (0.85f + (i % 4) * 0.22f) + i * 1.13f
            val ribbonScale = 0.72f + (i % 3) * 0.11f

            val path = Path()
            val cosTiltX = cos(tiltX)
            val sinTiltX = sin(tiltX)
            val cosSpin = cos(spin)
            val sinSpin = sin(spin)
            val cosTiltZ = cos(tiltZ)
            val sinTiltZ = sin(tiltZ)

            for (s in 0..steps) {
                val a = (s.toFloat() / steps) * (2f * PI.toFloat())
                val harmonic = 1f +
                    0.075f * sin(a * 3f + spin * 1.4f + i) +
                    (0.035f + energy * 0.11f) * cos(a * 4f - phase * 3.1f)
                val rad = ribbonScale * harmonic

                // Базовая окружность в 3D
                val x0 = cos(a) * rad
                val y0 = sin(a) * cosTiltX * rad
                val z0 = sin(a) * sinTiltX * rad

                // Вращение вокруг Y
                val x1 = x0 * cosSpin - z0 * sinSpin
                val z1 = x0 * sinSpin + z0 * cosSpin

                // Наклон вокруг Z
                val x2 = x1 * cosTiltZ - y0 * sinTiltZ
                val y2 = x1 * sinTiltZ + y0 * cosTiltZ

                val perspective = 2.9f / (2.9f - z1 * 0.62f)
                val px = cx + x2 * sphereR * perspective
                val py = cy + y2 * sphereR * perspective
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            val ribbonColor = when (i % 3) {
                0 -> c1
                1 -> c2
                else -> c3
            }

            // Проход 1: мягкое широкое свечение (bloom)
            drawPath(
                path = path,
                color = ribbonColor.copy(alpha = 0.16f + energy * 0.10f),
                style = Stroke(width = bloomStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
            // Проход 2: чёткая яркая нить
            drawPath(
                path = path,
                color = ribbonColor.copy(alpha = 0.56f + (i % 2) * 0.18f),
                style = Stroke(width = coreStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
        }

        // 6. Орбитальные микрочастицы (18 светящихся искр в 3D)
        val particleCount = 18
        for (p in 0 until particleCount) {
            val seed = p * 2.39996f // золотой угол
            val orbitSpeed = 0.45f + (p % 5) * 0.14f
            val theta = phase * orbitSpeed + seed
            val phi = sin(phase * 0.35f + p * 0.9f) * 1.15f + (p % 3 - 1) * 0.45f
            val dist = sphereR * (0.68f + (p % 4) * 0.13f + energy * 0.08f)

            val x3 = cos(theta) * cos(phi)
            val y3 = sin(phi)
            val z3 = sin(theta) * cos(phi)
            val depth = (z3 + 1.2f) / 2.2f // 0..1
            val persp = 2.6f / (2.6f - z3 * 0.55f)

            val px = cx + x3 * dist * persp
            val py = cy + y3 * dist * persp
            val twinkle = 0.45f + 0.55f * sin(phase * 3.8f + p * 1.7f)
            val dotR = ((minDim * 0.008f) + (p % 3) * (minDim * 0.003f)) * (0.6f + depth * 0.7f)
            val pColor = if (p % 2 == 0) cCore else c3

            drawCircle(
                color = pColor.copy(alpha = (0.28f + depth * 0.52f) * twinkle),
                radius = dotR.coerceAtLeast(1.1f),
                center = Offset(px, py),
                blendMode = BlendMode.Screen,
            )
        }

        // 7. Яркое многослойное плазменное ядро в центре
        val coreOuterR = (sphereR * (0.68f + energy * 0.14f)).coerceAtLeast(2f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.96f),
                0.18f to cCore.copy(alpha = 0.88f),
                0.45f to c1.copy(alpha = 0.48f + energy * 0.16f),
                0.76f to c2.copy(alpha = 0.18f),
                1.0f to Color.Transparent,
                center = center,
                radius = coreOuterR,
            ),
            radius = coreOuterR,
            center = center,
            blendMode = BlendMode.Screen,
        )

        // 8. Тонкий стеклянный блик на верхней левой грани сферы (объём)
        val highlightR = sphereR * 0.86f
        val hlTopLeft = Offset(cx - highlightR, cy - highlightR)
        val hlSize = Size(highlightR * 2f, highlightR * 2f)
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.34f),
                    cCore.copy(alpha = 0.10f),
                    Color.Transparent,
                ),
                start = Offset(cx - highlightR, cy - highlightR),
                end = Offset(cx, cy),
            ),
            startAngle = 205f,
            sweepAngle = 58f,
            useCenter = false,
            topLeft = hlTopLeft,
            size = hlSize,
            style = Stroke(width = (minDim * 0.011f).coerceAtLeast(1.5f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )
    }
}

private fun paletteFor(mode: AvatarMode): AvatarPalette = when (mode) {
    AvatarMode.IDLE -> AvatarPalette(
        primary = Color(0xFF38BDF8),
        secondary = Color(0xFF818CF8),
        accent = Color(0xFF22D3EE),
        core = Color(0xFFE0F2FE),
    )
    AvatarMode.SLEEP -> AvatarPalette(
        primary = Color(0xFF334155),
        secondary = Color(0xFF4338CA),
        accent = Color(0xFF38BDF8),
        core = Color(0xFFCBD5E1),
    )
    AvatarMode.LISTENING -> AvatarPalette(
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFF38BDF8),
        accent = Color(0xFF2DD4BF),
        core = Color(0xFFFFFFFF),
    )
    AvatarMode.CURIOUS -> AvatarPalette(
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFFF59E0B),
        accent = Color(0xFF38BDF8),
        core = Color(0xFFFEF3C7),
    )
    AvatarMode.SPEAKING -> AvatarPalette(
        primary = Color(0xFF38BDF8),
        secondary = Color(0xFFC084FC),
        accent = Color(0xFF22D3EE),
        core = Color(0xFFFFFFFF),
    )
    AvatarMode.MUSIC -> AvatarPalette(
        primary = Color(0xFF22D3EE),
        secondary = Color(0xFFA855F7),
        accent = Color(0xFF2DD4BF),
        core = Color(0xFFF0FDFA),
    )
    AvatarMode.HAPPY -> AvatarPalette(
        primary = Color(0xFF2DD4BF),
        secondary = Color(0xFF6366F1),
        accent = Color(0xFF38BDF8),
        core = Color(0xFFF0FDFA),
    )
    AvatarMode.ERROR -> AvatarPalette(
        primary = Color(0xFFF59E0B),
        secondary = Color(0xFFEF4444),
        accent = Color(0xFFFBBF24),
        core = Color(0xFFFEF3C7),
    )
}
