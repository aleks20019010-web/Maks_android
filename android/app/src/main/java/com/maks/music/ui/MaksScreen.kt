package com.maks.music.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
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

private const val YANDEX_WAVE_AGSL = """
    uniform float2 iResolution;
    uniform float iTime;
    uniform float iIntensity;
    uniform float iEnergy;
    uniform float2 iFlow;
    uniform float3 c1;
    uniform float3 c2;
    uniform float3 c3;

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(iResolution, float2(1.0));
        float aspect = iResolution.x / max(iResolution.y, 1.0);
        float2 p = float2((uv.x - 0.5) * aspect, uv.y - 0.30);

        float t = iTime * 0.42;

        // Искривление пространства (domain warping) с плавным течением по вектору iFlow
        float2 q = p + iFlow * 0.55;
        q.x += (0.32 + iEnergy * 0.14) * sin(q.y * 3.1 + t * 1.1) + 0.16 * cos(q.x * 2.4 - t * 0.7);
        q.y += (0.30 + iEnergy * 0.14) * cos(q.x * 2.9 - t * 0.9) + 0.14 * sin(q.y * 2.7 + t * 0.8);

        float2 r = q;
        r.x += 0.24 * sin(q.y * 4.4 - t * 1.3 + 1.2);
        r.y += 0.24 * cos(q.x * 4.1 + t * 1.0 + 0.6);

        // Базовое перетекание трёх цветов
        float m1 = 0.5 + 0.5 * sin(r.x * 2.2 + r.y * 1.8 + t);
        float m2 = 0.5 + 0.5 * cos(r.x * 1.9 - r.y * 2.4 - t * 0.8);
        float3 baseCol = mix(c1, c2, m1);
        baseCol = mix(baseCol, c3, m2 * 0.55);

        // Тонкие светящиеся «жилки» / ленты каустики, мягко усиливающиеся на припеве
        float w1 = abs(sin(r.x * 3.4 + r.y * 2.6 + t * 0.9));
        float w2 = abs(sin(r.x * 2.3 - r.y * 3.7 - t * 1.1 + 1.7));
        float ridge1 = pow(clamp(1.0 - w1, 0.0, 1.0), 6.5);
        float ridge2 = pow(clamp(1.0 - w2, 0.0, 1.0), 7.5);
        float veins = (ridge1 * 0.65 + ridge2 * 0.45) * (0.85 + iEnergy * 0.45);

        // Мягкие лучи света от центра обложки
        float angle = atan(p.y, p.x);
        float rays = pow(0.5 + 0.5 * sin(angle * 5.0 + t * 0.6), 4.0) * (0.18 + iEnergy * 0.10);

        // Вертикальная маска: сочный свет сверху и резкий уход в чистый чёрный ниже середины
        float topGlow = smoothstep(0.76, 0.04, uv.y);
        float centerBoost = exp(-dot(p * float2(1.15, 1.35), p * float2(1.15, 1.35)) * 1.7);
        float mask = clamp(topGlow * 0.82 + centerBoost * 0.48, 0.0, 1.0) * smoothstep(0.84, 0.50, uv.y);

        float3 color = baseCol * (0.68 + rays) + mix(baseCol, float3(1.0), 0.58) * veins;
        color *= mask * iIntensity;

        return half4(clamp(color, 0.0, 1.0), 1.0);
    }
"""

