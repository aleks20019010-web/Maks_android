package com.maks.music.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportPreferencesToUri(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPreferencesFromUri(uri)
    }


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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaksColors.SpaceBg.copy(alpha = 0.96f))
    ) {
        // Шапка экрана
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
                    contentDescription = "Назад",
                    tint = MaksColors.TextPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Настройки Макса",
                    color = MaksColors.TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Яндекс Музыка · Голос · Звук · Медиатека",
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
            // 1. ЯНДЕКС МУЗЫКА И КЭШ
            SettingsSectionCard(
                title = "Яндекс Музыка и кэш",
                icon = Icons.Rounded.CloudSync,
                accent = MaksColors.CyanAccent,
            ) {
                Text(
                    text = "OAuth-токен аккаунта",
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
                            tokenStatusText = if (tokenStatusOk) "Токен сохранён" else "Токен очищен"
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.CyanAccent.copy(alpha = 0.22f),
                            contentColor = MaksColors.CyanAccent,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить", fontWeight = FontWeight.SemiBold)
                    }

                    FilledTonalButton(
                        enabled = !isTestingToken,
                        onClick = {
                            viewModel.setYandexToken(tokenDraft)
                            isTestingToken = true
                            tokenStatusText = "Проверка..."
                            scope.launch {
                                val res = viewModel.testYandexConnection()
                                isTestingToken = false
                                isApiPaused = viewModel.isYandexTemporarilyDown()
                                res.onSuccess { accountName ->
                                    tokenStatusOk = true
                                    tokenStatusText = "Подключён: $accountName"
                                }.onFailure { err ->
                                    tokenStatusOk = false
                                    tokenStatusText = "Ошибка: ${err.message ?: "нет связи"}"
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
                        Text("Проверить", fontWeight = FontWeight.SemiBold)
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
                            text = "API на паузе после ошибок сети",
                            color = MaksColors.WarningAmber,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Сбросить",
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Локальный кэш Яндекса", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        val mb = cacheTotalBytes / (1024f * 1024f)
                        Text(
                            text = "$cacheFilesCount треков · ${"%.1f".format(mb)} МБ",
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
                        Text("Очистить", fontSize = 12.sp)
                    }
                }

                Text("Лимит кэша", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500 to "500 МБ", 1024 to "1 ГБ", 3072 to "3 ГБ", 0 to "Без лимита").forEach { (limit, label) ->
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

                Text("Предзагрузка волны (prefetch)", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3 to "3 трека", 5 to "5 треков", 10 to "10 треков").forEach { (cnt, label) ->
                        SettingsChip(
                            label = label,
                            selected = settings.yandexPrefetchCount == cnt,
                            onClick = { viewModel.setYandexPrefetchCount(cnt) },
                        )
                    }
                }

                SettingsToggleRow(
                    title = "Качать только по Wi-Fi",
                    subtitle = "В мобильной сети играет только локальная музыка",
                    checked = settings.yandexWifiOnly,
                    onCheckedChange = viewModel::setYandexWifiOnly,
                )

                SettingsToggleRow(
                    title = "Высокое качество (320 kbps)",
                    subtitle = "Выкл — экономия трафика (~192 kbps)",
                    checked = settings.yandexHighQuality,
                    onCheckedChange = viewModel::setYandexHighQuality,
                )
            }

            // 2. ГОЛОС И АКТИВАЦИЯ
            SettingsSectionCard(
                title = "Голос и активация",
                icon = Icons.Rounded.Mic,
                accent = MaksColors.VioletAccent,
            ) {
                SettingsToggleRow(
                    title = "Фоновая активация по слову «Макс»",
                    subtitle = "Оффлайн-распознавание Vosk",
                    checked = alwaysListening,
                    onCheckedChange = onToggleAlwaysListening,
                )
                SettingsToggleRow(
                    title = "Быстрые команды без имени",
                    subtitle = "«Дальше», «тише», «пауза» во время воспроизведения",
                    checked = settings.hotwordsEnabled,
                    onCheckedChange = viewModel::setHotwordsEnabled,
                )
                SettingsToggleRow(
                    title = "Автоусиление микрофона (AGC)",
                    subtitle = "Автоматическая регулировка уровня на аудиосессии записи",
                    checked = runCatching { settings.agcEnabled }.getOrDefault(true),
                    onCheckedChange = { enabled ->
                        val gain = runCatching { settings.micGainMultiplier }.getOrDefault(1.0f)
                        runCatching {
                            val m = viewModel.javaClass.methods.firstOrNull { it.name == "setAgcEnabled" }
                            m?.invoke(viewModel, enabled)
                        }
                    },
                )
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val gainVal = runCatching { settings.micGainMultiplier }.getOrDefault(1.0f)
                    Text(
                        text = "Усиление микрофона: ${String.format(java.util.Locale.US, "%.2fx", gainVal)}",
                        color = MaksColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.75f to "0.75x", 1.0f to "1.0x", 1.35f to "1.35x", 1.75f to "1.75x").forEach { (preset, label) ->
                            val selected = kotlin.math.abs(gainVal - preset) < 0.02f
                            FilledTonalButton(
                                onClick = { viewModel.setMicGainMultiplier(preset) },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (selected) MaksColors.LavenderGlow.copy(alpha = 0.28f) else MaksColors.SpaceSurfaceElevated,
                                    contentColor = if (selected) MaksColors.TextPrimary else MaksColors.TextSecondary,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                SettingsToggleRow(
                    title = "Пауза прослушивания в гарнитуре",
                    subtitle = "Не переключать Bluetooth-наушники в режим микрофона",
                    checked = settings.pauseListeningOnHeadset,
                    onCheckedChange = viewModel::setPauseListeningOnHeadset,
                )
                SettingsToggleRow(
                    title = "Тихий режим управления",
                    subtitle = "Не озвучивать «Громче» и «Дальше» поверх музыки",
                    checked = settings.silentControls,
                    onCheckedChange = viewModel::setSilentControls,
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Скорость речи Макса", color = MaksColors.TextPrimary, fontSize = 14.sp)
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

            // 3. ЗВУК И ВОСПРОИЗВЕДЕНИЕ
            SettingsSectionCard(
                title = "Звук и воспроизведение",
                icon = Icons.Rounded.GraphicEq,
                accent = MaksColors.TealAccent,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Плавный кроссфейд треков", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text(
                            text = if (player.crossfadeSec <= 0.05f) "Выкл" else "${"%.1f".format(player.crossfadeSec)} сек",
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
                        Text("Приглушение музыки при речи (Ducking)", color = MaksColors.TextPrimary, fontSize = 14.sp)
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

                Text("Таймер сна", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Выкл", 15 to "15 м", 30 to "30 м", 45 to "45 м", 60 to "60 м").forEach { (mins, label) ->
                        val active = if (mins == null) {
                            player.sleepTimerMinutesLeft == null
                        } else {
                            player.sleepTimerMinutesLeft != null && kotlin.math.abs((player.sleepTimerMinutesLeft ?: 0) - mins) <= 5
                        }
                        SettingsChip(
                            label = if (mins != null && active && player.sleepTimerMinutesLeft != null) "${player.sleepTimerMinutesLeft} м" else label,
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
                    Text("Открыть 5-полосный эквалайзер (EQ Studio)")
                }
            }


                val context = androidx.compose.ui.platform.LocalContext.current
                val currentDirUri = runCatching { settings.yandexDownloadDirUri }.getOrDefault("")
                val folderDisplayName = remember(currentDirUri) {
                    if (currentDirUri.isBlank()) {
                        "По умолчанию (yandex_cache)"
                    } else {
                        runCatching {
                            val parsed = android.net.Uri.parse(currentDirUri)
                            val treeId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(parsed) }.getOrNull().orEmpty()
                            val docName = treeId.substringAfterLast(':', treeId).substringAfterLast('/').takeIf { it.isNotBlank() }
                            if (!docName.isNullOrBlank()) "$docName ($currentDirUri)" else currentDirUri
                        }.getOrDefault(currentDirUri)
                    }
                }
                val dirPickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        runCatching {
                            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            context.contentResolver.takePersistableUriPermission(uri, flags)
                        }
                        viewModel.setYandexDownloadDirUri(uri.toString())
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Папка загрузки Яндекс Музыки",
                            color = MaksColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (currentDirUri.isNotBlank()) {
                            Text(
                                text = "Выбрана папка",
                                color = MaksColors.LavenderGlow,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Text(
                        text = folderDisplayName,
                        color = if (currentDirUri.isNotBlank()) MaksColors.TextPrimary else MaksColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { dirPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Выбрать папку", fontSize = 11.sp)
                        }
                        if (currentDirUri.isNotBlank()) {
                            FilledTonalButton(
                                onClick = { viewModel.setYandexDownloadDirUri("") },
                            ) {
                                Text("Сброс", fontSize = 11.sp)
                            }
                        }
                    }
                }

            // 4. МЕДИАТЕКА И УМНАЯ ВОЛНА
            SettingsSectionCard(
                title = "Медиатека и Умная волна",
                icon = Icons.Rounded.LibraryMusic,
                accent = MaksColors.LavenderGlow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Локальная медиатека", color = MaksColors.TextPrimary, fontSize = 14.sp)
                        Text("${player.libraryCount} треков в индексе", color = MaksColors.TextMuted, fontSize = 12.sp)
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
                        Text("Обновить", fontSize = 12.sp)
                    }
                }

                SettingsToggleRow(
                    title = "Игнорировать файлы короче 30 сек",
                    subtitle = "Короче 30 сек не попадают; при выключении — короче 15 сек",
                    checked = settings.ignoreShortTracksSec >= 30,
                    onCheckedChange = { checked ->
                        viewModel.setIgnoreShortTracksSec(if (checked) 30 else 15)
                    },
                )

                Text("Язык волны по умолчанию", color = MaksColors.TextSecondary, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Все", "russian" to "Русская", "not-russian" to "Зарубежная").forEach { (code, label) ->
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
                            text = "Чёрный список (${blacklistedEntries.size})",
                            color = MaksColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = if (blacklistExpanded) "Скрыть" else "Показать",
                        color = MaksColors.CyanAccent,
                        fontSize = 12.sp,
                    )
                }

                if (blacklistExpanded) {
                    if (blacklistedEntries.isEmpty()) {
                        Text(
                            text = "Чёрный список пуст",
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
                                            text = if (entry.isArtist) "Артист: ${entry.artist}" else entry.title,
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
                                        text = "Вернуть",
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


                SettingsDivider()

                Text(
                    text = "???? ?????? ? ????????? (JSON)",
                    color = MaksColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "?????????? ? liked_tracks.json ??????????? ?????",
                    color = MaksColors.TextMuted,
                    fontSize = 12.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch("maks_liked_tracks.json") },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.SpaceSurfaceRecessed,
                            contentColor = MaksColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("???????", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaksColors.SpaceSurfaceRecessed,
                            contentColor = MaksColors.TextPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("??????", fontSize = 12.sp)
                    }
                }
            }

            // 5. ??????? ? ??????????
            SettingsSectionCard(
                title = "??????? ? ??????????",
                icon = Icons.Rounded.BatteryChargingFull,
                accent = MaksColors.WarningAmber,
            ) {
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