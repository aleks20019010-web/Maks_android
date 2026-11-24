package com.maks.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.media.PlayerUiState
import com.maks.music.media.RepeatMode
import com.maks.music.voice.VoiceEngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class WaveLangMode(val label: String, val filter: String?) {
    ALL("Моя волна", null),
    RUSSIAN("Русская волна", "russian"),
    FOREIGN("Зарубежная", "not-russian"),
}

private val GlassSpecularBorder = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.22f),
        Color.White.copy(alpha = 0.08f),
        Color.White.copy(alpha = 0.03f),
    )
)

private fun Modifier.fadingEdgeMarquee(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                0.0f to Color.Transparent,
                0.04f to Color.Black,
                0.92f to Color.Black,
                1.0f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingTab(
    player: PlayerUiState,
    accent: Color,
    avatarMode: AvatarMode,
    avatarAudioLevel: Float,
    voiceState: VoiceEngineState,
    alwaysListening: Boolean,
    ttsSpeaking: Boolean,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onToggleAlwaysListening: () -> Unit,
    onShutdownApp: () -> Unit,
    onOpenEq: () -> Unit,
    onSwitchToCore: () -> Unit,
    onPushToTalk: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onLikeOnly: () -> Unit,
    onDislike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onStartWave: (String?) -> Unit,
    onPlayLiked: () -> Unit,
    onPlayArtist: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var waveMode by remember { mutableStateOf(WaveLangMode.ALL) }

    val durMs = player.durationMs.coerceAtLeast(1L)
    val sliderFraction = if (isDragging) {
        dragFraction
    } else {
        (player.positionMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
    }
    val shownPosMs = if (isDragging) (dragFraction * durMs).toLong() else player.positionMs

    val isWaveActive = player.isPlaying && (
        player.modeLabel == "Моя волна" ||
            player.modeLabel == "Русская волна" ||
            player.modeLabel == "Зарубежная волна"
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 1. Верхняя панель
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerCoreModeSwitch(
                isCoreActive = false,
                onSelectPlayer = {},
                onSelectCore = onSwitchToCore,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onOpenEq, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "EQ Studio",
                        tint = if (player.eqEnabled && (player.eqPresetIndex != 0 || player.bassBoostEnabled)) {
                            MaksColors.LavenderGlow
                        } else {
                            MaksColors.TextSecondary
                        },
                        modifier = Modifier.size(21.dp),
                    )
                }
                IconButton(onClick = onToggleAlwaysListening, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = if (alwaysListening) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = "Голосовой режим",
                        tint = if (alwaysListening) MaksColors.CyanAccent else MaksColors.TextMuted,
                        modifier = Modifier.size(21.dp),
                    )
                }
                IconButton(onClick = onShutdownApp, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = "Выключить Макса",
                        tint = MaksColors.LikeRose,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }

        // 2. Центральный круговой циферблат с «дышащей» обложкой на пружинной физике
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularArtworkDial(
                progress = sliderFraction,
                isPlaying = player.isPlaying,
                accent = accent,
                coverArt = player.coverArt,
                trackTitle = player.currentTrack?.title,
                avatarMode = avatarMode,
                avatarAudioLevel = avatarAudioLevel,
                onTapOrb = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPushToTalk()
                },
                onDoubleTapLike = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLikeOnly()
                },
                onSwipeNext = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNext()
                },
                onSwipePrev = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPrev()
                },
                modifier = Modifier
                    .widthIn(max = 266.dp)
                    .fillMaxWidth(0.80f)
                    .aspectRatio(1f),
            )

            Spacer(Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = formatTime(shownPosMs),
                    color = MaksColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "/",
                    color = MaksColors.TextMuted,
                    fontSize = 11.sp,
                )
                Text(
                    text = formatTime(player.durationMs),
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        // 3. Бегущая строка названия трека + Кликабельный Артист + Дизлайк / Пружинный Лайк
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = player.currentTrack?.title ?: "Включите Мою волну",
                    color = MaksColors.TextPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fadingEdgeMarquee()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2200,
                            velocity = 34.dp,
                        ),
                )
                Spacer(Modifier.height(2.dp))
                val curTrack = player.currentTrack
                if (curTrack != null && curTrack.artist.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fadingEdgeMarquee()
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 2800,
                                velocity = 30.dp,
                            ),
                    ) {
                        Text(
                            text = curTrack.artist,
                            color = MaksColors.LavenderGlow.copy(alpha = 0.90f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPlayArtist(curTrack.artist)
                                }
                                .padding(end = 2.dp),
                        )
                        if (curTrack.album.isNotBlank()) {
                            Text(
                                text = " · ${curTrack.album}",
                                color = MaksColors.TextMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (curTrack != null) "Локальный файл" else "Нажмите Play или откройте медиатеку",
                        color = MaksColors.TextMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDislike()
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ThumbDownOffAlt,
                    contentDescription = "Не нравится",
                    tint = MaksColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedLikeButton(
                isLiked = player.isCurrentLiked,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleLike()
                },
            )
        }

        if (!hasAudioPermission || player.libraryCount == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaksColors.SpaceSurface.copy(alpha = 0.85f))
                    .border(1.dp, GlassSpecularBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (!hasAudioPermission) "Нет доступа к музыке" else "Треки не найдены",
                    color = MaksColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!hasAudioPermission) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaksColors.VioletAccent)
                            .clickable { onRequestAudioPermission() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("Разрешить", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaksColors.SpaceSurfaceElevated)
                        .border(1.dp, GlassSpecularBorder, RoundedCornerShape(10.dp))
                        .clickable { onPickMusicFolder() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Папка", color = MaksColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 4. Зеркальная аудиоволна + тонкий неоновый ползунок перемотки
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MirrorSpectrumVisualizer(
                isActive = player.isPlaying || ttsSpeaking || voiceState == VoiceEngineState.LISTENING_COMMAND,
                intensity = avatarAudioLevel,
                accentColor = accent,
                progress = sliderFraction,
                trackSeed = player.currentTrack?.id?.hashCode() ?: 7,
                onSeekFraction = { frac ->
                    onSeekTo((frac * durMs).toLong())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            )

            SlimNeonSeekBar(
                fraction = sliderFraction,
                accent = accent,
                onDragFraction = { frac ->
                    isDragging = true
                    dragFraction = frac
                },
                onCommitFraction = { frac ->
                    isDragging = false
                    onSeekTo((frac * durMs).toLong())
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(shownPosMs), color = MaksColors.TextMuted, fontSize = 11.sp)
                Text(formatTime(player.durationMs), color = MaksColors.TextMuted, fontSize = 11.sp)
            }
        }

        // 5. Кнопки управления воспроизведением
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HardwareCircleButton(
                icon = Icons.Rounded.Shuffle,
                contentDescription = "Перемешать",
                size = 42.dp,
                iconSize = 18.dp,
                active = player.isShuffle,
                onClick = onToggleShuffle,
            )
            HardwareCircleButton(
                icon = Icons.Rounded.SkipPrevious,
                contentDescription = "Предыдущий",
                size = 48.dp,
                iconSize = 24.dp,
                active = false,
                onClick = onPrev,
            )
            PlayPauseGlowOrb(
                isPlaying = player.isPlaying,
                accent = accent,
                onClick = onTogglePlayPause,
            )
            HardwareCircleButton(
                icon = Icons.Rounded.SkipNext,
                contentDescription = "Следующий",
                size = 48.dp,
                iconSize = 24.dp,
                active = false,
                onClick = onNext,
            )
            HardwareCircleButton(
                icon = if (player.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Повтор",
                size = 42.dp,
                iconSize = 18.dp,
                active = player.repeatMode != RepeatMode.OFF,
                onClick = onCycleRepeat,
            )
        }

        // 6. Нижняя сдвоенная панель со спекулярным стеклянным бликом
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaksColors.SpaceSurface.copy(alpha = 0.85f))
                .border(1.dp, GlassSpecularBorder, RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (isWaveActive) {
                            val nextIdx = (waveMode.ordinal + 1) % WaveLangMode.entries.size
                            waveMode = WaveLangMode.entries[nextIdx]
                        }
                        onStartWave(waveMode.filter)
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaksColors.LavenderGlow,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = waveMode.label,
                    color = MaksColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPlayLiked()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaksColors.LikeRose,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Избранное",
                    color = MaksColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AnimatedLikeButton(
    isLiked: Boolean,
    onClick: () -> Unit,
) {
    val heartScale = remember { Animatable(1f) }
    val ringProgress = remember { Animatable(0f) }

    LaunchedEffect(isLiked) {
        if (isLiked) {
            launch {
                heartScale.snapTo(1f)
                heartScale.animateTo(1.28f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
                heartScale.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow))
            }
            launch {
                ringProgress.snapTo(0.05f)
                ringProgress.animateTo(1f, tween(420))
                ringProgress.snapTo(0f)
            }
        }
    }

    Box(
        modifier = Modifier.size(42.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (ringProgress.value > 0.01f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val p = ringProgress.value
                val radius = (size.minDimension * 0.22f) + (size.minDimension * 0.30f * p)
                drawCircle(
                    color = MaksColors.LikeRose.copy(alpha = (1f - p) * 0.65f),
                    radius = radius,
                    style = Stroke(width = (2.5.dp.toPx() * (1f - p * 0.5f))),
                )
            }
        }
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Нравится",
                tint = if (isLiked) MaksColors.LikeRose else MaksColors.TextSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .scale(heartScale.value),
            )
        }
    }
}

