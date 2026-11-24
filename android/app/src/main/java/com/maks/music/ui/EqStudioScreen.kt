package com.maks.music.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.media.EqualizerController
import com.maks.music.media.PlayerUiState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Полноэкранный аудиопроцессор «EQ Studio» в стиле Midnight Obsidian + Violet-Cyan.
 */
@Composable
fun EqStudioTab(
    player: PlayerUiState,
    accent: Color,
    onToggleEqEnabled: (Boolean) -> Unit,
    onBandGainChange: (bandIndex: Int, db: Float) -> Unit,
    onSelectPreset: (Int) -> Unit,
    onToggleBassBoost: (Boolean) -> Unit,
    onBalanceChange: (Float) -> Unit,
    onSelectSleepTimer: (Int?) -> Unit,
    onSelectCrossfade: (Float) -> Unit = {},
    onExportPreferencesUri: (Uri) -> Unit = {},
    onImportPreferencesUri: (Uri) -> Unit = {},
) {
    val eqEnabled = player.eqEnabled
    val bands = player.eqBandsDb

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) onExportPreferencesUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportPreferencesUri(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 1. Заголовок EQ Studio + главный переключатель
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "EQ Studio",
                    color = MaksColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    text = "Тонкая настройка звука",
                    color = MaksColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
            NeonPillSwitch(
                checked = eqEnabled,
                onCheckedChange = onToggleEqEnabled,
            )
        }

        // 2. Пятиполосные вертикальные фейдеры в стеклянной консоли
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaksColors.SpaceSurface.copy(alpha = 0.92f),
                            MaksColors.SpaceSurfaceRecessed.copy(alpha = 0.96f),
                        )
                    )
                )
                .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 18.dp)
                .alpha(if (eqEnabled) 1f else 0.45f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EqualizerController.BAND_LABELS.forEachIndexed { idx, freqLabel ->
                    val db = bands.getOrElse(idx) { 0f }
                    VerticalEqFader(
                        freqLabel = freqLabel,
                        valueDb = db,
                        enabled = eqEnabled,
                        accent = accent,
                        onValueChange = { newDb -> onBandGainChange(idx, newDb) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 3. Пресеты
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Пресеты",
                    color = MaksColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (player.eqPresetIndex < 0) "Свой" else EqualizerController.PRESETS[player.eqPresetIndex].label,
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                EqualizerController.PRESETS.forEachIndexed { idx, preset ->
                    val selected = player.eqPresetIndex == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) MaksColors.VioletAccent.copy(alpha = 0.22f)
                                else MaksColors.SpaceSurface
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) MaksColors.LavenderGlow else MaksColors.SpaceBorder,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { onSelectPreset(idx) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = preset.label,
                            color = if (selected) MaksColors.TextPrimary else MaksColors.TextMuted,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // 4. Карточка Bass Boost
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaksColors.SpaceSurface)
                .border(
                    1.dp,
                    if (player.bassBoostEnabled) MaksColors.SpaceBorderGlow else MaksColors.SpaceBorder,
                    RoundedCornerShape(20.dp),
                )
                .clickable { onToggleBassBoost(!player.bassBoostEnabled) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                MaksColors.VioletAccent.copy(alpha = if (player.bassBoostEnabled) 0.42f else 0.16f),
                                MaksColors.SpaceSurfaceElevated,
                            )
                        )
                    )
                    .border(
                        1.dp,
                        if (player.bassBoostEnabled) MaksColors.LavenderGlow.copy(alpha = 0.6f) else MaksColors.SpaceBorder,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = if (player.bassBoostEnabled) MaksColors.CyanAccent else MaksColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Bass Boost",
                    color = MaksColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Усиление глубины низких частот",
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }

            NeonPillSwitch(
                checked = player.bassBoostEnabled,
                onCheckedChange = onToggleBassBoost,
            )
        }

        // 5. Баланс L / R
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaksColors.SpaceSurface)
                .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Баланс",
                color = MaksColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            StereoBalanceSlider(
                balance = player.stereoBalance,
                accent = accent,
                onBalanceChange = onBalanceChange,
            )

            val balPct = (player.stereoBalance * 100f).roundToInt()
            val balLabel = when {
                balPct == 0 -> "0"
                balPct < 0 -> "L ${abs(balPct)}"
                else -> "R $balPct"
            }
            Text(
                text = balLabel,
                color = MaksColors.CyanAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { onBalanceChange(0f) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }

        // 6. Кроссфейд между треками (1.5–3 сек)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaksColors.CyanAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Плавный переход (Кроссфейд)",
                    color = MaksColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0f to "Выкл", 1.5f to "1.5 с", 2.0f to "2.0 с", 3.0f to "3.0 с").forEach { (sec, label) ->
                    val active = abs(player.crossfadeSec - sec) < 0.05f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) MaksColors.VioletAccent.copy(alpha = 0.22f) else MaksColors.SpaceSurface)
                            .border(
                                1.dp,
                                if (active) MaksColors.LavenderGlow else MaksColors.SpaceBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelectCrossfade(sec) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (active) MaksColors.TextPrimary else MaksColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // 7. Таймер сна
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bedtime,
                    contentDescription = null,
                    tint = MaksColors.LavenderGlow,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Таймер сна",
                    color = MaksColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(null to "Выкл", 15 to "15 мин", 30 to "30 мин", 60 to "60 мин").forEach { (mins, label) ->
                    val active = player.sleepTimerMinutesLeft == mins
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) MaksColors.VioletAccent.copy(alpha = 0.22f) else MaksColors.SpaceSurface)
                            .border(
                                1.dp,
                                if (active) MaksColors.LavenderGlow else MaksColors.SpaceBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelectSleepTimer(mins) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (active) MaksColors.TextPrimary else MaksColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // 8. Экспорт / Импорт лайков и дизлайков (JSON)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaksColors.SpaceSurface)
                .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "База лайков и дизлайков (JSON)",
                color = MaksColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Совместимо с liked_tracks.json десктопного Макса",
                color = MaksColors.TextMuted,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.SpaceSurfaceElevated)
                        .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(12.dp))
                        .clickable { exportLauncher.launch("maks_preferences.json") }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Upload,
                        contentDescription = null,
                        tint = MaksColors.CyanAccent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Экспорт", color = MaksColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.SpaceSurfaceElevated)
                        .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(12.dp))
                        .clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaksColors.LavenderGlow,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Импорт", color = MaksColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun VerticalEqFader(
    freqLabel: String,
    valueDb: Float,
    enabled: Boolean,
    accent: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestEnabled by rememberUpdatedState(enabled)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = freqLabel,
            color = MaksColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )

        Canvas(
            modifier = Modifier
                .width(48.dp)
                .height(196.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (!latestEnabled || size.height <= 0) return@detectTapGestures
                        val norm = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                        val db = ((norm * 24f - 12f) * 2f).roundToInt() / 2f
                        latestOnChange(db)
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        if (!latestEnabled || size.height <= 0) return@detectVerticalDragGestures
                        change.consume()
                        val norm = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                        val db = ((norm * 24f - 12f) * 2f).roundToInt() / 2f
                        latestOnChange(db)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            if (w <= 4f || h <= 4f) return@Canvas

            val cx = w * 0.5f
            val padY = 14.dp.toPx()
            val trackTop = padY
            val trackBottom = h - padY
            val trackLen = (trackBottom - trackTop).coerceAtLeast(1f)
            val trackW = 5.dp.toPx()

            val tickCount = 9
            for (t in 0..tickCount) {
                val ty = trackTop + (t.toFloat() / tickCount) * trackLen
                val isCenter = t == tickCount / 2
                val tickLen = if (isCenter) 7.dp.toPx() else 4.dp.toPx()
                val tickColor = if (isCenter) Color(0x44FFFFFF) else Color(0x1EFFFFFF)
                drawLine(
                    color = tickColor,
                    start = Offset(cx - trackW - 4.dp.toPx() - tickLen, ty),
                    end = Offset(cx - trackW - 4.dp.toPx(), ty),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tickColor,
                    start = Offset(cx + trackW + 4.dp.toPx(), ty),
                    end = Offset(cx + trackW + 4.dp.toPx() + tickLen, ty),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawRoundRect(
                color = Color(0xFF0A0C16),
                topLeft = Offset(cx - trackW * 0.5f, trackTop),
                size = Size(trackW, trackLen),
                cornerRadius = CornerRadius(trackW * 0.5f, trackW * 0.5f),
            )

            val norm = ((valueDb.coerceIn(-12f, 12f) + 12f) / 24f).coerceIn(0f, 1f)
            val thumbY = trackBottom - norm * trackLen

            val fillH = (trackBottom - thumbY).coerceAtLeast(2f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(accent, MaksColors.VioletAccent, MaksColors.VioletAccent.copy(alpha = 0.25f)),
                    startY = thumbY,
                    endY = trackBottom,
                ),
                topLeft = Offset(cx - trackW * 0.5f, thumbY),
                size = Size(trackW, fillH),
                cornerRadius = CornerRadius(trackW * 0.5f, trackW * 0.5f),
            )

            val glowR = 20.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaksColors.VioletAccent.copy(alpha = 0.45f),
                        accent.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, thumbY),
                    radius = glowR,
                ),
                radius = glowR,
                center = Offset(cx, thumbY),
            )

            val knobW = 28.dp.toPx()
            val knobH = 14.dp.toPx()
            drawRoundRect(
                color = Color(0xFF1F243B),
                topLeft = Offset(cx - knobW * 0.5f, thumbY - knobH * 0.5f),
                size = Size(knobW, knobH),
                cornerRadius = CornerRadius(knobH * 0.5f, knobH * 0.5f),
            )
            val innerW = 14.dp.toPx()
            val innerH = 3.dp.toPx()
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(MaksColors.LavenderGlow, accent),
                    startX = cx - innerW * 0.5f,
                    endX = cx + innerW * 0.5f,
                ),
                topLeft = Offset(cx - innerW * 0.5f, thumbY - innerH * 0.5f),
                size = Size(innerW, innerH),
                cornerRadius = CornerRadius(innerH * 0.5f, innerH * 0.5f),
            )
        }

        val dbText = if (valueDb > 0.05f) {
            String.format(Locale.US, "+%.1f", valueDb)
        } else if (valueDb < -0.05f) {
            String.format(Locale.US, "%.1f", valueDb)
        } else {
            "0.0"
        }
        Text(
            text = dbText,
            color = if (abs(valueDb) > 0.05f) MaksColors.TextPrimary else MaksColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun StereoBalanceSlider(
    balance: Float,
    accent: Color,
    onBalanceChange: (Float) -> Unit,
) {
    val latestOnChange by rememberUpdatedState(onBalanceChange)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("L", color = MaksColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (size.width <= 0) return@detectTapGestures
                        val norm = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val raw = norm * 2f - 1f
                        latestOnChange(if (abs(raw) < 0.06f) 0f else raw)
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        if (size.width <= 0) return@detectHorizontalDragGestures
                        change.consume()
                        val norm = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val raw = norm * 2f - 1f
                        latestOnChange(if (abs(raw) < 0.05f) 0f else raw)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            if (w <= 4f || h <= 4f) return@Canvas

            val cy = h * 0.5f
            val padX = 10.dp.toPx()
            val left = padX
            val right = w - padX
            val span = (right - left).coerceAtLeast(1f)
            val midX = left + span * 0.5f

            val ticks = 10
            for (i in 0..ticks) {
                val tx = left + (i.toFloat() / ticks) * span
                val isCenter = i == ticks / 2
                val tickH = if (isCenter) 10.dp.toPx() else 5.dp.toPx()
                drawLine(
                    color = if (isCenter) Color(0x55FFFFFF) else Color(0x22FFFFFF),
                    start = Offset(tx, cy - tickH * 0.5f),
                    end = Offset(tx, cy + tickH * 0.5f),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawLine(
                color = Color(0x28FFFFFF),
                start = Offset(left, cy),
                end = Offset(right, cy),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val thumbX = left + ((balance.coerceIn(-1f, 1f) + 1f) * 0.5f) * span

            drawLine(
                brush = Brush.horizontalGradient(listOf(MaksColors.VioletAccent, accent)),
                start = Offset(midX, cy),
                end = Offset(thumbX, cy),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val glowR = 14.dp.toPx()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(MaksColors.VioletAccent.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(thumbX, cy),
                    radius = glowR,
                ),
                radius = glowR,
                center = Offset(thumbX, cy),
            )
            drawCircle(
                color = MaksColors.LavenderGlow,
                radius = 6.dp.toPx(),
                center = Offset(thumbX, cy),
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = Offset(thumbX, cy),
            )
        }

        Text("R", color = MaksColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun NeonPillSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(220),
        label = "switch_thumb",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) MaksColors.VioletAccent else Color(0xFF1D2134),
        animationSpec = tween(220),
        label = "switch_track",
    )

    Box(
        modifier = modifier
            .width(48.dp)
            .height(28.dp)
            .shadow(
                elevation = if (checked) 10.dp else 0.dp,
                shape = CircleShape,
                ambientColor = MaksColors.VioletAccent,
                spotColor = MaksColors.VioletAccent,
            )
            .clip(CircleShape)
            .background(trackColor)
            .border(
                1.dp,
                if (checked) MaksColors.LavenderGlow.copy(alpha = 0.7f) else MaksColors.SpaceBorder,
                CircleShape,
            )
            .clickable { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else MaksColors.TextMuted),
        )
    }
}