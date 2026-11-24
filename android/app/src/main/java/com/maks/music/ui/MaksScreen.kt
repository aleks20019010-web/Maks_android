package com.maks.music.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.MaksBanner
import com.maks.music.media.PlayerUiState
import com.maks.music.voice.VoiceEngineState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class MainTab(val label: String, val icon: ImageVector) {
    PLAYER("Плеер", Icons.Rounded.MusicNote),
    EQ("EQ Studio", Icons.Rounded.Tune),
    LIBRARY("Медиатека", Icons.Rounded.LibraryMusic),
    QUEUE("Очередь", Icons.AutoMirrored.Rounded.QueueMusic),
}

private enum class PlayerViewMode {
    PLAYER,
    MAKS_CORE,
}

private data class AmbientPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
)

private val SpecularGlassBorder = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.22f),
        Color.White.copy(alpha = 0.08f),
        Color.White.copy(alpha = 0.03f),
    )
)

private fun extractAmbientPalette(coverArt: Bitmap?, modeLabel: String): AmbientPalette {
    val modeDefault = when {
        modeLabel.contains("лайк", ignoreCase = true) || modeLabel.contains("Избранное", ignoreCase = true) ->
            AmbientPalette(
                primary = MaksColors.LikeRose,
                secondary = Color(0xFF9C27B0),
                tertiary = MaksColors.VioletAccent,
            )
        modeLabel.contains("бодр", ignoreCase = true) || modeLabel.contains("энерг", ignoreCase = true) ->
            AmbientPalette(
                primary = Color(0xFFFFA726),
                secondary = Color(0xFFFF5722),
                tertiary = MaksColors.VioletAccent,
            )
        else ->
            AmbientPalette(
                primary = MaksColors.CyanAccent,
                secondary = MaksColors.VioletAccent,
                tertiary = MaksColors.LavenderGlow,
            )
    }

    if (coverArt == null || coverArt.isRecycled) return modeDefault

    return runCatching {
        val small = Bitmap.createScaledBitmap(coverArt, 12, 12, true)
        val hsv = FloatArray(3)
        val candidates = mutableListOf<Pair<Color, Float>>()

        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val pixel = small.getPixel(x, y)
                AndroidColor.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                if (value > 0.18f && sat > 0.15f) {
                    val boostedV = value.coerceIn(0.42f, 0.92f)
                    val boostedS = sat.coerceIn(0.35f, 0.90f)
                    val colorInt = AndroidColor.HSVToColor(floatArrayOf(hsv[0], boostedS, boostedV))
                    candidates += Color(colorInt) to (sat * value)
                }
            }
        }
        if (small != coverArt) small.recycle()

        if (candidates.isEmpty()) return@runCatching modeDefault
        candidates.sortByDescending { it.second }

        val c1 = candidates.first().first
        val c2 = candidates.getOrNull(candidates.size / 3)?.first ?: modeDefault.secondary
        val c3 = candidates.getOrNull((candidates.size * 2) / 3)?.first ?: modeDefault.tertiary
        AmbientPalette(primary = c1, secondary = c2, tertiary = c3)
    }.getOrDefault(modeDefault)
}

@Composable
private fun LiveMeshGradientBackground(
    palette: AmbientPalette,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val c1 by animateColorAsState(palette.primary, tween(1200), label = "mesh_c1")
    val c2 by animateColorAsState(palette.secondary, tween(1200), label = "mesh_c2")
    val c3 by animateColorAsState(palette.tertiary, tween(1200), label = "mesh_c3")
    val intensity by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.45f,
        animationSpec = tween(900),
        label = "mesh_intensity",
    )

    val infinite = rememberInfiniteTransition(label = "mesh_flow")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mesh_phase",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas

        // 1-е пятно: орбита вокруг обложки
        val p1 = Offset(
            x = w * (0.50f + 0.22f * cos(phase)),
            y = h * (0.24f + 0.10f * sin(phase * 1.2f)),
        )
        val r1 = size.maxDimension * (0.52f + 0.05f * sin(phase))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c1.copy(alpha = 0.24f * intensity),
                0.55f to c1.copy(alpha = 0.08f * intensity),
                1.0f to Color.Transparent,
                center = p1,
                radius = r1,
            ),
            radius = r1,
            center = p1,
            blendMode = BlendMode.Screen,
        )

        // 2-е пятно: противофазное перетекание слева/снизу
        val p2 = Offset(
            x = w * (0.28f + 0.26f * sin(phase * 0.8f + 1.4f)),
            y = h * (0.42f + 0.14f * cos(phase * 0.9f)),
        )
        val r2 = size.maxDimension * (0.48f + 0.04f * cos(phase))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c2.copy(alpha = 0.20f * intensity),
                0.60f to c2.copy(alpha = 0.06f * intensity),
                1.0f to Color.Transparent,
                center = p2,
                radius = r2,
            ),
            radius = r2,
            center = p2,
            blendMode = BlendMode.Screen,
        )

        // 3-е пятно: мягкий правый акцент
        val p3 = Offset(
            x = w * (0.76f + 0.18f * cos(phase * 1.1f + 2.6f)),
            y = h * (0.32f + 0.16f * sin(phase * 0.7f + 0.8f)),
        )
        val r3 = size.maxDimension * 0.44f
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to c3.copy(alpha = 0.16f * intensity),
                0.60f to c3.copy(alpha = 0.05f * intensity),
                1.0f to Color.Transparent,
                center = p3,
                radius = r3,
            ),
            radius = r3,
            center = p3,
            blendMode = BlendMode.Screen,
        )
    }
}