@Composable
private fun CircularArtworkDial(
    progress: Float,
    isPlaying: Boolean,
    accent: Color,
    coverArt: Bitmap?,
    trackTitle: String?,
    avatarMode: AvatarMode,
    avatarAudioLevel: Float,
    onTapOrb: () -> Unit,
    onDoubleTapLike: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestNext by rememberUpdatedState(onSwipeNext)
    val latestPrev by rememberUpdatedState(onSwipePrev)
    val latestDoubleTap by rememberUpdatedState(onDoubleTapLike)
    val latestTapOrb by rememberUpdatedState(onTapOrb)

    var rawDragX by remember { mutableFloatStateOf(0f) }
    var showLikeBurst by remember { mutableStateOf(false) }

    LaunchedEffect(showLikeBurst) {
        if (showLikeBurst) {
            delay(650L)
            showLikeBurst = false
        }
    }

    val artworkScale by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.88f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artwork_breathing_scale",
    )

    val glowAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.35f,
        animationSpec = tween(420),
        label = "artwork_glow_alpha",
    )

    val animatedOffsetX by animateFloatAsState(
        targetValue = rawDragX.coerceIn(-120f, 120f),
        animationSpec = tween(durationMillis = if (rawDragX == 0f) 220 else 0),
        label = "artwork_swipe_offset",
    )

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { rawDragX = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    rawDragX += dragAmount
                },
                onDragEnd = {
                    val thresholdPx = 56.dp.toPx()
                    when {
                        rawDragX <= -thresholdPx -> latestNext()
                        rawDragX >= thresholdPx -> latestPrev()
                    }
                    rawDragX = 0f
                },
                onDragCancel = { rawDragX = 0f },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            if (minDim <= 8f) return@Canvas

            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val center = Offset(cx, cy)

            val outerTickR = minDim * 0.485f
            val innerTickR = minDim * 0.462f
            val ringR = minDim * 0.422f

            drawCircle(
                brush = Brush.radialGradient(
                    0.50f to accent.copy(alpha = 0.24f * glowAlpha),
                    0.78f to MaksColors.VioletAccent.copy(alpha = 0.12f * glowAlpha),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = minDim * 0.498f,
                ),
                radius = minDim * 0.498f,
                center = center,
            )

            val ticks = 60
            for (i in 0 until ticks) {
                val deg = -90f + (i.toFloat() / ticks) * 360f
                val rad = deg * (PI.toFloat() / 180f)
                val isMajor = i % 5 == 0
                val rIn = if (isMajor) innerTickR - 2.dp.toPx() else innerTickR
                drawLine(
                    color = if (isMajor) Color(0x36FFFFFF) else Color(0x18FFFFFF),
                    start = Offset(cx + cos(rad) * rIn, cy + sin(rad) * rIn),
                    end = Offset(cx + cos(rad) * outerTickR, cy + sin(rad) * outerTickR),
                    strokeWidth = if (isMajor) 1.3.dp.toPx() else 0.9.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawCircle(
                color = Color(0xFF1A1E32),
                radius = ringR,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )

            val sweep = (progress.coerceIn(0f, 1f) * 360f).coerceAtLeast(if (progress > 0f) 4f else 0f)
            if (sweep > 0.5f) {
                val arcTopLeft = Offset(cx - ringR, cy - ringR)
                val arcSize = Size(ringR * 2f, ringR * 2f)
                val arcBrush = Brush.linearGradient(
                    colors = listOf(MaksColors.LavenderGlow, MaksColors.VioletAccent, accent),
                    start = Offset(cx - ringR, cy - ringR),
                    end = Offset(cx + ringR, cy + ringR),
                )
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    alpha = 0.34f * glowAlpha,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                )

                val tipRad = (-90f + sweep) * (PI.toFloat() / 180f)
                val tipPos = Offset(cx + cos(tipRad) * ringR, cy + sin(tipRad) * ringR)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.72f), Color.Transparent),
                        center = tipPos,
                        radius = 12.dp.toPx(),
                    ),
                    radius = 12.dp.toPx(),
                    center = tipPos,
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.8.dp.toPx(),
                    center = tipPos,
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset((animatedOffsetX * 0.45f).roundToInt(), 0) }
                .scale(artworkScale)
                .fillMaxSize(0.75f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF14182B), Color(0xFF0A0C16))
                    )
                )
                .border(1.dp, GlassSpecularBorder, CircleShape)
                .pointerInput(coverArt != null) {
                    detectTapGestures(
                        onDoubleTap = {
                            showLikeBurst = true
                            latestDoubleTap()
                        },
                        onTap = {
                            if (coverArt == null) latestTapOrb()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (coverArt != null && !coverArt.isRecycled) {
                Image(
                    bitmap = coverArt.asImageBitmap(),
                    contentDescription = trackTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlasmaAvatar(
                    mode = avatarMode,
                    audioLevel = avatarAudioLevel,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = showLikeBurst,
                enter = scaleIn(initialScale = 0.4f, animationSpec = tween(180)) + fadeIn(tween(150)),
                exit = scaleOut(targetScale = 1.25f, animationSpec = tween(260)) + fadeOut(tween(240)),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0x880B0D18)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaksColors.LikeRose,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }

        if (coverArt != null && !coverArt.isRecycled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xE00A0C16))
                    .border(1.dp, GlassSpecularBorder, CircleShape)
                    .clickable { onTapOrb() },
                contentAlignment = Alignment.Center,
            ) {
                PlasmaAvatar(
                    mode = avatarMode,
                    audioLevel = avatarAudioLevel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SlimNeonSeekBar(
    fraction: Float,
    accent: Color,
    onDragFraction: (Float) -> Unit,
    onCommitFraction: (Float) -> Unit,
) {
    val latestDrag by rememberUpdatedState(onDragFraction)
    val latestCommit by rememberUpdatedState(onCommitFraction)
    var currentDrag by remember { mutableFloatStateOf(fraction) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        latestCommit(f)
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { latestCommit(currentDrag) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            currentDrag = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            latestDrag(currentDrag)
                        }
                    },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        val cy = h * 0.5f
        val pad = 7.dp.toPx()
        val startX = pad
        val endX = w - pad
        val span = (endX - startX).coerceAtLeast(1f)
        val thumbX = startX + fraction.coerceIn(0f, 1f) * span

        drawLine(
            color = Color(0xFF1F2338),
            start = Offset(startX, cy),
            end = Offset(endX, cy),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        if (thumbX > startX) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(MaksColors.VioletAccent, MaksColors.LavenderGlow, accent),
                    startX = startX,
                    endX = thumbX.coerceAtLeast(startX + 1f),
                ),
                start = Offset(startX, cy),
                end = Offset(thumbX, cy),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val glowR = 11.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(MaksColors.LavenderGlow.copy(alpha = 0.65f), Color.Transparent),
                center = Offset(thumbX, cy),
                radius = glowR,
            ),
            radius = glowR,
            center = Offset(thumbX, cy),
        )
        drawCircle(
            color = Color.White,
            radius = 4.2.dp.toPx(),
            center = Offset(thumbX, cy),
        )
    }
}