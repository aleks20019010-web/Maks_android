package com.maks.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.media.PlayerUiState
import com.maks.music.voice.VoiceEngineState

@Composable
internal fun MaksCoreTab(
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
    onShutdownApp: () -> Unit,
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
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
                    "Мои лайки" to "включи мою музыку",
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