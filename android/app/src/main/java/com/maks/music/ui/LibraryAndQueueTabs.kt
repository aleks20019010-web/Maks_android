package com.maks.music.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.media.LocalTrack
import com.maks.music.media.PlayerUiState

private enum class LibraryFilter(val label: String) {
    ALL("Все"),
    LIKED("Любимые"),
    ARTISTS("Артисты"),
}

@Composable
internal fun LibraryTab(
    player: PlayerUiState,
    accent: Color,
    hasAudioPermission: Boolean,
    loadThumbnail: suspend (LocalTrack) -> Bitmap?,
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
                        isPlaying = player.isPlaying,
                        isLiked = isLiked,
                        accent = accent,
                        loadThumbnail = loadThumbnail,
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
internal fun QueueTab(
    player: PlayerUiState,
    accent: Color,
    loadThumbnail: suspend (LocalTrack) -> Bitmap?,
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
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "${idx + 1}",
                            color = if (isCurrent) accent else MaksColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(22.dp),
                            textAlign = TextAlign.Center,
                        )
                        TrackThumbnailBox(
                            track = track,
                            isCurrent = isCurrent,
                            isPlaying = player.isPlaying,
                            accent = accent,
                            size = 40.dp,
                            loadThumbnail = loadThumbnail,
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
internal fun TrackThumbnailBox(
    track: LocalTrack,
    isCurrent: Boolean,
    isPlaying: Boolean = false,
    accent: Color,
    size: Dp,
    loadThumbnail: suspend (LocalTrack) -> Bitmap?,
) {
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = track.id) {
        value = loadThumbnail(track)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(if (isCurrent) MaksColors.VioletAccent.copy(alpha = 0.25f) else MaksColors.SpaceSurface),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumb
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = if (isCurrent) accent else MaksColors.TextMuted,
                modifier = Modifier.size(19.dp),
            )
        }

        if (isCurrent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88080A14)),
                contentAlignment = Alignment.Center,
            ) {
                MiniEqualizerBars(isPlaying = isPlaying, color = accent)
            }
        }
    }
}

@Composable
private fun MiniEqualizerBars(
    isPlaying: Boolean,
    color: Color,
) {
    val infinite = rememberInfiniteTransition(label = "mini_eq")
    val b1 by infinite.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(380),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq_b1",
    )
    val b2 by infinite.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(520),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq_b2",
    )
    val b3 by infinite.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(440),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "eq_b3",
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(16.dp),
    ) {
        listOf(
            if (isPlaying) b1 else 0.32f,
            if (isPlaying) b2 else 0.55f,
            if (isPlaying) b3 else 0.25f,
        ).forEach { frac ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((16.dp * frac).coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: LocalTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    accent: Color,
    loadThumbnail: suspend (LocalTrack) -> Bitmap?,
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
        TrackThumbnailBox(
            track = track,
            isCurrent = isCurrent,
            isPlaying = isPlaying,
            accent = accent,
            size = 44.dp,
            loadThumbnail = loadThumbnail,
        )
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