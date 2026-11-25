package com.maks.music.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class AvatarMode {
    IDLE, SLEEP, LISTENING, SPEAKING, MUSIC, HAPPY, CURIOUS, ERROR
}

private data class AvatarPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val core: Color,
)

// ───────────────────────── Настройки голограммы ─────────────────────────
private const val NODE_COUNT = 150          // узлы сетки на сфере (100..220)
private const val CAM = 3.4f                // дистанция камеры: меньше = сильнее перспектива
private const val SCANLINE_ALPHA = 0.42f    // прозрачные строки развёртки (0 = выкл)
private const val ABERRATION = 1.0f         // множитель RGB-разноса (0 = выкл)
private const val GLITCH_GAIN = 1.0f        // множитель глитчей (0 = выкл)

private val TAU = (2.0 * PI).toFloat()

private val RING_RADIUS = floatArrayOf(1.065f, 1.22f, 1.36f)
private val RING_SPEED = floatArrayOf(0.30f, -0.55f, 0.42f)

// ───────────────────────── Вспомогательные структуры ─────────────────────────

private class SphereNet(val pts: FloatArray, val edges: IntArray)

/** Сфера Фибоначчи + связи с 3 ближайшими соседями → «нейросетевая» голографическая сетка. */
private fun buildSphereNet(n: Int): SphereNet {
    val pts = FloatArray(n * 3)
    val golden = PI.toFloat() * (3f - sqrt(5f))
    for (i in 0 until n) {
        val y = 1f - 2f * (i + 0.5f) / n
        val r = sqrt(1f - y * y)
        val th = golden * i
        pts[i * 3] = cos(th) * r
        pts[i * 3 + 1] = y
        pts[i * 3 + 2] = sin(th) * r
    }
    val k = 3
    val best = IntArray(k)
    val bestD = FloatArray(k)
    val seen = HashSet<Long>()
    val list = ArrayList<Int>()
    for (i in 0 until n) {
        for (t in 0 until k) {
            best[t] = -1
            bestD[t] = -2f
        }
        for (j in 0 until n) {
            if (j == i) continue
            val d = pts[i * 3] * pts[j * 3] +
                pts[i * 3 + 1] * pts[j * 3 + 1] +
                pts[i * 3 + 2] * pts[j * 3 + 2]
            var pos = -1
            for (t in 0 until k) {
                if (d > bestD[t]) {
                    pos = t
                    break
                }
            }
            if (pos >= 0) {
                for (t in k - 1 downTo pos + 1) {
                    bestD[t] = bestD[t - 1]
                    best[t] = best[t - 1]
                }
                bestD[pos] = d
                best[pos] = j
            }
        }
        for (t in 0 until k) {
            val j = best[t]
            if (j < 0) continue
            val a = min(i, j)
            val b = max(i, j)
            if (seen.add(a.toLong() * n + b)) {
                list.add(a)
                list.add(b)
            }
        }
    }
    return SphereNet(pts, list.toIntArray())
}

/** Поворот yaw (Y) → pitch (X, камера чуть сверху) → roll (Z). Результат пишется в out без аллокаций. */
private class Rot(yaw: Float, pitch: Float, roll: Float) {
    private val cyw = cos(yaw)
    private val syw = sin(yaw)
    private val cp = cos(pitch)
    private val sp = sin(pitch)
    private val cr = cos(roll)
    private val sr = sin(roll)

    fun apply(x: Float, y: Float, z: Float, out: FloatArray) {
        val x1 = x * cyw + z * syw
        val z1 = -x * syw + z * cyw
        val y2 = y * cp + z1 * sp
        val z2 = -y * sp + z1 * cp
        out[0] = x1 * cr - y2 * sr
        out[1] = x1 * sr + y2 * cr
        out[2] = z2
    }
}

/** Переиспользуемые Path — без аллокаций на каждый кадр. */
private class HoloPaths {
    val beam = Path()
    val wireBack = Path()
    val wireMid = Path()
    val wireFront = Path()
    val netBack = Path()
    val netFront = Path()
    val barsBack = Path()
    val barsFront = Path()
    val ringBack = Path()
    val ringFront = Path()
    val ringArc = Path()
    val tickBack = Path()
    val tickFront = Path()
    val ribbon = Path()
    val corona = Path()
    val scanlines = Path()

    fun resetScene() {
        wireBack.rewind(); wireMid.rewind(); wireFront.rewind()
        netBack.rewind(); netFront.rewind()
        barsBack.rewind(); barsFront.rewind()
    }
}

private fun hash01(n: Int): Float {
    var x = n * 374761393 + 668265263
    x = (x xor (x ushr 13)) * 1274126177
    x = x xor (x ushr 16)
    return (x and 0xFFFF) / 65535f
}

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Кладёт отрезок в один из трёх путей по глубине: дальняя / средняя / ближняя сторона. */
private fun addDepthSeg(
    x0: Float, y0: Float, x1: Float, y1: Float,
    zAvg: Float, thr: Float,
    back: Path, mid: Path, front: Path,
) {
    val p = if (zAvg < -thr) back else if (zAvg > thr) front else mid
    p.moveTo(x0, y0)
    p.lineTo(x1, y1)
}

