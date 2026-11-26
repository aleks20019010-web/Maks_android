package com.maks.music.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.music.core.memory.MusicMemoryDb
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MaksSettingsScreen(
    viewModel: MaksViewModel,
    alwaysListening: Boolean,
    onToggleAlwaysListening: (Boolean) -> Unit,
    onOpenEq: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }

    val settings by viewModel.settingsState.collectAsState()
    val player by viewModel.playerState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tokenDraft by remember(settings.yandexToken) { mutableStateOf(settings.yandexToken) }
    var showToken by remember { mutableStateOf(false) }
    var tokenStatusText by remember { mutableStateOf<String?>(null) }
    var tokenStatusOk by remember { mutableStateOf(false) }
    var isTestingToken by remember { mutableStateOf(false) }

    var cacheFilesCount by remember { mutableIntStateOf(0) }
    var cacheTotalBytes by remember { mutableLongStateOf(0L) }
    var isApiPaused by remember { mutableStateOf(viewModel.isYandexTemporarilyDown()) }

    var blacklistExpanded by remember { mutableStateOf(false) }
    var blacklistedEntries by remember { mutableStateOf<List<MusicMemoryDb.BlacklistedEntry>>(emptyList()) }

    val refreshCacheAndBlacklist = {
        val (cnt, bytes) = viewModel.getYandexCacheStats()
        cacheFilesCount = cnt
        cacheTotalBytes = bytes
        isApiPaused = viewModel.isYandexTemporarilyDown()
        blacklistedEntries = viewModel.getBlacklistedEntries()
    }

    LaunchedEffect(Unit) {
        refreshCacheAndBlacklist()
    }

    val isBatteryUnrestricted = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaksColors.SpaceBg.copy(alpha = 0.94f))
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaksColors.SpaceSurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "?????",
                    tint = MaksColors.TextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "????????? ?????",
                    color = MaksColors.TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "?????? ?????? ? ????? ? ???? ? ?????????",
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. ?????? ?????? ? ???
            SettingsSectionCard(
                title = "?????? ?????? ? ???",
                icon = Icons.Rounded.CloudSync,
                accent = MaksColors.CyanAccent,
            ) {
                Text(
                    text = "OAuth-????? ????????",
                    color = MaksColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it },
                    placeholder = { Text("y0_AgAAAA...", color = MaksColors.TextMuted, fontSize = 13.sp) },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null,
                                tint = MaksColors.TextMuted,
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaksColors.SpaceSurfaceRecessed,
                        unfocusedContainerColor = MaksColors.SpaceSurfaceRecessed,
                        focusedBorderColor = MaksColors.CyanAccent,
                        unfocusedBorderColor = MaksColors.SpaceBorder,
                        focusedTextColor = MaksColors.TextPrimary,
                        unfocusedTextColor = MaksColors.TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.setYandexToken(tokenDraft)
                            tokenStatusOk = tokenDraft.trim().isNotEmpty()
                            tokenStatusText = if (tokenStatusOk) "????? ????????" else "????? ??????"
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.CyanAccent.copy(alpha = 0.22f),
                            contentColor = MaksColors.CyanAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("?????????", fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        enabled = !isTestingToken,
                        onClick = {
                            viewModel.setYandexToken(tokenDraft)
                            isTestingToken = true
                            tokenStatusText = "????????..."
                            scope.launch {
                                val res = viewModel.testYandexConnection()
                                isTestingToken = false
                                isApiPaused = viewModel.isYandexTemporarilyDown()
                                res.onSuccess { accountName ->
                                    tokenStatusOk = true
                                    tokenStatusText = "?????????: $accountName"
                                }.onFailure { err ->
                                    tokenStatusOk = false
                                    tokenStatusText = "??????: ${err.message ?: "??? ?????"}"
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.VioletAccent.copy(alpha = 0.25f),
                            contentColor = MaksColors.LavenderGlow,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("?????????", fontWeight = FontWeight.SemiBold)
                    }
                }

                tokenStatusText?.let { status ->
                    Text(
                        text = status,
                        color = if (tokenStatusOk) MaksColors.TealAccent else MaksColors.WarningAmber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (isApiPaused) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaksColors.WarningAmber.copy(alpha = 0.14f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "API ?? ????? ????? ?????? ????",
                            color = MaksColors.WarningAmber,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "????????",
                            color = MaksColors.CyanAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                viewModel.resetYandexCircuitBreaker()
                                isApiPaused = false
                            },
                        )
                    }
                }

                SettingsDivider()

                // ??? ??????
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("????????? ??? ???????", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        val mb = cacheTotalBytes / (1024f * 1024f)
                        Text(
                            text = "$cacheFilesCount ?????? ? ${"%.1f".format(mb)} ??",
                            color = MaksColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            viewModel.clearYandexCache()
                            refreshCacheAndBlacklist()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.LikeRose.copy(alpha = 0.18f),
                            contentColor = MaksColors.LikeRose,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("????????", fontSize = 12.sp)
                    }
                }

                Text("????? ????", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500 to "500 ??", 1024 to "1 ??", 3072 to "3 ??", 0 to "??? ??????").forEach { (limit, label) ->
                        SettingsChip(
                            label = label,
                            selected = settings.yandexCacheLimitMb == limit,
                            onClick = {
                                viewModel.setYandexCacheLimitMb(limit)
                                refreshCacheAndBlacklist()
                            },
                        )
                    }
                }

                Text("???????????? ????? (prefetch)", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3 to "3 ?????", 5 to "5 ??????", 10 to "10 ??????").forEach { (cnt, label) ->
                        SettingsChip(
                            label = label,
                            selected = settings.yandexPrefetchCount == cnt,
                            onClick = { viewModel.setYandexPrefetchCount(cnt) },
                        )
                    }
                }

                SettingsToggleRow(
                    title = "?????? ?????? ?? Wi-Fi",
                    subtitle = "? ????????? ???? ?????? ?????? ????????? ??????",
                    checked = settings.yandexWifiOnly,
                    onCheckedChange = viewModel::setYandexWifiOnly,
                )

                SettingsToggleRow(
                    title = "??????? ???????? (320 kbps)",
                    subtitle = "???? ? ???????? ??????? (~192 kbps)",
                    checked = settings.yandexHighQuality,
                    onCheckedChange = viewModel::setYandexHighQuality,
                )
            }

            // 2. ????? ? ?????????
            SettingsSectionCard(
                title = "????? ? ?????????",
                icon = Icons.Rounded.Mic,
                accent = MaksColors.VioletAccent,
            ) {
                SettingsToggleRow(
                    title = "??????? ????????? ?? ????? ??????",
                    subtitle = "???????-????????????? Vosk",
                    checked = alwaysListening,
                    onCheckedChange = onToggleAlwaysListening,
                )
                SettingsToggleRow(
                    title = "??????? ??????? ??? ?????",
                    subtitle = "????????, ??????, ??????? ?? ????? ???????????????",
                    checked = settings.hotwordsEnabled,
                    onCheckedChange = viewModel::setHotwordsEnabled,
                )
                SettingsToggleRow(
                    title = "????? ????????????? ? ?????????",
                    subtitle = "?? ??????????? Bluetooth-???????? ? ????? ?????????",
                    checked = settings.pauseListeningOnHeadset,
                    onCheckedChange = viewModel::setPauseListeningOnHeadset,
                )
                SettingsToggleRow(
                    title = "????? ????? ??????????",
                    subtitle = "?? ?????????? ???????? ? ???????? ?????? ??????",
                    checked = settings.silentControls,
                    onCheckedChange = viewModel::setSilentControls,
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("???????? ???? ?????", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            text = "${"%.2f".format(settings.ttsSpeechRate)}x",
                            color = MaksColors.LavenderGlow,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = settings.ttsSpeechRate,
                        onValueChange = { viewModel.setTtsSpeechRate((it * 20).roundToInt() / 20f) },
                        valueRange = 0.75f..1.35f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaksColors.LavenderGlow,
                            activeTrackColor = MaksColors.VioletAccent,
                        ),
                    )
                }
            }

            // 3. ???? ? ???????????????
            SettingsSectionCard(
                title = "???? ? ???????????????",
                icon = Icons.Rounded.GraphicEq,
                accent = MaksColors.TealAccent,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("??????? ????????? ??????", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            text = if (player.crossfadeSec <= 0.05f) "????" else "${"%.1f".format(player.crossfadeSec)} ???",
                            color = MaksColors.TealAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = player.crossfadeSec,
                        onValueChange = { viewModel.setCrossfadeSec((it * 2).roundToInt() / 2f) },
                        valueRange = 0f..3.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaksColors.TealAccent,
                            activeTrackColor = MaksColors.TealAccent,
                        ),
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("??????????? ?????? ??? ???? (Ducking)", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            text = "${settings.duckLevelPercent}%",
                            color = MaksColors.CyanAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Slider(
                        value = settings.duckLevelPercent.toFloat(),
                        onValueChange = { viewModel.setDuckLevelPercent(it.roundToInt()) },
                        valueRange = 5f..50f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaksColors.CyanAccent,
                            activeTrackColor = MaksColors.CyanAccent,
                        ),
                    )
                }

                Text("?????? ???", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "????", 15 to "15 ?", 30 to "30 ?", 45 to "45 ?", 60 to "60 ?").forEach { (mins, label) ->
                        val active = if (mins == null) {
                            player.sleepTimerMinutesLeft == null
                        } else {
                            player.sleepTimerMinutesLeft != null && kotlin.math.abs((player.sleepTimerMinutesLeft ?: 0) - mins) <= 5
                        }
                        SettingsChip(
                            label = if (mins != null && active && player.sleepTimerMinutesLeft != null) "${player.sleepTimerMinutesLeft} ?" else label,
                            selected = active,
                            onClick = { viewModel.setSleepTimer(mins) },
                        )
                    }
                }

                FilledTonalButton(
                    onClick = {
                        onClose()
                        onOpenEq()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaksColors.SpaceSurfaceRecessed,
                        contentColor = MaksColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("??????? 5-???????? ?????????? (EQ Studio)")
                }
            }

            // 4. ????????? ? ????? ?????
            SettingsSectionCard(
                title = "????????? ? ????? ?????",
                icon = Icons.Rounded.LibraryMusic,
                accent = MaksColors.LavenderGlow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("????????? ?????????", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text("${player.libraryCount} ?????? ? ???????", color = MaksColors.TextMuted, fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = { viewModel.refreshLibrary() },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.VioletAccent.copy(alpha = 0.22f),
                            contentColor = MaksColors.LavenderGlow,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("????????", fontSize = 12.sp)
                    }
                }

                SettingsToggleRow(
                    title = "???????????? ????? ?????? 30 ???",
                    subtitle = "????????? ????????? ????????? ? ???????? (????? 15 ???)",
                    checked = settings.ignoreShortTracksSec >= 30,
                    onCheckedChange = { checked ->
                        viewModel.setIgnoreShortTracksSec(if (checked) 30 else 15)
                    },
                )

                Text("???? ????? ?? ?????????", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "???", "russian" to "???????", "not-russian" to "??????????").forEach { (code, label) ->
                        SettingsChip(
                            label = label,
                            selected = settings.defaultWaveLanguage == code,
                            onClick = { viewModel.setDefaultWaveLanguage(code) },
                        )
                    }
                }

                SettingsDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            blacklistedEntries = viewModel.getBlacklistedEntries()
                            blacklistExpanded = !blacklistExpanded
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Block, contentDescription = null, tint = MaksColors.LikeRose, modifier = Modifier.size(18.dp))
                        Text(
                            text = "?????? ?????? (${blacklistedEntries.size})",
                            color = MaksColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = if (blacklistExpanded) "??????" else "????????",
                        color = MaksColors.CyanAccent,
                        fontSize = 12.sp,
                    )
                }

                if (blacklistExpanded) {
                    if (blacklistedEntries.isEmpty()) {
                        Text(
                            text = "?????? ?????? ????",
                            color = MaksColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            blacklistedEntries.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaksColors.SpaceSurfaceRecessed)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (entry.isArtist) "??????: ${entry.artist}" else entry.title,
                                            color = MaksColors.TextPrimary,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (!entry.isArtist && entry.artist.isNotBlank()) {
                                            Text(
                                                text = entry.artist,
                                                color = MaksColors.TextMuted,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Text(
                                        text = "???????",
                                        color = MaksColors.TealAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable {
                                                viewModel.unblacklistEntry(entry)
                                                blacklistedEntries = viewModel.getBlacklistedEntries()
                                            }
                                            .padding(start = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. ??????? ? ??????????
            SettingsSectionCard(
                title = "??????? ? ??????????",
                icon = Icons.Rounded.BatteryChargingFull,
                accent = MaksColors.WarningAmber,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("?????? ? ???? (???????)", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            text = if (isBatteryUnrestricted) "??????????? ?????" else "???? ??????????? ? Android ????? ????????? ???",
                            color = if (isBatteryUnrestricted) MaksColors.TealAccent else MaksColors.WarningAmber,
                            fontSize = 12.sp,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            runCatching {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                } else {
                                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.WarningAmber.copy(alpha = 0.18f),
                            contentColor = MaksColors.WarningAmber,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("?????????", fontSize = 12.sp)
                    }
                }

                SettingsToggleRow(
                    title = "?????????/??????????? ?? Bluetooth",
                    subtitle = "??????????? ?? ??????????? ????????? ? ?????????????",
                    checked = settings.resumeOnBluetooth,
                    onCheckedChange = viewModel::setResumeOnBluetooth,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaksColors.SpaceSurface.copy(alpha = 0.90f))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.04f))
                ),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Text(
                text = title,
                color = MaksColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaksColors.TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = MaksColors.TextMuted, fontSize = 12.sp)
        }
        NeonPillSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaksColors.CyanAccent.copy(alpha = 0.22f) else MaksColors.SpaceSurfaceRecessed)
            .border(
                width = 1.dp,
                color = if (selected) MaksColors.CyanAccent else MaksColors.SpaceBorder,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) MaksColors.CyanAccent else MaksColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaksColors.SpaceBorder, thickness = 1.dp)
}
