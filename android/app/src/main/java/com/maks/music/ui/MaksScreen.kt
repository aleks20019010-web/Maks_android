package com.maks.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
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

private enum class MainTab(val label: String, val icon: ImageVector) {
    PLAYER("Плеер", Icons.Rounded.MusicNote),
    EQ("EQ Studio", Icons.Rounded.Tune),
    LIBRARY("Медиатека", Icons.Rounded.LibraryMusic),
    QUEUE("Очередь", Icons.Rounded.QueueMusic),
}

private enum class PlayerViewMode {
    PLAYER,
    MAKS_CORE,
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

    val rawAccent = remember(player.coverArt) { extractAccentColor(player.coverArt) }
    val accent by animateColorAsState(rawAccent, tween(650), label = "ambient_accent")

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
        // Атмосферное фоновое свечение под цвет текущей обложки (Ambient Glow)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val glowRadius = size.maxDimension * 0.62f
            val glowCenter = Offset(size.width * 0.5f, size.height * 0.26f)
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to accent.copy(alpha = 0.14f),
                    0.55f to MaksColors.VioletAccent.copy(alpha = 0.06f),
                    1.0f to Color.Transparent,
                    center = glowCenter,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = glowCenter,
            )
        }

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
            .background(MaksColors.SpaceSurface)
            .border(1.dp, MaksColors.SpaceBorderGlow, RoundedCornerShape(18.dp))
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
            .background(Color(0xEE080911))
            .border(1.dp, Color(0x10FFFFFF))
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