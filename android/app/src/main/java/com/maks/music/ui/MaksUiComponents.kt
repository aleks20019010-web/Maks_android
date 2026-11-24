package com.maks.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.MaksBanner

@Composable
internal fun HardwareCircleButton(
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
internal fun PlayPauseGlowOrb(
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
internal fun MaksReplyToast(
    banner: MaksBanner,
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
internal fun PlayerCoreModeSwitch(
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

internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60L
    val sec = totalSec % 60L
    return "%d:%02d".format(min, sec)
}