@Composable
fun MaksScreen(
    viewModel: MaksViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onPushToTalk: () -> Unit,
    onToggleAlwaysListening: (Boolean) -> Unit,
    onShutdownApp: () -> Unit,
) {
    val player by viewModel.playerState.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val ttsSpeaking by viewModel.isTtsSpeaking.collectAsState()
    val emotion by viewModel.lastEmotion.collectAsState()

    val palette = remember(player.coverArt, player.modeLabel) {
        extractAmbientPalette(player.coverArt, player.modeLabel)
    }
    val accent by animateColorAsState(palette.primary, tween(1200), label = "ambient_accent")

    val alwaysListening = voiceState == VoiceEngineState.LISTENING_WAKE ||
        voiceState == VoiceEngineState.LISTENING_COMMAND

    val avatarMode = when {
        ttsSpeaking -> AvatarMode.SPEAKING
        voiceState == VoiceEngineState.LISTENING_COMMAND -> AvatarMode.LISTENING
        voiceState == VoiceEngineState.ERROR -> AvatarMode.ERROR
        player.isPlaying -> AvatarMode.MUSIC
        emotion == "happy" || emotion == "good" -> AvatarMode.HAPPY
        else -> AvatarMode.IDLE
    }

    val avatarAudioLevel = when {
        ttsSpeaking -> 0.72f
        voiceState == VoiceEngineState.LISTENING_COMMAND -> micLevel
        player.isPlaying -> 0.42f
        else -> 0.08f
    }

    var activeTab by remember { mutableStateOf(MainTab.PLAYER) }
    var playerViewMode by remember { mutableStateOf(PlayerViewMode.PLAYER) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF101324),
                        MaksColors.SpaceBg,
                        MaksColors.SpaceBgDeep,
                    )
                )
            )
    ) {
        // Живой переливающийся mesh-градиент от палитры текущей обложки и режима
        LiveMeshGradientBackground(
            palette = palette,
            isPlaying = player.isPlaying || ttsSpeaking,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    MainTab.PLAYER -> {
                        if (playerViewMode == PlayerViewMode.PLAYER) {
                            NowPlayingTab(
                                player = player,
                                accent = accent,
                                avatarMode = avatarMode,
                                avatarAudioLevel = avatarAudioLevel,
                                voiceState = voiceState,
                                alwaysListening = alwaysListening,
                                ttsSpeaking = ttsSpeaking,
                                hasAudioPermission = hasAudioPermission,
                                onRequestAudioPermission = onRequestAudioPermission,
                                onPickMusicFolder = onPickMusicFolder,
                                onToggleAlwaysListening = { onToggleAlwaysListening(!alwaysListening) },
                                onShutdownApp = onShutdownApp,
                                onOpenEq = { activeTab = MainTab.EQ },
                                onSwitchToCore = { playerViewMode = PlayerViewMode.MAKS_CORE },
                                onPushToTalk = onPushToTalk,
                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                onNext = { viewModel.nextTrack() },
                                onPrev = { viewModel.prevTrack() },
                                onSeekTo = { viewModel.seekTo(it) },
                                onToggleLike = { viewModel.toggleLike() },
                                onLikeOnly = {
                                    if (!player.isCurrentLiked) viewModel.toggleLike()
                                },
                                onDislike = { viewModel.dislike() },
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                onCycleRepeat = { viewModel.cycleRepeatMode() },
                                onStartWave = { langFilter -> viewModel.playWave(langFilter) },
                                onPlayLiked = { viewModel.playLiked() },
                                onPlayArtist = { artist -> viewModel.playArtist(artist) },
                                onOpenQueue = { activeTab = MainTab.QUEUE },
                                onApplyWaveSettings = viewModel::playWaveWithSettings,
                            )
                        } else {
                            MaksCoreTab(
                                player = player,
                                accent = accent,
                                avatarMode = avatarMode,
                                avatarAudioLevel = avatarAudioLevel,
                                voiceState = voiceState,
                                alwaysListening = alwaysListening,
                                ttsSpeaking = ttsSpeaking,
                                onSwitchToPlayer = { playerViewMode = PlayerViewMode.PLAYER },
                                onPushToTalk = onPushToTalk,
                                onToggleAlwaysListening = { onToggleAlwaysListening(!alwaysListening) },
                                onShutdownApp = onShutdownApp,
                                onSubmitCommand = { cmd -> viewModel.submitCommand(cmd) },
                            )
                        }
                    }

                    MainTab.EQ -> EqStudioTab(
                        player = player,
                        accent = accent,
                        onToggleEqEnabled = { viewModel.setEqEnabled(it) },
                        onBandGainChange = { idx, db -> viewModel.setEqBandDb(idx, db) },
                        onSelectPreset = { viewModel.selectEqPreset(it) },
                        onToggleBassBoost = { viewModel.setBassBoostEnabled(it) },
                        onBalanceChange = { viewModel.setStereoBalance(it) },
                        onSelectSleepTimer = { viewModel.setSleepTimer(it) },
                        onSelectCrossfade = viewModel::setCrossfadeSec,
                        onExportPreferencesUri = viewModel::exportPreferencesToUri,
                        onImportPreferencesUri = viewModel::importPreferencesFromUri,
                    )

                    MainTab.LIBRARY -> LibraryTab(
                        player = player,
                        accent = accent,
                        hasAudioPermission = hasAudioPermission,
                        loadThumbnail = { viewModel.loadTrackThumbnail(it) },
                        onRequestAudioPermission = onRequestAudioPermission,
                        onPickMusicFolder = onPickMusicFolder,
                        onRefresh = { viewModel.refreshLibrary() },
                        onPlayWave = {
                            viewModel.playWave()
                            activeTab = MainTab.PLAYER
                            playerViewMode = PlayerViewMode.PLAYER
                        },
                        onPlayLiked = {
                            viewModel.playLiked()
                            activeTab = MainTab.PLAYER
                            playerViewMode = PlayerViewMode.PLAYER
                        },
                        onPlayArtist = { artist ->
                            viewModel.playArtist(artist)
                            activeTab = MainTab.PLAYER
                            playerViewMode = PlayerViewMode.PLAYER
                        },
                        onPlayTrack = { tracks, index, label ->
                            viewModel.playFromLibrary(tracks, index, label)
                            activeTab = MainTab.PLAYER
                            playerViewMode = PlayerViewMode.PLAYER
                        },
                        onToggleLikeTrack = { viewModel.toggleLikeTrack(it) },
                    )

                    MainTab.QUEUE -> QueueTab(
                        player = player,
                        accent = accent,
                        loadThumbnail = { viewModel.loadTrackThumbnail(it) },
                        onPlayQueueIndex = { idx -> viewModel.playQueueIndex(idx) },
                        onRebuildWave = { viewModel.playWave() },
                        onOpenEq = { activeTab = MainTab.EQ },
                    )
                }

                MaksBannerOverlay(
                    banner = banner,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    onConfirmYes = {
                        viewModel.submitCommand("да")
                        viewModel.dismissBanner()
                    },
                    onConfirmNo = {
                        viewModel.submitCommand("нет")
                        viewModel.dismissBanner()
                    },
                    onDismiss = { viewModel.dismissBanner() },
                )
            }

            val showMiniPlayer = (activeTab != MainTab.PLAYER || playerViewMode == PlayerViewMode.MAKS_CORE) &&
                player.currentTrack != null
            if (showMiniPlayer) {
                MiniPlayerBar(
                    player = player,
                    accent = accent,
                    onClick = {
                        activeTab = MainTab.PLAYER
                        playerViewMode = PlayerViewMode.PLAYER
                    },
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onNext = { viewModel.nextTrack() },
                )
            }

            BottomNavBar(
                activeTab = activeTab,
                onSelect = { tab ->
                    if (tab == MainTab.PLAYER && activeTab == MainTab.PLAYER && playerViewMode == PlayerViewMode.MAKS_CORE) {
                        playerViewMode = PlayerViewMode.PLAYER
                    }
                    activeTab = tab
                },
            )
        }
    }
}

@Composable
private fun MaksBannerOverlay(
    banner: MaksBanner?,
    modifier: Modifier = Modifier,
    onConfirmYes: () -> Unit,
    onConfirmNo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        banner?.let { b ->
            MaksReplyToast(
                banner = b,
                onConfirmYes = onConfirmYes,
                onConfirmNo = onConfirmNo,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    player: PlayerUiState,
    accent: Color,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val track = player.currentTrack ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaksColors.SpaceSurface.copy(alpha = 0.88f))
            .border(1.dp, SpecularGlassBorder, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaksColors.VioletAccent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = player.coverArt
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = MaksColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.ifBlank { "Локальный файл" },
                color = MaksColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "Пауза / Играть",
                tint = MaksColors.TextPrimary,
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "Дальше",
                tint = MaksColors.TextPrimary,
            )
        }
    }
}

@Composable
private fun BottomNavBar(
    activeTab: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE8080911))
            .border(1.dp, SpecularGlassBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val selected = activeTab == tab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) MaksColors.LavenderGlow else MaksColors.TextMuted,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    text = tab.label,
                    color = if (selected) MaksColors.TextPrimary else MaksColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}