private fun extractAmbientPalette(coverArt: Bitmap?, modeLabel: String): AmbientPalette {
    val modeDefault = when {
        modeLabel.contains("лайк", ignoreCase = true) || modeLabel.contains("Избранное", ignoreCase = true) ->
            AmbientPalette(
                primary = MaksColors.LikeRose,
                secondary = Color(0xFFD500F9),
                tertiary = MaksColors.VioletAccent,
            )
        modeLabel.contains("бодр", ignoreCase = true) || modeLabel.contains("энерг", ignoreCase = true) ->
            AmbientPalette(
                primary = Color(0xFFFFB300),
                secondary = Color(0xFFFF3D00),
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
                if (value > 0.16f && sat > 0.14f) {
                    val boostedS = sat.coerceIn(0.60f, 1.0f)
                    val boostedV = value.coerceIn(0.60f, 1.0f)
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
    musicEnergy: Float,
    modifier: Modifier = Modifier,
) {
    val c1 by animateColorAsState(palette.primary, tween(1100), label = "mesh_c1")
    val c2 by animateColorAsState(palette.secondary, tween(1100), label = "mesh_c2")
    val c3 by animateColorAsState(palette.tertiary, tween(1100), label = "mesh_c3")
    val intensity by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.58f,
        animationSpec = tween(800),
        label = "mesh_intensity",
    )

    var timeSec by remember { mutableFloatStateOf(0f) }
    var smoothEnergy by remember { mutableFloatStateOf(0f) }
    var driftAngle by remember { mutableFloatStateOf(0f) }
    var flowX by remember { mutableFloatStateOf(0f) }
    var flowY by remember { mutableFloatStateOf(0f) }

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestEnergy by rememberUpdatedState(if (isPlaying) musicEnergy.coerceIn(0f, 1f) else 0f)

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)

                    // Двойная инерция: плавный вдох и ещё более плавный выдох (без рывков)
                    val k = if (latestEnergy > smoothEnergy) 4.0f else 1.6f
                    smoothEnergy += (latestEnergy - smoothEnergy) * (dt * k).coerceAtMost(1f)

                    // Время и направление только интегрируются
                    val flowSpeed = if (latestIsPlaying) (0.45f + smoothEnergy * 1.35f) else 0.15f
                    timeSec += dt * flowSpeed
                    driftAngle += dt * (0.14f + smoothEnergy * 0.55f)
                    flowX += cos(driftAngle) * dt * (0.12f + smoothEnergy * 0.42f)
                    flowY += sin(driftAngle) * dt * (0.12f + smoothEnergy * 0.42f)
                }
                lastNanos = frameNanos
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(YANDEX_WAVE_AGSL) }
        Canvas(modifier = modifier) {
            if (size.width <= 4f || size.height <= 4f) return@Canvas
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", timeSec)
            shader.setFloatUniform("iIntensity", intensity)
            shader.setFloatUniform("iEnergy", smoothEnergy)
            shader.setFloatUniform("iFlow", flowX, flowY)
            shader.setFloatUniform("c1", c1.red, c1.green, c1.blue)
            shader.setFloatUniform("c2", c2.red, c2.green, c2.blue)
            shader.setFloatUniform("c3", c3.red, c3.green, c3.blue)
            drawRect(brush = ShaderBrush(shader))
        }
    } else {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            if (w <= 4f || h <= 4f) return@Canvas
            drawRect(Color.Black)

            val phase = timeSec * 0.4f
            val boost = 1f + smoothEnergy * 0.18f
            val p1 = Offset(w * (0.50f + 0.24f * cos(phase + flowX * 0.3f)), h * (0.22f + 0.10f * sin(phase * 1.2f)))
            val r1 = size.maxDimension * 0.58f * boost
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to c1.copy(alpha = (0.72f * intensity).coerceAtMost(1f)),
                    0.50f to c1.copy(alpha = 0.28f * intensity),
                    1.0f to Color.Transparent,
                    center = p1,
                    radius = r1,
                ),
                radius = r1,
                center = p1,
                blendMode = BlendMode.Screen,
            )

            val p2 = Offset(w * (0.28f + 0.26f * sin(phase * 0.8f + 1.4f + flowY * 0.3f)), h * (0.36f + 0.12f * cos(phase * 0.9f)))
            val r2 = size.maxDimension * 0.52f * boost
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to c2.copy(alpha = (0.60f * intensity).coerceAtMost(1f)),
                    0.55f to c2.copy(alpha = 0.20f * intensity),
                    1.0f to Color.Transparent,
                    center = p2,
                    radius = r2,
                ),
                radius = r2,
                center = p2,
                blendMode = BlendMode.Screen,
            )

            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.52f to Color.Black.copy(alpha = 0.35f),
                    0.78f to Color.Black,
                )
            )
        }
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
    val musicEnergy by viewModel.musicEnergy.collectAsState()
    val ttsSpeaking by viewModel.isTtsSpeaking.collectAsState()
    val emotion by viewModel.lastEmotion.collectAsState()

    val palette = remember(player.coverArt, player.modeLabel) {
        extractAmbientPalette(player.coverArt, player.modeLabel)
    }
    val accent by animateColorAsState(palette.primary, tween(1100), label = "ambient_accent")

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
        player.isPlaying -> if (musicEnergy > 0.02f) musicEnergy else 0.42f
        else -> 0.08f
    }

    var activeTab by remember { mutableStateOf(MainTab.PLAYER) }
    var playerViewMode by remember { mutableStateOf(PlayerViewMode.PLAYER) }
    var showSettingsScreen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LiveMeshGradientBackground(
            palette = palette,
            isPlaying = player.isPlaying || ttsSpeaking,
            musicEnergy = musicEnergy,
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
                                onOpenQueue = { activeTab = MainTab.QUEUE },
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
                                onApplyWaveSettings = viewModel::playWaveWithSettings,
                                onPlayLiked = { viewModel.playLiked() },
                                onPlayArtist = { artist -> viewModel.playArtist(artist) },
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
                                onOpenSettings = { showSettingsScreen = true },
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

                if (showSettingsScreen) {
                    MaksSettingsScreen(
                        viewModel = viewModel,
                        alwaysListening = alwaysListening,
                        onToggleAlwaysListening = onToggleAlwaysListening,
                        onOpenEq = { activeTab = MainTab.EQ },
                        onClose = { showSettingsScreen = false },
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