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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
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

@Composable
fun PlasmaAvatar(
    mode: AvatarMode,
    audioLevel: Float,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableFloatStateOf(0f) }
    var smoothAudio by remember { mutableFloatStateOf(0f) }

    val targetSpeed = when (mode) {
        AvatarMode.SLEEP -> 0.28f
        AvatarMode.IDLE -> 0.72f
        AvatarMode.LISTENING -> 1.38f
        AvatarMode.CURIOUS -> 1.48f
        AvatarMode.SPEAKING -> 1.65f
        AvatarMode.MUSIC -> 1.32f
        AvatarMode.HAPPY -> 1.22f
        AvatarMode.ERROR -> 0.90f
    }

    val latestAudio by rememberUpdatedState(audioLevel.coerceIn(0f, 1f))
    val currentMode by rememberUpdatedState(mode)

    LaunchedEffect(targetSpeed) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastNs != 0L) {
                    val dt = ((frameNs - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
                    phase += dt * targetSpeed

                    // В режиме MUSIC добавляем живую ритмическую пульсацию поверх базового уровня
                    val effectiveTarget = if (currentMode == AvatarMode.MUSIC) {
                        val beat = (abs(sin(phase * 3.1f)).pow(3f) * 0.34f +
                            abs(cos(phase * 1.7f)) * 0.14f)
                        (latestAudio * 0.65f + beat).coerceIn(0.12f, 0.95f)
                    } else {
                        latestAudio
                    }

                    val attack = if (effectiveTarget > smoothAudio) 14f else 6f
                    smoothAudio += (effectiveTarget - smoothAudio) * (dt * attack).coerceAtMost(1f)
                }
                lastNs = frameNs
            }
        }
    }

    val targetPalette = remember(mode) { paletteFor(mode) }
    val c1 by animateColorAsState(targetPalette.primary, tween(500), label = "plasma_c1")
    val c2 by animateColorAsState(targetPalette.secondary, tween(500), label = "plasma_c2")
    val c3 by animateColorAsState(targetPalette.accent, tween(500), label = "plasma_c3")
    val cCore by animateColorAsState(targetPalette.core, tween(500), label = "plasma_core")

    Canvas(modifier = modifier) {
        val minDim = size.minDimension
        if (minDim <= 4f) return@Canvas

        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val center = Offset(cx, cy)

        val energy = smoothAudio.coerceIn(0f, 1f)
        val breathe = sin(phase * 1.9f) * 0.022f
        val maxSafeRadius = minDim * 0.49f
        val sphereR = (minDim * 0.31f * (1f + energy * 0.10f + breathe)).coerceAtLeast(2f)
        val haloR = (sphereR * 1.52f).coerceAtMost(maxSafeRadius)

        // 1. Внешняя космическая туманность
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c1.copy(alpha = 0.34f + energy * 0.18f),
                0.46f to c2.copy(alpha = 0.18f + energy * 0.10f),
                0.78f to c3.copy(alpha = 0.06f),
                1.0f to Color.Transparent,
                center = center,
                radius = haloR,
            ),
            radius = haloR,
            center = center,
        )

        // 2. Расширяющаяся ударная волна (Shockwave) при активности голоса или музыки
        if (energy > 0.16f) {
            for (w in 0..1) {
                val waveProgress = ((phase * 0.55f + w * 0.5f) % 1f)
                val rippleR = sphereR * (0.95f + waveProgress * 0.48f)
                if (rippleR < maxSafeRadius) {
                    val rippleAlpha = ((1f - waveProgress) * (energy * 0.32f)).coerceIn(0f, 0.35f)
                    drawCircle(
                        color = c3.copy(alpha = rippleAlpha),
                        radius = rippleR,
                        center = center,
                        style = Stroke(width = (minDim * 0.006f).coerceAtLeast(1f)),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        }

        // 3. Тёмная стеклянная сфера с френелевским ободком
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color(0xFF0B132B).copy(alpha = 0.90f),
                0.66f to Color(0xFF060A18).copy(alpha = 0.85f),
                0.88f to c1.copy(alpha = 0.28f + energy * 0.14f),
                0.97f to c3.copy(alpha = 0.44f + energy * 0.20f),
                1.0f to Color.Transparent,
                center = center,
                radius = sphereR * 1.04f,
            ),
            radius = sphereR * 1.04f,
            center = center,
        )

        // 4. Внешнее орбитальное HUD-кольцо с микро-рисками и 2 светящимися узлами
        val ringR = (sphereR * 1.20f).coerceAtMost(maxSafeRadius * 0.96f)
        val ringTopLeft = Offset(cx - ringR, cy - ringR)
        val ringSize = Size(ringR * 2f, ringR * 2f)
        val ringDeg1 = (phase * 28f) % 360f
        val ringDeg2 = (-phase * 21f + 140f) % 360f

        drawCircle(
            color = c1.copy(alpha = 0.15f),
            radius = ringR,
            center = center,
            style = Stroke(width = (minDim * 0.0045f).coerceAtLeast(1f)),
        )

        // 24 тонкие орбитальные насечки на внешнем кольце
        for (t in 0 until 24) {
            val tRad = ((t * 15f) + ringDeg1 * 0.35f) * (PI.toFloat() / 180f)
            val isCardinal = t % 6 == 0
            val r1 = ringR - (if (isCardinal) minDim * 0.016f else minDim * 0.008f)
            val r2 = ringR + (if (isCardinal) minDim * 0.016f else minDim * 0.008f)
            drawLine(
                color = c3.copy(alpha = if (isCardinal) 0.42f else 0.18f),
                start = Offset(cx + cos(tRad) * r1, cy + sin(tRad) * r1),
                end = Offset(cx + cos(tRad) * r2, cy + sin(tRad) * r2),
                strokeWidth = (if (isCardinal) 1.5f else 1f),
                cap = StrokeCap.Round,
                blendMode = BlendMode.Screen,
            )
        }

        drawArc(
            color = c3.copy(alpha = 0.48f + energy * 0.25f),
            startAngle = ringDeg1,
            sweepAngle = 78f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = (minDim * 0.0095f).coerceAtLeast(1.4f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )
        drawArc(
            color = c2.copy(alpha = 0.40f + energy * 0.22f),
            startAngle = ringDeg2,
            sweepAngle = 58f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = (minDim * 0.0075f).coerceAtLeast(1.2f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )

        // Два светящихся узла на концах дуг
        listOf(ringDeg1 + 78f to c3, ringDeg2 + 58f to c2).forEach { (deg, nodeColor) ->
            val nodeRad = deg * (PI.toFloat() / 180f)
            val nodePos = Offset(cx + cos(nodeRad) * ringR, cy + sin(nodeRad) * ringR)
            val nodeGlowR = (minDim * 0.032f).coerceAtLeast(3f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cCore.copy(alpha = 0.95f), nodeColor.copy(alpha = 0.50f), Color.Transparent),
                    center = nodePos,
                    radius = nodeGlowR,
                ),
                radius = nodeGlowR,
                center = nodePos,
                blendMode = BlendMode.Screen,
            )
        }

        // 5. Аудио-реактивная плазменная корона вокруг экватора
        val coronaPath = Path()
        val coronaSteps = 80
        for (s in 0..coronaSteps) {
            val a = (s.toFloat() / coronaSteps) * (2f * PI.toFloat())
            val wave1 = sin(a * 6f + phase * 3.6f) * (0.022f + energy * 0.085f)
            val wave2 = cos(a * 3f - phase * 2.4f) * (0.016f + energy * 0.050f)
            val rr = (sphereR * (0.95f + wave1 + wave2)).coerceAtMost(maxSafeRadius * 0.94f)
            val px = cx + cos(a) * rr
            val py = cy + sin(a) * rr
            if (s == 0) coronaPath.moveTo(px, py) else coronaPath.lineTo(px, py)
        }
        drawPath(
            path = coronaPath,
            color = c3.copy(alpha = 0.16f + energy * 0.24f),
            style = Stroke(
                width = (minDim * 0.022f).coerceAtLeast(2.4f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )
        drawPath(
            path = coronaPath,
            color = cCore.copy(alpha = 0.30f + energy * 0.36f),
            style = Stroke(
                width = (minDim * 0.0065f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )

        // 6. 10 трёхмерных шелковых плазменных лент с глубиной по оси Z
        val ribbonCount = 10
        val steps = 76
        val bloomStrokeW = (minDim * 0.023f + energy * minDim * 0.012f).coerceAtLeast(2.8f)
        val coreStrokeW = (minDim * 0.008f + energy * minDim * 0.0045f).coerceAtLeast(1.2f)

        for (i in 0 until ribbonCount) {
            val tiltX = (i.toFloat() / ribbonCount) * PI.toFloat() + sin(phase * 0.45f + i) * 0.20f
            val tiltZ = (i * 0.58f) + phase * (0.26f + (i % 3) * 0.10f)
            val spin = phase * (0.82f + (i % 4) * 0.21f) + i * 1.07f
            val ribbonScale = 0.68f + (i % 4) * 0.085f

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
                    (0.035f + energy * 0.12f) * cos(a * 4f - phase * 3.2f)
                val rad = ribbonScale * harmonic

                val x0 = cos(a) * rad
                val y0 = sin(a) * cosTiltX * rad
                val z0 = sin(a) * sinTiltX * rad

                val x1 = x0 * cosSpin - z0 * sinSpin
                val z1 = x0 * sinSpin + z0 * cosSpin

                val x2 = x1 * cosTiltZ - y0 * sinTiltZ
                val y2 = x1 * sinTiltZ + y0 * cosTiltZ

                val perspective = 2.85f / (2.85f - z1 * 0.64f)
                val px = cx + x2 * sphereR * perspective
                val py = cy + y2 * sphereR * perspective
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            val ribbonColor = when (i % 3) {
                0 -> c1
                1 -> c2
                else -> c3
            }

            drawPath(
                path = path,
                color = ribbonColor.copy(alpha = 0.15f + energy * 0.11f),
                style = Stroke(width = bloomStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
            drawPath(
                path = path,
                color = ribbonColor.copy(alpha = 0.54f + (i % 2) * 0.20f),
                style = Stroke(width = coreStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
        }

        // 7. 24 орбитальные микрочастицы в 3D-пространстве
        val particleCount = 24
        for (p in 0 until particleCount) {
            val seed = p * 2.39996f
            val orbitSpeed = 0.42f + (p % 5) * 0.15f
            val theta = phase * orbitSpeed + seed
            val phi = sin(phase * 0.35f + p * 0.9f) * 1.18f + (p % 3 - 1) * 0.48f
            val dist = sphereR * (0.64f + (p % 4) * 0.14f + energy * 0.09f)

            val x3 = cos(theta) * cos(phi)
            val y3 = sin(phi)
            val z3 = sin(theta) * cos(phi)
            val depth = (z3 + 1.2f) / 2.2f
            val persp = 2.6f / (2.6f - z3 * 0.56f)

            val px = cx + x3 * dist * persp
            val py = cy + y3 * dist * persp
            val twinkle = 0.45f + 0.55f * sin(phase * 4.0f + p * 1.7f)
            val dotR = ((minDim * 0.0075f) + (p % 3) * (minDim * 0.003f)) * (0.6f + depth * 0.75f)
            val pColor = if (p % 2 == 0) cCore else c3

            drawCircle(
                color = pColor.copy(alpha = (0.30f + depth * 0.54f) * twinkle),
                radius = dotR.coerceAtLeast(1.1f),
                center = Offset(px, py),
                blendMode = BlendMode.Screen,
            )
        }

        // 8. Многослойное плазменное ядро в центре
        val coreOuterR = (sphereR * (0.66f + energy * 0.16f)).coerceAtLeast(2f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.98f),
                0.18f to cCore.copy(alpha = 0.90f),
                0.44f to c1.copy(alpha = 0.52f + energy * 0.18f),
                0.76f to c2.copy(alpha = 0.20f),
                1.0f to Color.Transparent,
                center = center,
                radius = coreOuterR,
            ),
            radius = coreOuterR,
            center = center,
            blendMode = BlendMode.Screen,
        )

        // 9. Стеклянный блик на верхней левой грани
        val highlightR = sphereR * 0.86f
        val hlTopLeft = Offset(cx - highlightR, cy - highlightR)
        val hlSize = Size(highlightR * 2f, highlightR * 2f)
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.36f),
                    cCore.copy(alpha = 0.12f),
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