// ───────────────────────── Композабл ─────────────────────────

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

    val currentSpeed by rememberUpdatedState(targetSpeed)
    val latestAudio by rememberUpdatedState(audioLevel.coerceIn(0f, 1f))
    val currentMode by rememberUpdatedState(mode)

    LaunchedEffect(Unit) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { frameNs ->
                if (lastNs != 0L) {
                    val dt = ((frameNs - lastNs) / 1_000_000_000f).coerceAtMost(0.05f)
                    phase += dt * currentSpeed

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

    // Параметры «характера» голограммы по режимам (плавные переходы)
    val holoAlpha by animateFloatAsState(
        if (mode == AvatarMode.SLEEP) 0.55f else 1f, tween(600), label = "holo_alpha",
    )
    val glitch by animateFloatAsState(
        when (mode) {
            AvatarMode.ERROR -> 1f
            AvatarMode.CURIOUS -> 0.12f
            AvatarMode.SPEAKING -> 0.10f
            else -> 0.04f
        },
        tween(400), label = "holo_glitch",
    )
    val hoverAmt by animateFloatAsState(
        when (mode) {
            AvatarMode.HAPPY -> 1.0f
            AvatarMode.MUSIC -> 0.7f
            AvatarMode.SLEEP -> 0.3f
            else -> 0.5f
        },
        tween(600), label = "holo_hover",
    )
    val curious by animateFloatAsState(
        if (mode == AvatarMode.CURIOUS) 1f else 0f, tween(500), label = "holo_curious",
    )

    val net = remember { buildSphereNet(NODE_COUNT) }
    val scratch = remember { FloatArray(NODE_COUNT * 3) }
    val v = remember { FloatArray(3) }
    val paths = remember { HoloPaths() }

    // Offscreen-слой нужен, чтобы BlendMode.DstOut (прозрачные строки развёртки) вырезал
    // пиксели аватара, а не пробивал дыры до окна.
    Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val w = size.width
        val h = size.height
        val minDim = size.minDimension
        if (minDim <= 4f) return@Canvas

        val t = phase
        val energy = smoothAudio.coerceIn(0f, 1f)
        val compact = minDim < 180f

        // ── Компактный режим (мини-кнопка в углу обложки): лёгкий и чёткий ──
        if (compact) {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val center = Offset(cx, cy)
            val breathe = sin(t * 1.9f) * 0.025f
            val sphereR = (minDim * 0.38f * (1f + energy * 0.08f + breathe)).coerceAtLeast(2f)
            val ga = holoAlpha.coerceIn(0f, 1f)
            fun al(x: Float): Float = (x * ga).coerceIn(0f, 1f)

            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to c1.copy(alpha = al(0.35f + energy * 0.20f)),
                    0.65f to c2.copy(alpha = al(0.16f)),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = minDim * 0.50f,
                ),
                radius = minDim * 0.50f,
                center = center,
            )

            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color(0xFF0B132B).copy(alpha = al(0.65f)),
                    0.75f to c1.copy(alpha = al(0.28f + energy * 0.14f)),
                    0.96f to c3.copy(alpha = al(0.55f + energy * 0.20f)),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = sphereR * 1.05f,
                ),
                radius = sphereR * 1.05f,
                center = center,
            )

            val ribbon = paths.ribbon
            val coreStrokeW = (minDim * 0.018f).coerceAtLeast(1.1f)
            val steps = 40
            for (i in 0 until 5) {
                val tiltX = (i.toFloat() / 5f) * PI.toFloat() + sin(t * 0.5f + i) * 0.2f
                val tiltZ = (i * 0.7f) + t * (0.35f + (i % 3) * 0.12f)
                val spin = t * (0.9f + (i % 3) * 0.22f) + i * 1.1f
                val ribbonScale = 0.68f + (i % 3) * 0.08f

                ribbon.rewind()
                val cosTiltX = cos(tiltX)
                val sinTiltX = sin(tiltX)
                val cosSpin = cos(spin)
                val sinSpin = sin(spin)
                val cosTiltZ = cos(tiltZ)
                val sinTiltZ = sin(tiltZ)

                for (s in 0..steps) {
                    val a = (s.toFloat() / steps) * TAU
                    val rad = ribbonScale * (1f + (0.04f + energy * 0.12f) * sin(a * 3f + spin))
                    val x0 = cos(a) * rad
                    val y0 = sin(a) * cosTiltX * rad
                    val z0 = sin(a) * sinTiltX * rad
                    val x1 = x0 * cosSpin - z0 * sinSpin
                    val z1 = x0 * sinSpin + z0 * cosSpin
                    val x2 = x1 * cosTiltZ - y0 * sinTiltZ
                    val y2 = x1 * sinTiltZ + y0 * cosTiltZ
                    val persp = 2.6f / (2.6f - z1 * 0.6f)
                    val px = cx + x2 * sphereR * persp
                    val py = cy + y2 * sphereR * persp
                    if (s == 0) ribbon.moveTo(px, py) else ribbon.lineTo(px, py)
                }

                val ribbonColor = when (i % 3) {
                    0 -> c1
                    1 -> c2
                    else -> c3
                }
                drawPath(
                    path = ribbon,
                    color = ribbonColor.copy(alpha = al(0.60f)),
                    style = Stroke(width = coreStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = BlendMode.Screen,
                )
            }

            val coreR = (sphereR * (0.58f + energy * 0.18f)).coerceAtLeast(2f)
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to Color.White.copy(alpha = al(0.95f)),
                    0.25f to cCore.copy(alpha = al(0.85f)),
                    0.60f to c1.copy(alpha = al(0.45f)),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = coreR,
                ),
                radius = coreR,
                center = center,
                blendMode = BlendMode.Screen,
            )
            return@Canvas
        }

        val rich = minDim >= 260f
        val glitchLevel = (glitch * GLITCH_GAIN).coerceIn(0f, 1f)

        // ── Мерцание проектора и глитчи ──
        val flickStep = floor(t * 14f).toInt()
        val dip = if (hash01(flickStep * 3 + 1) > 0.94f) 0.78f else 1f
        val shimmer = 0.965f + 0.035f * sin(t * 31f)
        val ga = (holoAlpha * dip * shimmer).coerceIn(0f, 1f)
        fun al(x: Float): Float = (x * ga).coerceIn(0f, 1f)

        val gRate = 3.1f
        val gSlot = floor(t * gRate).toInt()
        val gFrac = t * gRate - gSlot
        val glitchOn = gFrac < (0.06f + glitchLevel * 0.16f) &&
            hash01(gSlot * 7 + 3) < (0.10f + glitchLevel * 0.80f)
        val gDx = (hash01(gSlot * 13 + 1) - 0.5f) * minDim * (0.05f + glitchLevel * 0.07f)
        val gTop = h * (0.12f + 0.70f * hash01(gSlot * 5 + 2))
        val gBot = gTop + minDim * (0.02f + 0.07f * hash01(gSlot * 11 + 9))
        val ab = (minDim * (0.0022f + glitchLevel * 0.005f + energy * 0.0018f) +
            (if (glitchOn) minDim * 0.005f else 0f)) * ABERRATION

        // ── Геометрия сцены ──
        val cx = w * 0.5f
        val hover = sin(t * 1.25f) * minDim * (0.006f + hoverAmt * 0.012f)
        val cy = h * 0.44f + hover
        val center = Offset(cx, cy)
        val breathe = sin(t * 1.9f) * 0.02f
        val sphereR = (minDim * 0.265f * (1f + energy * 0.08f + breathe)).coerceAtLeast(2f)
        val baseY = h * 0.44f + minDim * 0.265f * 1.62f
        val baseRx = minDim * 0.265f * 1.32f
        val topY = h * 0.44f - minDim * 0.265f * 1.50f
        val beamBottomHalf = baseRx * 0.70f
        val beamTopHalf = minDim * 0.265f * 1.28f
        val beamSpan = baseY - topY
        val haloR = (sphereR * 1.9f).coerceAtMost(minDim * 0.52f)
        val maxSafeRadius = minDim * 0.49f

        val hair = (minDim * 0.0028f).coerceAtLeast(0.8f)
        val thin = (minDim * 0.0042f).coerceAtLeast(1f)

        // Сканирующая плоскость (идёт снизу вверх по лучу проектора)
        val scanU = (t * 0.22f) % 1f
        val scanY = baseY - scanU * beamSpan
        val scanFade = sin(scanU * PI.toFloat())

        // ── Вращение ──
        val yaw = t * 0.42f
        val pitch = 0.34f + sin(t * 0.37f) * 0.07f + curious * 0.10f
        val roll = sin(t * 0.29f) * 0.06f + curious * 0.20f * sin(t * 1.3f)
        val rot = Rot(yaw, pitch, roll)

        // 1. Ореол
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c1.copy(alpha = al(0.26f + energy * 0.16f)),
                0.50f to c2.copy(alpha = al(0.12f + energy * 0.08f)),
                0.80f to c3.copy(alpha = al(0.04f)),
                1.0f to Color.Transparent,
                center = center,
                radius = haloR,
            ),
            radius = haloR,
            center = center,
        )

        // 2. Луч проектора: конус, лучи, поднимающиеся частицы
        val beam = paths.beam
        beam.rewind()
        beam.moveTo(cx - beamBottomHalf, baseY)
        beam.lineTo(cx - beamTopHalf, topY)
        beam.lineTo(cx + beamTopHalf, topY)
        beam.lineTo(cx + beamBottomHalf, baseY)
        beam.close()
        drawPath(
            path = beam,
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.55f to c1.copy(alpha = al(0.07f + energy * 0.05f)),
                1.0f to c3.copy(alpha = al(0.30f + energy * 0.14f)),
                startY = topY,
                endY = baseY,
            ),
            blendMode = BlendMode.Screen,
        )

        val rayBrush = Brush.verticalGradient(
            0.0f to Color.Transparent,
            1.0f to c3.copy(alpha = 0.55f),
            startY = topY,
            endY = baseY,
        )
        for (r in 0 until 9) {
            val f = (r - 4) / 4f
            val flick = 0.35f + 0.65f * abs(sin(t * (1.1f + r * 0.23f) + r * 1.9f))
            drawLine(
                brush = rayBrush,
                start = Offset(cx + f * beamBottomHalf, baseY),
                end = Offset(cx + f * beamTopHalf, topY),
                strokeWidth = hair,
                alpha = al(0.30f * flick),
                blendMode = BlendMode.Screen,
            )
        }

        for (p in 0 until 22) {
            val u = (t * (0.07f + (p % 4) * 0.025f) + p * 0.173f) % 1f
            val half = mix(beamBottomHalf, beamTopHalf, u)
            val px = cx + sin(p * 2.31f + t * 0.7f) * half * 0.88f
            val py = baseY - u * beamSpan
            val tw = 0.5f + 0.5f * sin(t * 5f + p * 1.3f)
            drawCircle(
                color = cCore.copy(alpha = al(sin(u * PI.toFloat()) * (0.25f + 0.45f * tw))),
                radius = (minDim * (0.0035f + (p % 3) * 0.0015f)).coerceAtLeast(1f),
                center = Offset(px, py),
                blendMode = BlendMode.Screen,
            )
        }

        // 3. Платформа проектора (эллипсы через вертикальное сжатие)
        val basePivot = Offset(cx, baseY)
        scale(scaleX = 1f, scaleY = 0.22f, pivot = basePivot) {
            drawCircle(
                color = Color(0xFF03060F).copy(alpha = al(0.80f)),
                radius = baseRx * 1.06f,
                center = basePivot,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to cCore.copy(alpha = al(0.80f)),
                    0.18f to c3.copy(alpha = al(0.50f + energy * 0.20f)),
                    0.55f to c1.copy(alpha = al(0.16f)),
                    1.0f to Color.Transparent,
                    center = basePivot,
                    radius = baseRx * 1.35f,
                ),
                radius = baseRx * 1.35f,
                center = basePivot,
                blendMode = BlendMode.Screen,
            )
            val ringW = (minDim * 0.011f).coerceAtLeast(1.4f)
            drawCircle(
                color = c3.copy(alpha = al(0.60f)),
                radius = baseRx,
                center = basePivot,
                style = Stroke(width = ringW),
                blendMode = BlendMode.Screen,
            )
            drawCircle(
                color = c1.copy(alpha = al(0.32f)),
                radius = baseRx * 0.80f,
                center = basePivot,
                style = Stroke(width = ringW),
                blendMode = BlendMode.Screen,
            )
            drawCircle(
                color = c2.copy(alpha = al(0.45f)),
                radius = baseRx * 0.56f,
                center = basePivot,
                style = Stroke(width = ringW),
                blendMode = BlendMode.Screen,
            )
            drawArc(
                color = c3.copy(alpha = al(0.85f)),
                startAngle = t * 55f,
                sweepAngle = 52f,
                useCenter = false,
                topLeft = Offset(cx - baseRx * 0.80f, baseY - baseRx * 0.80f),
                size = Size(baseRx * 1.60f, baseRx * 1.60f),
                style = Stroke(width = ringW * 1.8f, cap = StrokeCap.Round),
                blendMode = BlendMode.Screen,
            )
            drawArc(
                color = c2.copy(alpha = al(0.80f)),
                startAngle = -t * 70f + 120f,
                sweepAngle = 36f,
                useCenter = false,
                topLeft = Offset(cx - baseRx * 0.56f, baseY - baseRx * 0.56f),
                size = Size(baseRx * 1.12f, baseRx * 1.12f),
                style = Stroke(width = ringW * 1.8f, cap = StrokeCap.Round),
                blendMode = BlendMode.Screen,
            )
            if (rich) {
                for (k in 0 until 60) {
                    val ang = k * (TAU / 60f)
                    val glow = cos(ang - t * 1.2f).coerceAtLeast(0f).pow(6f)
                    val r1 = baseRx * 1.05f
                    val r2 = baseRx * (if (k % 5 == 0) 1.16f else 1.10f)
                    drawLine(
                        color = c3.copy(alpha = al(0.16f + 0.55f * glow)),
                        start = Offset(cx + cos(ang) * r1, baseY + sin(ang) * r1),
                        end = Offset(cx + cos(ang) * r2, baseY + sin(ang) * r2),
                        strokeWidth = hair * 1.2f,
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        }

        // 4. Прозрачная стеклянная сфера, подсветка снизу от проектора, френелевский обод
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color(0xFF0B132B).copy(alpha = al(0.55f)),
                0.62f to Color(0xFF060A18).copy(alpha = al(0.50f)),
                0.86f to c1.copy(alpha = al(0.22f + energy * 0.12f)),
                0.97f to c3.copy(alpha = al(0.42f + energy * 0.18f)),
                1.0f to Color.Transparent,
                center = center,
                radius = sphereR * 1.04f,
            ),
            radius = sphereR * 1.04f,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c3.copy(alpha = al(0.34f + energy * 0.20f)),
                0.55f to c1.copy(alpha = al(0.10f)),
                1.0f to Color.Transparent,
                center = Offset(cx, cy + sphereR * 0.90f),
                radius = sphereR * 1.30f,
            ),
            radius = sphereR * 1.02f,
            center = center,
            blendMode = BlendMode.Screen,
        )
        drawCircle(
            color = c3.copy(alpha = al(0.38f + energy * 0.2f)),
            radius = sphereR * 1.005f,
            center = center,
            style = Stroke(width = hair * 1.4f),
            blendMode = BlendMode.Screen,
        )

        // 5. Геометрия: широты/меридианы, сетка узлов, 3D-эквалайзер
        paths.resetScene()
        val wob = 0.010f + energy * 0.045f

        val latLines = 7
        val latSteps = 56
        for (li in 1..latLines) {
            val lat = -PI.toFloat() * 0.5f + li * (PI.toFloat() / (latLines + 1))
            val lyy = sin(lat)
            val lr = cos(lat)
            var px0 = 0f
            var py0 = 0f
            var pz0 = 0f
            for (s in 0..latSteps) {
                val a = s.toFloat() / latSteps * TAU
                val k = 1f + wob * sin(a * 5f + lat * 7f + t * 3.2f)
                rot.apply(cos(a) * lr * k, lyy * k, sin(a) * lr * k, v)
                val pf = CAM / (CAM - v[2])
                val sx = cx + v[0] * sphereR * pf
                val sy = cy + v[1] * sphereR * pf
                if (s > 0) {
                    addDepthSeg(
                        px0, py0, sx, sy, (pz0 + v[2]) * 0.5f, 0.30f,
                        paths.wireBack, paths.wireMid, paths.wireFront,
                    )
                }
                px0 = sx
                py0 = sy
                pz0 = v[2]
            }
        }

        val lonLines = 12
        val lonSteps = 30
        for (mi in 0 until lonLines) {
            val lon = mi * (TAU / lonLines)
            val cl = cos(lon)
            val sl = sin(lon)
            var px0 = 0f
            var py0 = 0f
            var pz0 = 0f
            for (s in 0..lonSteps) {
                val lat = -PI.toFloat() * 0.5f + s.toFloat() / lonSteps * PI.toFloat()
                val k = 1f + wob * sin(lat * 6f + lon * 2f + t * 3.0f)
                val crr = cos(lat)
                rot.apply(crr * cl * k, sin(lat) * k, crr * sl * k, v)
                val pf = CAM / (CAM - v[2])
                val sx = cx + v[0] * sphereR * pf
                val sy = cy + v[1] * sphereR * pf
                if (s > 0) {
                    addDepthSeg(
                        px0, py0, sx, sy, (pz0 + v[2]) * 0.5f, 0.30f,
                        paths.wireBack, paths.wireMid, paths.wireFront,
                    )
                }
                px0 = sx
                py0 = sy
                pz0 = v[2]
            }
        }

        // Сетка узлов
        for (i in 0 until NODE_COUNT) {
            val k = 1.02f + wob * 0.8f * sin(i * 1.7f + t * 2.6f)
            rot.apply(net.pts[i * 3] * k, net.pts[i * 3 + 1] * k, net.pts[i * 3 + 2] * k, v)
            val pf = CAM / (CAM - v[2])
            scratch[i * 3] = cx + v[0] * sphereR * pf
            scratch[i * 3 + 1] = cy + v[1] * sphereR * pf
            scratch[i * 3 + 2] = v[2]
        }
        var ei = 0
        while (ei < net.edges.size) {
            val ia = net.edges[ei]
            val ib = net.edges[ei + 1]
            addDepthSeg(
                scratch[ia * 3], scratch[ia * 3 + 1], scratch[ib * 3], scratch[ib * 3 + 1],
                (scratch[ia * 3 + 2] + scratch[ib * 3 + 2]) * 0.5f, 0f,
                paths.netBack, paths.netFront, paths.netFront,
            )
            ei += 2
        }

        // 3D-эквалайзер вокруг экватора
        val barRot = Rot(t * 0.30f, pitch, roll)
        val bars = 72
        for (b in 0 until bars) {
            val ang = b.toFloat() / bars * TAU
            val spec = 0.50f * abs(sin(ang * 3f + t * 2.1f)) +
                0.30f * abs(sin(ang * 7f - t * 3.3f)) +
                0.20f * abs(sin(ang * 13f + t * 5.1f))
            val amp = 0.018f + 0.012f * spec + energy * 0.24f * spec
            val r0 = 1.07f
            val r1 = r0 + amp
            val ca = cos(ang)
            val sa = sin(ang)
            barRot.apply(ca * r0, 0f, sa * r0, v)
            val pf0 = CAM / (CAM - v[2])
            val x0 = cx + v[0] * sphereR * pf0
            val y0 = cy + v[1] * sphereR * pf0
            val z0 = v[2]
            barRot.apply(ca * r1, 0f, sa * r1, v)
            val pf1 = CAM / (CAM - v[2])
            val x1 = cx + v[0] * sphereR * pf1
            val y1 = cy + v[1] * sphereR * pf1
            val target = if (z0 >= 0f) paths.barsFront else paths.barsBack
            target.moveTo(x0, y0)
            target.lineTo(x1, y1)
        }

        // Рисуем дальнюю сторону
        drawPath(
            path = paths.wireBack,
            color = c1.copy(alpha = al(0.13f + energy * 0.05f)),
            style = Stroke(width = hair),
            blendMode = BlendMode.Screen,
        )
        drawPath(
            path = paths.netBack,
            color = c2.copy(alpha = al(0.20f + energy * 0.08f)),
            style = Stroke(width = hair),
            blendMode = BlendMode.Screen,
        )
        val barW = (minDim * 0.0055f).coerceAtLeast(1.4f)
        drawPath(
            path = paths.barsBack,
            color = c1.copy(alpha = al(0.30f)),
            style = Stroke(width = barW, cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )

        val scanBand = minDim * 0.05f
        fun drawNodes(front: Boolean) {
            for (i in 0 until NODE_COUNT) {
                val z = scratch[i * 3 + 2]
                if ((z >= 0f) != front) continue
                val sx = scratch[i * 3]
                val sy = scratch[i * 3 + 1]
                val depth = (z + 1f) * 0.5f
                val near = (1f - abs(sy - scanY) / scanBand).coerceIn(0f, 1f)
                val pulse = 0.5f + 0.5f * sin(t * 2.6f - i * 0.55f)
                val glow = 0.25f + 0.35f * pulse * (0.4f + energy) + near * 0.6f
                val r = minDim * (0.0045f + 0.0035f * depth + near * 0.004f + energy * 0.002f * pulse)
                drawCircle(
                    color = (if (i % 5 == 0) cCore else c3)
                        .copy(alpha = al(glow * (0.35f + depth * 0.65f))),
                    radius = r.coerceAtLeast(1f),
                    center = Offset(sx, sy),
                    blendMode = BlendMode.Screen,
                )
            }
        }
        drawNodes(front = false)

        // 6. Три гироскопических кольца с насечками и бегущей дугой
        for (ri in 0 until 3) {
            val rr = RING_RADIUS[ri]
            val rPitch = when (ri) {
                0 -> pitch
                1 -> 1.00f + sin(t * 0.21f) * 0.12f
                else -> 0.62f + sin(t * 0.17f + 1f) * 0.10f
            }
            val rRoll = when (ri) {
                0 -> roll
                1 -> 0.95f + sin(t * 0.13f) * 0.10f
                else -> -1.05f + sin(t * 0.11f) * 0.10f
            }
            val col = when (ri) {
                0 -> c3
                1 -> c2
                else -> c1
            }
            val rotR = Rot(t * RING_SPEED[ri], rPitch, rRoll)

            paths.ringBack.rewind()
            paths.ringFront.rewind()
            var qx = 0f
            var qy = 0f
            var qz = 0f
            val ringSteps = 72
            for (s in 0..ringSteps) {
                val a = s.toFloat() / ringSteps * TAU
                rotR.apply(cos(a) * rr, 0f, sin(a) * rr, v)
                val pf = CAM / (CAM - v[2])
                val sx = cx + v[0] * sphereR * pf
                val sy = cy + v[1] * sphereR * pf
                if (s > 0) {
                    addDepthSeg(
                        qx, qy, sx, sy, (qz + v[2]) * 0.5f, 0f,
                        paths.ringBack, paths.ringFront, paths.ringFront,
                    )
                }
                qx = sx
                qy = sy
                qz = v[2]
            }
            drawPath(
                path = paths.ringBack,
                color = col.copy(alpha = al(0.12f)),
                style = Stroke(width = hair),
                blendMode = BlendMode.Screen,
            )
            drawPath(
                path = paths.ringFront,
                color = col.copy(alpha = al(0.34f)),
                style = Stroke(width = hair * 1.3f),
                blendMode = BlendMode.Screen,
            )

            if (rich) {
                paths.tickBack.rewind()
                paths.tickFront.rewind()
                for (k in 0 until 36) {
                    val ang = k * (TAU / 36f)
                    val rOut = rr * (if (k % 9 == 0) 1.06f else 1.035f)
                    val ca = cos(ang)
                    val sa = sin(ang)
                    rotR.apply(ca * rr, 0f, sa * rr, v)
                    val pfA = CAM / (CAM - v[2])
                    val xa = cx + v[0] * sphereR * pfA
                    val ya = cy + v[1] * sphereR * pfA
                    val za = v[2]
                    rotR.apply(ca * rOut, 0f, sa * rOut, v)
                    val pfB = CAM / (CAM - v[2])
                    val target = if (za >= 0f) paths.tickFront else paths.tickBack
                    target.moveTo(xa, ya)
                    target.lineTo(cx + v[0] * sphereR * pfB, cy + v[1] * sphereR * pfB)
                }
                drawPath(
                    path = paths.tickBack,
                    color = col.copy(alpha = al(0.14f)),
                    style = Stroke(width = hair),
                    blendMode = BlendMode.Screen,
                )
                drawPath(
                    path = paths.tickFront,
                    color = col.copy(alpha = al(0.50f)),
                    style = Stroke(width = hair),
                    blendMode = BlendMode.Screen,
                )
            }

            // Бегущая яркая дуга + светящийся узел на её конце
            paths.ringArc.rewind()
            val arcLen = 1.25f
            val arcSteps = 22
            var hx = 0f
            var hy = 0f
            var hz = 0f
            for (s in 0..arcSteps) {
                val a = s.toFloat() / arcSteps * arcLen
                rotR.apply(cos(a) * rr, 0f, sin(a) * rr, v)
                val pf = CAM / (CAM - v[2])
                hx = cx + v[0] * sphereR * pf
                hy = cy + v[1] * sphereR * pf
                hz = v[2]
                if (s == 0) paths.ringArc.moveTo(hx, hy) else paths.ringArc.lineTo(hx, hy)
            }
            val arcFade = 0.55f + 0.45f * (hz + 1f) * 0.5f
            drawPath(
                path = paths.ringArc,
                color = col.copy(alpha = al(0.55f * arcFade + energy * 0.20f)),
                style = Stroke(width = thin * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
            val nodeGlowR = (minDim * 0.030f).coerceAtLeast(3f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cCore.copy(alpha = al(0.95f * arcFade)),
                        col.copy(alpha = al(0.50f * arcFade)),
                        Color.Transparent,
                    ),
                    center = Offset(hx, hy),
                    radius = nodeGlowR,
                ),
                radius = nodeGlowR,
                center = Offset(hx, hy),
                blendMode = BlendMode.Screen,
            )
        }

        // 7. Аудио-реактивная корона по силуэту
        val corona = paths.corona
        corona.rewind()
        val coronaSteps = 80
        for (s in 0..coronaSteps) {
            val a = (s.toFloat() / coronaSteps) * TAU
            val wave1 = sin(a * 6f + t * 3.6f) * (0.022f + energy * 0.085f)
            val wave2 = cos(a * 3f - t * 2.4f) * (0.016f + energy * 0.050f)
            val rr = (sphereR * (0.95f + wave1 + wave2)).coerceAtMost(maxSafeRadius * 0.94f)
            val px = cx + cos(a) * rr
            val py = cy + sin(a) * rr
            if (s == 0) corona.moveTo(px, py) else corona.lineTo(px, py)
        }
        drawPath(
            path = corona,
            color = c3.copy(alpha = al(0.12f + energy * 0.22f)),
            style = Stroke(
                width = (minDim * 0.018f).coerceAtLeast(2.2f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )
        drawPath(
            path = corona,
            color = cCore.copy(alpha = al(0.26f + energy * 0.34f)),
            style = Stroke(
                width = (minDim * 0.0055f).coerceAtLeast(1f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Screen,
        )

        // 8. Плазменные ленты внутри сферы (фирменный элемент)
        val ribbonCount = 8
        val steps = 76
        val bloomStrokeW = (minDim * 0.020f + energy * minDim * 0.010f).coerceAtLeast(2.6f)
        val coreStrokeW = (minDim * 0.0075f + energy * minDim * 0.0040f).coerceAtLeast(1.1f)
        val ribbon = paths.ribbon

        for (i in 0 until ribbonCount) {
            val tiltX = (i.toFloat() / ribbonCount) * PI.toFloat() + sin(t * 0.45f + i) * 0.20f
            val tiltZ = (i * 0.58f) + t * (0.26f + (i % 3) * 0.10f)
            val spin = t * (0.82f + (i % 4) * 0.21f) + i * 1.07f
            val ribbonScale = 0.60f + (i % 4) * 0.075f

            ribbon.rewind()
            val cosTiltX = cos(tiltX)
            val sinTiltX = sin(tiltX)
            val cosSpin = cos(spin)
            val sinSpin = sin(spin)
            val cosTiltZ = cos(tiltZ)
            val sinTiltZ = sin(tiltZ)

            for (s in 0..steps) {
                val a = (s.toFloat() / steps) * TAU
                val harmonic = 1f +
                    0.075f * sin(a * 3f + spin * 1.4f + i) +
                    (0.035f + energy * 0.12f) * cos(a * 4f - t * 3.2f)
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
                if (s == 0) ribbon.moveTo(px, py) else ribbon.lineTo(px, py)
            }

            val ribbonColor = when (i % 3) {
                0 -> c1
                1 -> c2
                else -> c3
            }

            drawPath(
                path = ribbon,
                color = ribbonColor.copy(alpha = al(0.10f + energy * 0.08f)),
                style = Stroke(width = bloomStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
            drawPath(
                path = ribbon,
                color = ribbonColor.copy(alpha = al(0.46f + (i % 2) * 0.16f)),
                style = Stroke(width = coreStrokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = BlendMode.Screen,
            )
        }

        // 9. Орбитальные микрочастицы
        val particleCount = 24
        for (p in 0 until particleCount) {
            val seed = p * 2.39996f
            val orbitSpeed = 0.42f + (p % 5) * 0.15f
            val theta = t * orbitSpeed + seed
            val phi = sin(t * 0.35f + p * 0.9f) * 1.18f + (p % 3 - 1) * 0.48f
            val dist = sphereR * (0.64f + (p % 4) * 0.14f + energy * 0.09f)

            val x3 = cos(theta) * cos(phi)
            val y3 = sin(phi)
            val z3 = sin(theta) * cos(phi)
            val depth = (z3 + 1.2f) / 2.2f
            val persp = 2.6f / (2.6f - z3 * 0.56f)

            val px = cx + x3 * dist * persp
            val py = cy + y3 * dist * persp
            val twinkle = 0.45f + 0.55f * sin(t * 4.0f + p * 1.7f)
            val dotR = ((minDim * 0.0075f) + (p % 3) * (minDim * 0.003f)) * (0.6f + depth * 0.75f)
            val pColor = if (p % 2 == 0) cCore else c3

            drawCircle(
                color = pColor.copy(alpha = al((0.30f + depth * 0.54f) * twinkle)),
                radius = dotR.coerceAtLeast(1.1f),
                center = Offset(px, py),
                blendMode = BlendMode.Screen,
            )
        }

        // 10. Плазменное ядро
        val coreOuterR = (sphereR * (0.60f + energy * 0.16f)).coerceAtLeast(2f)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = al(0.92f)),
                0.18f to cCore.copy(alpha = al(0.86f)),
                0.44f to c1.copy(alpha = al(0.50f + energy * 0.18f)),
                0.76f to c2.copy(alpha = al(0.18f)),
                1.0f to Color.Transparent,
                center = center,
                radius = coreOuterR,
            ),
            radius = coreOuterR,
            center = center,
            blendMode = BlendMode.Screen,
        )

        // 11. Ближняя сторона: сетка, провода (с RGB-разносом), эквалайзер, узлы
        fun wireLayer(path: Path, alpha: Float, width: Float) {
            if (ab > 0.05f) {
                translate(left = -ab) {
                    drawPath(
                        path = path,
                        color = Color(0xFFFF2D55).copy(alpha = al(alpha * 0.55f)),
                        style = Stroke(width = width),
                        blendMode = BlendMode.Screen,
                    )
                }
                translate(left = ab) {
                    drawPath(
                        path = path,
                        color = Color(0xFF2D7BFF).copy(alpha = al(alpha * 0.55f)),
                        style = Stroke(width = width),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
            drawPath(
                path = path,
                color = c3.copy(alpha = al(alpha)),
                style = Stroke(width = width),
                blendMode = BlendMode.Screen,
            )
        }
        wireLayer(paths.wireMid, 0.24f + energy * 0.08f, hair)
        wireLayer(paths.wireFront, 0.46f + energy * 0.14f, hair * 1.25f)
        wireLayer(paths.netFront, 0.40f + energy * 0.14f, hair * 1.15f)

        drawPath(
            path = paths.barsFront,
            color = c3.copy(alpha = al(0.16f + energy * 0.10f)),
            style = Stroke(width = barW * 2.6f, cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )
        drawPath(
            path = paths.barsFront,
            color = cCore.copy(alpha = al(0.55f + energy * 0.25f)),
            style = Stroke(width = barW, cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )
        drawNodes(front = true)

        // 12. Стеклянный блик
        val highlightR = sphereR * 0.86f
        drawArc(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = al(0.36f)),
                    cCore.copy(alpha = al(0.12f)),
                    Color.Transparent,
                ),
                start = Offset(cx - highlightR, cy - highlightR),
                end = Offset(cx, cy),
            ),
            startAngle = 205f,
            sweepAngle = 58f,
            useCenter = false,
            topLeft = Offset(cx - highlightR, cy - highlightR),
            size = Size(highlightR * 2f, highlightR * 2f),
            style = Stroke(width = (minDim * 0.011f).coerceAtLeast(1.5f), cap = StrokeCap.Round),
            blendMode = BlendMode.Screen,
        )

        // 13. Сканирующая плоскость: полоса по лучу + яркий срез на сфере
        val beamHalfAtScan = mix(beamBottomHalf, beamTopHalf, scanU)
        val bandH = minDim * 0.035f
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.5f to c3.copy(alpha = al(0.16f * scanFade)),
                1.0f to Color.Transparent,
                startY = scanY - bandH,
                endY = scanY + bandH,
            ),
            topLeft = Offset(cx - beamHalfAtScan, scanY - bandH),
            size = Size(beamHalfAtScan * 2f, bandH * 2f),
            blendMode = BlendMode.Screen,
        )
        drawLine(
            color = c3.copy(alpha = al(0.45f * scanFade)),
            start = Offset(cx - beamHalfAtScan, scanY),
            end = Offset(cx + beamHalfAtScan, scanY),
            strokeWidth = thin,
            blendMode = BlendMode.Screen,
        )
        val scanDy = scanY - cy
        if (abs(scanDy) < sphereR) {
            val chord = sqrt(sphereR * sphereR - scanDy * scanDy)
            drawLine(
                color = cCore.copy(alpha = al(0.60f)),
                start = Offset(cx - chord, scanY),
                end = Offset(cx + chord, scanY),
                strokeWidth = thin * 1.4f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Screen,
            )
        }

        // 14. Глитч: смещённая полоса с дублем проводов
        if (glitchOn) {
            clipRect(left = 0f, top = gTop, right = w, bottom = gBot) {
                translate(left = gDx) {
                    drawPath(
                        path = paths.wireFront,
                        color = cCore.copy(alpha = al(0.65f)),
                        style = Stroke(width = thin),
                        blendMode = BlendMode.Screen,
                    )
                    drawPath(
                        path = paths.netFront,
                        color = c3.copy(alpha = al(0.70f)),
                        style = Stroke(width = thin),
                        blendMode = BlendMode.Screen,
                    )
                    drawPath(
                        path = paths.barsFront,
                        color = c3.copy(alpha = al(0.70f)),
                        style = Stroke(width = barW, cap = StrokeCap.Round),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
            drawRect(
                color = c3.copy(alpha = al(0.10f)),
                topLeft = Offset(0f, gTop),
                size = Size(w, gBot - gTop),
                blendMode = BlendMode.Screen,
            )
        }

        // 15. HUD: уголки, вертикальные линейки со «следящей» подсветкой, сегментный индикатор
        if (rich) {
            val inset = minDim * 0.045f
            val len = minDim * 0.07f
            val hudColor = c1.copy(alpha = al(0.40f))
            val hudW = hair * 1.4f
            for (corner in 0 until 4) {
                val bx = if ((corner and 1) == 0) inset else w - inset
                val by = if ((corner shr 1) == 0) inset else h - inset
                val ddx = if ((corner and 1) == 0) len else -len
                val ddy = if ((corner shr 1) == 0) len else -len
                drawLine(
                    color = hudColor,
                    start = Offset(bx, by),
                    end = Offset(bx + ddx, by),
                    strokeWidth = hudW,
                    blendMode = BlendMode.Screen,
                )
                drawLine(
                    color = hudColor,
                    start = Offset(bx, by),
                    end = Offset(bx, by + ddy),
                    strokeWidth = hudW,
                    blendMode = BlendMode.Screen,
                )
            }

            val rulerX = minDim * 0.455f
            val rulerTicks = 26
            for (side in 0..1) {
                val rx = if (side == 0) cx - rulerX else cx + rulerX
                val dirX = if (side == 0) 1f else -1f
                for (k in 0..rulerTicks) {
                    val ty = mix(topY, baseY, k.toFloat() / rulerTicks)
                    val major = k % 5 == 0
                    val near = (1f - abs(ty - scanY) / (minDim * 0.07f)).coerceIn(0f, 1f)
                    val tl = minDim * (if (major) 0.020f else 0.011f) * (1f + near * 0.8f)
                    drawLine(
                        color = c3.copy(
                            alpha = al(0.20f + near * 0.60f + (if (major) 0.12f else 0f)),
                        ),
                        start = Offset(rx, ty),
                        end = Offset(rx + dirX * tl, ty),
                        strokeWidth = hair,
                        blendMode = BlendMode.Screen,
                    )
                }
            }

            val segs = 14
            val lit = energy * segs
            val meterX = cx + minDim * 0.385f
            val segH = minDim * 0.017f
            val segGap = minDim * 0.006f
            for (sIdx in 0 until segs) {
                val segY = baseY - (sIdx + 1) * (segH + segGap)
                val on = (lit - sIdx).coerceIn(0f, 1f)
                val segColor = if (sIdx >= segs - 3) cCore else c3
                drawRect(
                    color = segColor.copy(alpha = al(0.10f + on * 0.70f)),
                    topLeft = Offset(meterX, segY),
                    size = Size(minDim * 0.014f, segH),
                    blendMode = BlendMode.Screen,
                )
            }
        }

        // 16. Строки развёртки (вырезают прозрачные линии) + медленная «шторка»
        if (SCANLINE_ALPHA > 0f) {
            val sl = paths.scanlines
            sl.rewind()
            val lineGap = (minDim * 0.0085f).coerceAtLeast(2.6f)
            var sy = (t * 14f) % lineGap
            while (sy < h) {
                sl.moveTo(0f, sy)
                sl.lineTo(w, sy)
                sy += lineGap
            }
            drawPath(
                path = sl,
                color = Color.Black.copy(alpha = SCANLINE_ALPHA),
                style = Stroke(width = lineGap * 0.40f),
                blendMode = BlendMode.DstOut,
            )

            val shutterH = minDim * 0.16f
            val shutterY = ((t * 0.09f) % 1f) * (h + shutterH) - shutterH * 0.5f
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.5f to Color.Black.copy(alpha = 0.30f),
                    1.0f to Color.Transparent,
                    startY = shutterY - shutterH * 0.5f,
                    endY = shutterY + shutterH * 0.5f,
                ),
                topLeft = Offset(0f, shutterY - shutterH * 0.5f),
                size = Size(w, shutterH),
                blendMode = BlendMode.DstOut,
            )
        }
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