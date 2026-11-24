package com.maks.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.MaksBanner
import com.maks.music.media.LocalTrack
import com.maks.music.media.PlayerUiState
import com.maks.music.media.RepeatMode
import com.maks.music.voice.VoiceEngineState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    LIKED("Любимые"),
    ARTISTS("Артисты"),
}

@Composable
fun MaksScreen(
    viewModel: MaksViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onPushToTalk: () -> Unit,
    onToggleAlwaysListening: (Boolean) -> Unit,
) {
    val player by viewModel.playerState.collectAsState()
    val banner by viewModel.banner.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val micLevel by viewModel.micLevel.collectAsState()
    val ttsSpeaking by viewModel.isTtsSpeaking.collectAsState()
    val emotion by viewModel.lastEmotion.collectAsState()

    val accent = remember(player.coverArt) { extractAccentColor(player.coverArt) }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Основная область вкладки
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
                                onOpenEq = { activeTab = MainTab.EQ },
                                onSwitchToCore = { playerViewMode = PlayerViewMode.MAKS_CORE },
                                onPushToTalk = onPushToTalk,
                                onTogglePlayPause = { viewModel.togglePlayPause() },
                                onNext = { viewModel.nextTrack() },
                                onPrev = { viewModel.prevTrack() },
                                onSeekTo = { viewModel.seekTo(it) },
                                onToggleLike = { viewModel.toggleLike() },
                                onDislike = { viewModel.dislike() },
                                onToggleShuffle = { viewModel.toggleShuffle() },
                                onCycleRepeat = { viewModel.cycleRepeatMode() },
                                onStartWave = { viewModel.playWave() },
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
                        onPlayQueueIndex = { idx -> viewModel.playQueueIndex(idx) },
                        onRebuildWave = { viewModel.playWave() },
                        onOpenEq = { activeTab = MainTab.EQ },
                    )
                }

                // Всплывающее сообщение Макса (вынесено вне ColumnScope)
                MaksBannerOverlay(
                    banner = banner,
                    accent = accent,
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

            // Компактный мини-плеер, когда открыто Ядро Макса, Медиатека, EQ Studio или Очередь
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

            // Нижняя панель навигации (4 вкладки)
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
    accent: Color,
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
                accent = accent,
                onConfirmYes = onConfirmYes,
                onConfirmNo = onConfirmNo,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun NowPlayingTab(
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
    onOpenEq: () -> Unit,
    onSwitchToCore: () -> Unit,
    onPushToTalk: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onDislike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onStartWave: () -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val durMs = player.durationMs.coerceAtLeast(1L)
    val sliderFraction = if (isDragging) {
        dragFraction
    } else {
        (player.positionMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
    }
    val shownPosMs = if (isDragging) (dragFraction * durMs).toLong() else player.positionMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 1. Верхняя панель: переключатель «Плеер ↔ Ядро Макса» + EQ и микрофон
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
                IconButton(onClick = onOpenEq) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "EQ Studio",
                        tint = if (player.eqEnabled && (player.eqPresetIndex != 0 || player.bassBoostEnabled)) {
                            MaksColors.LavenderGlow
                        } else {
                            MaksColors.TextSecondary
                        },
                    )
                }
                IconButton(onClick = onToggleAlwaysListening) {
                    Icon(
                        imageVector = if (alwaysListening) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        contentDescription = "Голосовой режим",
                        tint = if (alwaysListening) MaksColors.CyanAccent else MaksColors.TextMuted,
                    )
                }
            }
        }

        // 2. Центральный круговой циферблат с обложкой / плазменным ядром + неоновая дуга прогресса
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularArtworkDial(
                progress = sliderFraction,
                accent = accent,
                coverArt = player.coverArt,
                trackTitle = player.currentTrack?.title,
                avatarMode = avatarMode,
                avatarAudioLevel = avatarAudioLevel,
                onTapOrb = onPushToTalk,
                modifier = Modifier
                    .widthIn(max = 266.dp)
                    .fillMaxWidth(0.80f)
                    .aspectRatio(1f),
            )

            Spacer(Modifier.height(6.dp))

            // Табличное время под кольцом (как 2:36 / 4:48 на макете)
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

        // 3. Название трека + Артист + Сердце / Дизлайк
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
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = player.currentTrack?.let { t ->
                        listOf(t.artist.ifBlank { "Локальный файл" }, t.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    } ?: "Нажмите Play или откройте медиатеку",
                    color = MaksColors.TextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onDislike, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.Rounded.ThumbDownOffAlt,
                    contentDescription = "Не нравится",
                    tint = MaksColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleLike, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (player.isCurrentLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Нравится",
                    tint = if (player.isCurrentLiked) MaksColors.LikeRose else MaksColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Компактная плашка выдачи доступа / выбора папки, если нет треков
        if (!hasAudioPermission || player.libraryCount == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaksColors.SpaceSurface)
                    .border(1.dp, MaksColors.SpaceBorderGlow, RoundedCornerShape(14.dp))
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
                        .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(10.dp))
                        .clickable { onPickMusicFolder() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Папка", color = MaksColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 4. Высокодетализированная зеркальная аудиоволна + тонкий неоновый ползунок перемотки
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

        // 5. Тактильные тёмные круглые кнопки управления + светящаяся орбитальная кнопка Play/Pause
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

        // 6. Нижняя сдвоенная стеклянная панель быстрых действий (как [ Add to Playlist | Lyrics ] на макете)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaksColors.SpaceSurface)
                .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onStartWave() }
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
                    text = "Моя волна",
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
                    .background(MaksColors.SpaceBorder)
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSwitchToCore() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = if (voiceState == VoiceEngineState.LISTENING_COMMAND) MaksColors.LikeRose else accent,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Ядро Макса",
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
private fun CircularArtworkDial(
    progress: Float,
    accent: Color,
    coverArt: android.graphics.Bitmap?,
    trackTitle: String?,
    avatarMode: AvatarMode,
    avatarAudioLevel: Float,
    onTapOrb: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Внешние прецизионные штрихи, неоновое кольцо прогресса и светящаяся точка-индикатор
        Canvas(modifier = Modifier.fillMaxSize()) {
            val minDim = size.minDimension
            if (minDim <= 8f) return@Canvas

            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val center = Offset(cx, cy)

            val outerTickR = minDim * 0.485f
            val innerTickR = minDim * 0.462f
            val ringR = minDim * 0.422f

            // Мягкое фиолетово-голубое свечение за кольцом
            drawCircle(
                brush = Brush.radialGradient(
                    0.55f to MaksColors.VioletAccent.copy(alpha = 0.14f),
                    0.82f to accent.copy(alpha = 0.08f),
                    1.0f to Color.Transparent,
                    center = center,
                    radius = minDim * 0.495f,
                ),
                radius = minDim * 0.495f,
                center = center,
            )

            // 60 микро-делений циферблата по окружности
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

            // Тёмный трек прогресса
            drawCircle(
                color = Color(0xFF1A1E32),
                radius = ringR,
                center = center,
                style = Stroke(width = 3.dp.toPx()),
            )

            // Светящаяся дуга прогресса (Violet -> Lavender -> Cyan)
            val sweep = (progress.coerceIn(0f, 1f) * 360f).coerceAtLeast(if (progress > 0f) 4f else 0f)
            if (sweep > 0.5f) {
                val arcTopLeft = Offset(cx - ringR, cy - ringR)
                val arcSize = Size(ringR * 2f, ringR * 2f)
                val arcBrush = Brush.linearGradient(
                    colors = listOf(MaksColors.LavenderGlow, MaksColors.VioletAccent, accent),
                    start = Offset(cx - ringR, cy - ringR),
                    end = Offset(cx + ringR, cy + ringR),
                )
                // Мягкий bloom под дугой
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    alpha = 0.34f,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                )
                // Чёткая яркая дуга
                drawArc(
                    brush = arcBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round),
                )

                // Светящаяся орбитальная точка на кончике дуги
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

        // Внутренняя круглая обложка или плазменная сфера Макса
        Box(
            modifier = Modifier
                .fillMaxSize(0.75f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF14182B), Color(0xFF0A0C16))
                    )
                )
                .border(1.dp, Color(0x24FFFFFF), CircleShape),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onTapOrb() },
                )
            }
        }

        // Когда есть обложка — аккуратная компактная сфера Макса в нижнем правом углу кольца
        if (coverArt != null && !coverArt.isRecycled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(14.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xE00A0C16))
                    .border(1.dp, MaksColors.SpaceBorderGlow, CircleShape)
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

        // Неактивный трек
        drawLine(
            color = Color(0xFF1F2338),
            start = Offset(startX, cy),
            end = Offset(endX, cy),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // Активный неоновый отрезок
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

        // Светящийся бегунок
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

@Composable
private fun HardwareCircleButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    iconSize: Dp,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (active) MaksColors.VioletAccent.copy(alpha = 0.22f)
                else MaksColors.SpaceSurface
            )
            .border(
                width = 1.dp,
                color = if (active) MaksColors.LavenderGlow.copy(alpha = 0.65f) else MaksColors.SpaceBorder,
                shape = CircleShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaksColors.CyanAccent else MaksColors.TextSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun PlayPauseGlowOrb(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .shadow(
                elevation = 24.dp,
                shape = CircleShape,
                ambientColor = MaksColors.VioletAccent,
                spotColor = MaksColors.VioletAccent,
            )
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF282747), Color(0xFF15172B))
                )
            )
            .border(
                width = 2.2.dp,
                brush = Brush.linearGradient(
                    listOf(MaksColors.LavenderGlow, MaksColors.VioletAccent, accent)
                ),
                shape = CircleShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Пауза" else "Играть",
            tint = Color.White,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun LibraryTab(
    player: PlayerUiState,
    accent: Color,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickMusicFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlayWave: () -> Unit,
    onPlayLiked: () -> Unit,
    onPlayArtist: (String) -> Unit,
    onPlayTrack: (List<LocalTrack>, Int, String) -> Unit,
    onToggleLikeTrack: (LocalTrack) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }

    val filteredTracks = remember(player.allTracks, player.likedTrackIds, searchQuery, filter) {
        val base = when (filter) {
            LibraryFilter.LIKED -> player.allTracks.filter { it.id in player.likedTrackIds }
            else -> player.allTracks
        }
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) base
        else base.filter {
            it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    val artistsGrouped = remember(filteredTracks) {
        filteredTracks
            .groupBy { it.artist.ifBlank { "Неизвестный артист" } }
            .entries
            .sortedByDescending { it.value.size }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Медиатека",
                    color = MaksColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${player.libraryCount} треков · ${player.likedTrackIds.size} любимых",
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPickMusicFolder) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = "Выбрать папку с музыкой",
                        tint = MaksColors.LavenderGlow,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Обновить библиотеку",
                        tint = MaksColors.TextSecondary,
                    )
                }
            }
        }

        // Поиск
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Поиск трека или артиста", color = MaksColors.TextMuted, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaksColors.TextMuted) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Очистить", tint = MaksColors.TextMuted)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaksColors.SpaceSurface,
                unfocusedContainerColor = MaksColors.SpaceSurface,
                focusedBorderColor = MaksColors.LavenderGlow,
                unfocusedBorderColor = MaksColors.SpaceBorder,
                focusedTextColor = MaksColors.TextPrimary,
                unfocusedTextColor = MaksColors.TextPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Фильтры + быстрый старт Волны
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryFilter.entries.forEach { tab ->
                val selected = filter == tab
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaksColors.VioletAccent.copy(alpha = 0.22f) else MaksColors.SpaceSurface)
                        .border(
                            1.dp,
                            if (selected) MaksColors.LavenderGlow else MaksColors.SpaceBorder,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { filter = tab }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = tab.label,
                        color = if (selected) MaksColors.TextPrimary else MaksColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(listOf(MaksColors.VioletAccent, accent))
                    )
                    .clickable {
                        if (filter == LibraryFilter.LIKED) onPlayLiked() else onPlayWave()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (filter == LibraryFilter.LIKED) "Слушать" else "Волна",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (player.allTracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        tint = MaksColors.TextMuted,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        text = if (!hasAudioPermission) "Нет доступа к аудиофайлам" else "Аудиофайлы пока не найдены",
                        color = MaksColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (!hasAudioPermission) {
                            "Выдайте разрешение на чтение аудио или укажите папку с музыкой вручную."
                        } else {
                            "Выберите папку с MP3 / FLAC на телефоне или нажмите обновить."
                        },
                        color = MaksColors.TextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (!hasAudioPermission) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaksColors.VioletAccent)
                                .clickable { onRequestAudioPermission() }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = "Разрешить доступ к музыке",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaksColors.SpaceSurface)
                            .border(1.dp, MaksColors.SpaceBorderGlow, RoundedCornerShape(14.dp))
                            .clickable { onPickMusicFolder() }
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = "Выбрать папку с музыкой",
                            color = MaksColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        } else if (filter == LibraryFilter.ARTISTS) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(artistsGrouped, key = { _, item -> item.key }) { _, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaksColors.SpaceSurface)
                            .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(16.dp))
                            .clickable { onPlayArtist(entry.key) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaksColors.VioletAccent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = MaksColors.LavenderGlow)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.key,
                                color = MaksColors.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${entry.value.size} треков",
                                color = MaksColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Играть артиста",
                            tint = MaksColors.TextSecondary,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(filteredTracks, key = { _, t -> t.id }) { index, track ->
                    val isCurrent = player.currentTrack?.id == track.id
                    val isLiked = track.id in player.likedTrackIds
                    TrackRow(
                        track = track,
                        isCurrent = isCurrent,
                        isLiked = isLiked,
                        accent = accent,
                        onClick = {
                            val label = if (filter == LibraryFilter.LIKED) "Мои лайки" else "Медиатека"
                            onPlayTrack(filteredTracks, index, label)
                        },
                        onToggleLike = { onToggleLikeTrack(track) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTab(
    player: PlayerUiState,
    accent: Color,
    onPlayQueueIndex: (Int) -> Unit,
    onRebuildWave: () -> Unit,
    onOpenEq: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = "Очередь",
                    color = MaksColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${player.modeLabel} · ${player.queue.size} треков",
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.SpaceSurface)
                        .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(12.dp))
                        .clickable { onOpenEq() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("EQ", color = MaksColors.LavenderGlow, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.VioletAccent.copy(alpha = 0.22f))
                        .border(1.dp, MaksColors.SpaceBorderGlow, RoundedCornerShape(12.dp))
                        .clickable { onRebuildWave() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Обновить волну", color = MaksColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        if (player.queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Очередь пуста — запустите Мою волну или выберите трек в медиатеке",
                    color = MaksColors.TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                itemsIndexed(player.queue, key = { idx, t -> "${idx}_${t.id}" }) { idx, track ->
                    val isCurrent = idx == player.currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isCurrent) MaksColors.VioletAccent.copy(alpha = 0.16f) else Color.Transparent)
                            .clickable { onPlayQueueIndex(idx) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "${idx + 1}",
                            color = if (isCurrent) accent else MaksColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(24.dp),
                            textAlign = TextAlign.Center,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isCurrent) accent else MaksColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artist.ifBlank { "Локальный файл" },
                                color = MaksColors.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatTime(track.durationMs),
                            color = MaksColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: LocalTrack,
    isCurrent: Boolean,
    isLiked: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) MaksColors.VioletAccent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isCurrent) MaksColors.VioletAccent.copy(alpha = 0.25f) else MaksColors.SpaceSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (isCurrent) accent else MaksColors.TextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isCurrent) accent else MaksColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.ifBlank { "Локальный файл" },
                color = MaksColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatTime(track.durationMs),
            color = MaksColors.TextMuted,
            fontSize = 12.sp,
        )
        IconButton(onClick = onToggleLike, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Лайк",
                tint = if (isLiked) MaksColors.LikeRose else MaksColors.TextMuted,
                modifier = Modifier.size(18.dp),
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

@Composable
private fun MaksReplyToast(
    banner: MaksBanner,
    accent: Color,
    onConfirmYes: () -> Unit,
    onConfirmNo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF5141728))
            .border(1.dp, MaksColors.SpaceBorderGlow, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = banner.text,
                color = MaksColors.TextPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Закрыть",
                    tint = MaksColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (banner.isPendingConfirmation) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.VioletAccent)
                        .clickable { onConfirmYes() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Да, убрать", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaksColors.SpaceSurfaceElevated)
                        .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(12.dp))
                        .clickable { onConfirmNo() }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Оставить", color = MaksColors.TextPrimary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PlayerCoreModeSwitch(
    isCoreActive: Boolean,
    onSelectPlayer: () -> Unit,
    onSelectCore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaksColors.SpaceSurface)
            .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(14.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val playerSelected = !isCoreActive
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (playerSelected) {
                        Brush.horizontalGradient(
                            listOf(
                                MaksColors.VioletAccent.copy(alpha = 0.36f),
                                MaksColors.CyanAccent.copy(alpha = 0.22f),
                            )
                        )
                    } else {
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (playerSelected) MaksColors.LavenderGlow.copy(alpha = 0.55f) else Color.Transparent,
                    shape = RoundedCornerShape(11.dp),
                )
                .clickable { onSelectPlayer() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (playerSelected) MaksColors.CyanAccent else MaksColors.TextMuted,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Плеер",
                color = if (playerSelected) MaksColors.TextPrimary else MaksColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = if (playerSelected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (isCoreActive) {
                        Brush.horizontalGradient(
                            listOf(
                                MaksColors.VioletAccent.copy(alpha = 0.36f),
                                MaksColors.CyanAccent.copy(alpha = 0.22f),
                            )
                        )
                    } else {
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (isCoreActive) MaksColors.LavenderGlow.copy(alpha = 0.55f) else Color.Transparent,
                    shape = RoundedCornerShape(11.dp),
                )
                .clickable { onSelectCore() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (isCoreActive) MaksColors.CyanAccent else MaksColors.TextMuted,
                modifier = Modifier.size(15.dp),
            )
            Text(
                text = "Ядро Макса",
                color = if (isCoreActive) MaksColors.TextPrimary else MaksColors.TextMuted,
                fontSize = 12.sp,
                fontWeight = if (isCoreActive) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MaksCoreTab(
    player: PlayerUiState,
    accent: Color,
    avatarMode: AvatarMode,
    avatarAudioLevel: Float,
    voiceState: VoiceEngineState,
    alwaysListening: Boolean,
    ttsSpeaking: Boolean,
    onSwitchToPlayer: () -> Unit,
    onPushToTalk: () -> Unit,
    onToggleAlwaysListening: () -> Unit,
    onSubmitCommand: (String) -> Unit,
) {
    var commandText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 1. Верхняя панель с переключателем Плеер ↔ Ядро Макса
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlayerCoreModeSwitch(
                isCoreActive = true,
                onSelectPlayer = onSwitchToPlayer,
                onSelectCore = {},
            )

            IconButton(onClick = onToggleAlwaysListening) {
                Icon(
                    imageVector = if (alwaysListening) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                    contentDescription = "Голосовой режим",
                    tint = if (alwaysListening) MaksColors.CyanAccent else MaksColors.TextMuted,
                )
            }
        }

        // 2. Полноразмерное 3D-ядро Макса в центре экрана
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 264.dp)
                    .fillMaxWidth(0.76f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .clickable { onPushToTalk() },
                contentAlignment = Alignment.Center,
            ) {
                PlasmaAvatar(
                    mode = avatarMode,
                    audioLevel = avatarAudioLevel,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.height(10.dp))

            val stateTitle = when {
                ttsSpeaking -> "Макс отвечает…"
                voiceState == VoiceEngineState.LISTENING_COMMAND -> "Слушаю команду…"
                voiceState == VoiceEngineState.LISTENING_WAKE -> "Готов · скажите «Макс…»"
                voiceState == VoiceEngineState.LOADING_MODEL -> "Подготовка оффлайн-голоса…"
                voiceState == VoiceEngineState.MODEL_MISSING -> "Голосовая модель не найдена"
                voiceState == VoiceEngineState.ERROR -> "Проверьте доступ к микрофону"
                else -> "Нажмите на ядро, чтобы сказать"
            }
            Text(
                text = stateTitle,
                color = MaksColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(3.dp))

            val subInfo = player.currentTrack?.let { t ->
                "${t.artist.ifBlank { "Локальный файл" }} — ${t.title}"
            } ?: "${player.libraryCount} треков · Оффлайн-алиасы активны"
            Text(
                text = subInfo,
                color = MaksColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            MirrorSpectrumVisualizer(
                isActive = player.isPlaying || ttsSpeaking || voiceState == VoiceEngineState.LISTENING_COMMAND,
                intensity = avatarAudioLevel,
                accentColor = accent,
                progress = 1f,
                trackSeed = 42,
                onSeekFraction = {},
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(26.dp),
            )
        }

        // 3. Быстрые голосовые сценарии, ввод команды и фоновая активация
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Моя волна" to "включи мою волну",
                    "Русская волна" to "включи русскую музыку",
                    "Мои лайки" to "включи мои лайки",
                    "КиШ" to "включи Горшка",
                    "Бодрое" to "включи бодрое",
                    "Рок" to "включи рок",
                ).forEach { (label, cmd) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaksColors.SpaceSurfaceElevated)
                            .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(12.dp))
                            .clickable { onSubmitCommand(cmd) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(label, color = MaksColors.TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    placeholder = { Text("Например: включи Горшка или Кино", color = MaksColors.TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaksColors.SpaceSurfaceElevated,
                        unfocusedContainerColor = MaksColors.SpaceSurfaceElevated,
                        focusedBorderColor = MaksColors.LavenderGlow,
                        unfocusedBorderColor = MaksColors.SpaceBorder,
                        focusedTextColor = MaksColors.TextPrimary,
                        unfocusedTextColor = MaksColors.TextPrimary,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (commandText.isNotBlank()) {
                            onSubmitCommand(commandText)
                            commandText = ""
                        }
                    }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (commandText.isNotBlank()) {
                            onSubmitCommand(commandText)
                            commandText = ""
                        } else {
                            onPushToTalk()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(MaksColors.VioletAccent, accent))),
                ) {
                    Icon(
                        imageVector = if (commandText.isNotBlank()) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Mic,
                        contentDescription = "Отправить",
                        tint = Color.White,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaksColors.SpaceSurface)
                    .border(1.dp, MaksColors.SpaceBorder, RoundedCornerShape(14.dp))
                    .clickable { onToggleAlwaysListening() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Фоновая активация по слову «Макс»",
                    color = MaksColors.TextSecondary,
                    fontSize = 13.sp,
                )
                NeonPillSwitch(
                    checked = alwaysListening,
                    onCheckedChange = { onToggleAlwaysListening() },
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}
