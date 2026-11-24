package com.maks.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.maks.music.media.MaksPlaybackService
import com.maks.music.ui.MaksScreen
import com.maks.music.ui.MaksTheme
import com.maks.music.ui.MaksViewModel
import com.maks.music.voice.VoiceForegroundService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MaksViewModel by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var hasAudioPermission by mutableStateOf(false)
    private var askedAudioOnce = false
    private var pendingPushToTalk = false
    private var pendingStartVoiceService = false

    private val initialPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioPerm = audioPermissionName()
        hasAudioPermission = hasPermission(audioPerm)
        if (hasAudioPermission || results[audioPerm] == true) {
            viewModel.refreshLibrary()
        }
        if (pendingPushToTalk && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingPushToTalk = false
            viewModel.triggerPushToTalk()
        }
        if (pendingStartVoiceService && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingStartVoiceService = false
            toggleVoiceService(true)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted || hasPermission(audioPermissionName())
        if (hasAudioPermission) {
            viewModel.refreshLibrary()
        }
    }

    private val voicePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            if (pendingPushToTalk) {
                pendingPushToTalk = false
                viewModel.triggerPushToTalk()
            }
            if (pendingStartVoiceService) {
                pendingStartVoiceService = false
                toggleVoiceService(true)
            }
        } else {
            pendingPushToTalk = false
            pendingStartVoiceService = false
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addMusicFolder(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        hasAudioPermission = hasPermission(audioPermissionName())

        lifecycleScope.launch {
            (application as MaksApplication).exitAppEvents.collect {
                finishAndRemoveTask()
            }
        }

        setContent {
            MaksTheme {
                MaksScreen(
                    viewModel = viewModel,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = { requestAudioPermissionOrOpenSettings() },
                    onPickMusicFolder = { openMusicFolderPicker() },
                    onPushToTalk = { triggerPushToTalkWithPermission() },
                    onToggleAlwaysListening = { enabled -> toggleVoiceService(enabled) },
                    onShutdownApp = {
                        viewModel.shutdownApp()
                        finishAndRemoveTask()
                    },
                )
            }
        }

        window.decorView.post {
            requestInitialPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            val sessionToken = SessionToken(this, ComponentName(this, MaksPlaybackService::class.java))
            controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        }
    }

    override fun onResume() {
        super.onResume()
        val nowGranted = hasPermission(audioPermissionName())
        val changed = nowGranted != hasAudioPermission
        hasAudioPermission = nowGranted
        if (nowGranted && (changed || viewModel.playerState.value.libraryCount == 0)) {
            viewModel.refreshLibrary()
        }
    }

    override fun onStop() {
        controllerFuture?.let { future ->
            runCatching { MediaController.releaseFuture(future) }
        }
        controllerFuture = null
        super.onStop()
    }

    private fun requestInitialPermissions() {
        val needed = mutableListOf<String>()
        val audioPerm = audioPermissionName()
        if (!hasPermission(audioPerm)) {
            needed += audioPerm
            askedAudioOnce = true
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            runCatching { initialPermissionsLauncher.launch(needed.toTypedArray()) }
        } else {
            viewModel.refreshLibrary()
        }
    }

    private fun requestAudioPermissionOrOpenSettings() {
        val audioPerm = audioPermissionName()
        if (hasPermission(audioPerm)) {
            hasAudioPermission = true
            viewModel.refreshLibrary()
            return
        }

        val canShowDialog = !askedAudioOnce || ActivityCompat.shouldShowRequestPermissionRationale(this, audioPerm)
        if (canShowDialog) {
            askedAudioOnce = true
            runCatching { audioPermissionLauncher.launch(audioPerm) }
        } else {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            startActivity(intent)
        }
    }

    private fun openMusicFolderPicker() {
        runCatching {
            folderPickerLauncher.launch(null)
        }
    }

    private fun missingVoicePermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            missing += Manifest.permission.BLUETOOTH_CONNECT
        }
        return missing
    }

    private fun triggerPushToTalkWithPermission() {
        val missing = missingVoicePermissions()
        if (hasPermission(Manifest.permission.RECORD_AUDIO) && missing.isEmpty()) {
            viewModel.triggerPushToTalk()
        } else if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            pendingPushToTalk = true
            runCatching { voicePermissionsLauncher.launch(missing.toTypedArray()) }
        } else {
            runCatching { voicePermissionsLauncher.launch(missing.toTypedArray()) }
            viewModel.triggerPushToTalk()
        }
    }

    private fun toggleVoiceService(enable: Boolean) {
        if (enable) {
            val missing = missingVoicePermissions()
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                pendingStartVoiceService = true
                runCatching { voicePermissionsLauncher.launch(missing.toTypedArray()) }
                return
            } else if (missing.isNotEmpty()) {
                runCatching { voicePermissionsLauncher.launch(missing.toTypedArray()) }
            }
        }
        val intent = Intent(this, VoiceForegroundService::class.java).apply {
            action = if (enable) VoiceForegroundService.ACTION_START else VoiceForegroundService.ACTION_STOP
        }
        runCatching {
            if (enable) {
                ContextCompat.startForegroundService(this, intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun audioPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}