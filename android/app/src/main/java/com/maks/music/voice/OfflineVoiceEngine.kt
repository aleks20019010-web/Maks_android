package com.maks.music.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

enum class VoiceEngineState {
    IDLE,
    LOADING_MODEL,
    MODEL_MISSING,
    LISTENING_WAKE,
    LISTENING_COMMAND,
    ERROR,
}

class OfflineVoiceEngine(
    private val context: Context,
    private val isTtsSpeaking: () -> Boolean,
    private val onWakeDetected: () -> Unit,
    private val onCommandRecognized: (text: String, isHotWord: Boolean) -> Unit,
    private val onListeningWindowChanged: (active: Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val _state = MutableStateFlow(VoiceEngineState.IDLE)
    val state: StateFlow<VoiceEngineState> = _state.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    @Volatile
    private var voskModel: Model? = null

    private val modelLock = Any()

    @Volatile
    private var listenJob: Job? = null

    @Volatile
    private var commandWindowUntilMs: Long = 0L

    @Volatile
    private var lastHotWordAtMs: Long = 0L

    @Volatile
    private var lastWakeAtMs: Long = 0L

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var usingHeadsetMic: Boolean = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            activeRecorder?.let { routeToPreferredMic(it) }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            activeRecorder?.let { routeToPreferredMic(it) }
        }
    }

    init {
        audioManager?.registerAudioDeviceCallback(
            audioDeviceCallback,
            Handler(Looper.getMainLooper())
        )
    }

    fun preloadModelAsync() {
        scope.launch {
            val loaded = ensureModelLoaded()
            if (loaded != null && _state.value == VoiceEngineState.LOADING_MODEL) {
                _state.value = VoiceEngineState.IDLE
            }
        }
    }

    fun startAlwaysListening() {
        if (listenJob?.isActive == true) return
        listenJob = scope.launch {
            val model = ensureModelLoaded() ?: return@launch
            runAudioLoop(model, pushToTalkOnly = false)
        }
    }

    fun triggerPushToTalk() {
        val now = System.currentTimeMillis()
        commandWindowUntilMs = now + COMMAND_WINDOW_MS
        onListeningWindowChanged(true)
        if (listenJob?.isActive != true) {
            listenJob = scope.launch {
                val model = ensureModelLoaded()
                if (model == null) {
                    commandWindowUntilMs = 0L
                    onListeningWindowChanged(false)
                    return@launch
                }
                runAudioLoop(model, pushToTalkOnly = true)
            }
        } else {
            _state.value = VoiceEngineState.LISTENING_COMMAND
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        commandWindowUntilMs = 0L
        _micLevel.value = 0f
        if (_state.value != VoiceEngineState.MODEL_MISSING) {
            _state.value = VoiceEngineState.IDLE
        }
        onListeningWindowChanged(false)
    }

    fun release() {
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
        stop()
        synchronized(modelLock) {
            runCatching { voskModel?.close() }
            voskModel = null
        }
    }

    private fun routeToPreferredMic(recorder: AudioRecord) {
        val am = audioManager ?: return
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val wiredHeadset = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val btHeadset = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        val targetMic = wiredHeadset ?: btHeadset
        if (targetMic != null) {
            recorder.setPreferredDevice(targetMic)
            usingHeadsetMic = true
        } else {
            recorder.setPreferredDevice(null)
            usingHeadsetMic = false
        }
    }

    private fun setBluetoothMicActive(active: Boolean) {
        val am = audioManager ?: return
        val hasBtSco = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        if (!hasBtSco) return

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (active) {
                    val commDevice = am.availableCommunicationDevices
                        .firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                    if (commDevice != null) am.setCommunicationDevice(commDevice)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                if (active) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
            }
        }
    }

    private fun ensureModelLoaded(): Model? = synchronized(modelLock) {
        voskModel?.let { return it }
        _state.value = VoiceEngineState.LOADING_MODEL

        val modelDir = resolveModelDirectory()
        if (modelDir == null || !isValidModelDir(modelDir)) {
            _state.value = VoiceEngineState.MODEL_MISSING
            return null
        }

        return runCatching {
            Model(modelDir.absolutePath).also { voskModel = it }
        }.onFailure {
            _state.value = VoiceEngineState.ERROR
        }.getOrNull()
    }

    private fun isValidModelDir(dir: File): Boolean =
        dir.exists() && File(dir, "am/final.mdl").exists()

    private fun resolveModelDirectory(): File? {
        val target = File(context.filesDir, "models/$MODEL_FOLDER")
        val marker = File(target, ".unpacked_ok")
        if (marker.exists() && isValidModelDir(target)) {
            return target
        }

        val candidateAssets = listOf("models/$MODEL_FOLDER", "model-ru")
        for (assetPath in candidateAssets) {
            val assetEntries = runCatching { context.assets.list(assetPath) }.getOrNull()
            if (assetEntries != null && assetEntries.isNotEmpty()) {
                val ok = runCatching {
                    if (target.exists()) target.deleteRecursively()
                    copyAssetDir(assetPath, target)
                    if (isValidModelDir(target)) {
                        marker.writeText("ok")
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
                if (ok) return target
            }
        }

        val ext = context.getExternalFilesDir(null)?.let { File(it, "models/$MODEL_FOLDER") }
        if (ext != null && isValidModelDir(ext)) {
            return ext
        }
        return null
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destDir).use { output -> input.copyTo(output) }
            }
        } else {
            destDir.mkdirs()
            for (child in children) {
                copyAssetDir("$assetPath/$child", File(destDir, child))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestRecorder(minBuf: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            runCatching { rec?.release() }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun runAudioLoop(model: Model, pushToTalkOnly: Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = createBestRecorder(minBuf)
        if (recorder == null) {
            _state.value = VoiceEngineState.ERROR
            return
        }

        activeRecorder = recorder
        routeToPreferredMic(recorder)

        var aec: AcousticEchoCanceler? = null
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        var agc: AutomaticGainControl? = null
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching {
                AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val buffer = ShortArray(2048)

        try {
            recorder.startRecording()
            _state.value = if (pushToTalkOnly) VoiceEngineState.LISTENING_COMMAND else VoiceEngineState.LISTENING_WAKE

            while (scope.isActive && listenJob?.isActive == true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                if (isTtsSpeaking()) {
                    _micLevel.value = 0f
                    continue
                }

                val currentGain = if (usingHeadsetMic) HEADSET_MIC_GAIN else TABLE_MIC_GAIN
                var sumSq = 0.0
                for (i in 0 until read) {
                    val amplified = (buffer[i] * currentGain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    buffer[i] = amplified.toShort()
                    val s = amplified.toDouble() / 32768.0
                    sumSq += s * s
                }
                val rms = sqrt(sumSq / read).toFloat()
                _micLevel.value = (rms * 4.5f).coerceIn(0f, 1f)

                val now = System.currentTimeMillis()
                val inCommandWindow = now < commandWindowUntilMs

                if (inCommandWindow && _state.value != VoiceEngineState.LISTENING_COMMAND) {
                    _state.value = VoiceEngineState.LISTENING_COMMAND
                    setBluetoothMicActive(true)
                } else if (!inCommandWindow && _state.value == VoiceEngineState.LISTENING_COMMAND) {
                    setBluetoothMicActive(false)
                    onListeningWindowChanged(false)
                    if (pushToTalkOnly) break
                    _state.value = VoiceEngineState.LISTENING_WAKE
                }

                val isFinal = recognizer.acceptWaveForm(buffer, read)
                if (isFinal) {
                    val text = extractField(recognizer.result, "text")
                    if (text.isNotEmpty()) {
                        handleRecognizedText(text, inCommandWindow)
                        if (pushToTalkOnly) {
                            commandWindowUntilMs = 0L
                            setBluetoothMicActive(false)
                            onListeningWindowChanged(false)
                            break
                        }
                    }
                } else {
                    val partial = extractField(recognizer.partialResult, "partial")
                    if (!inCommandWindow && partial.isNotEmpty()) {
                        if (partial in HOT_WORDS && (now - lastHotWordAtMs) > HOT_DEBOUNCE_MS) {
                            lastHotWordAtMs = now
                            recognizer.reset()
                            onCommandRecognized(partial, true)
                        }
                    }
                }
            }
        } finally {
            setBluetoothMicActive(false)
            activeRecorder = null
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { aec?.release() }
            runCatching { agc?.release() }
            runCatching { recognizer.close() }
            _micLevel.value = 0f
            if (_state.value != VoiceEngineState.MODEL_MISSING && _state.value != VoiceEngineState.ERROR) {
                _state.value = VoiceEngineState.IDLE
            }
            onListeningWindowChanged(false)
        }
    }

    private fun handleRecognizedText(raw: String, wasInCommandWindow: Boolean) {
        val text = raw.lowercase().trim()
        if (text.isEmpty()) return

        val afterWake = stripWakeWord(text)
        if (afterWake != null) {
            if (afterWake.isNotEmpty()) {
                commandWindowUntilMs = 0L
                setBluetoothMicActive(false)
                onListeningWindowChanged(false)
                onCommandRecognized(afterWake, false)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastWakeAtMs > WAKE_DEBOUNCE_MS) {
                    lastWakeAtMs = now
                    commandWindowUntilMs = now + COMMAND_WINDOW_MS
                    _state.value = VoiceEngineState.LISTENING_COMMAND
                    setBluetoothMicActive(true)
                    onListeningWindowChanged(true)
                    onWakeDetected()
                }
            }
            return
        }

        if (text in HOT_WORDS) {
            val now = System.currentTimeMillis()
            if (now - lastHotWordAtMs > HOT_DEBOUNCE_MS) {
                lastHotWordAtMs = now
                onCommandRecognized(text, true)
            }
            return
        }

        if (wasInCommandWindow) {
            commandWindowUntilMs = 0L
            setBluetoothMicActive(false)
            onListeningWindowChanged(false)
            onCommandRecognized(text, false)
        }
    }

    private fun stripWakeWord(text: String): String? {
        val tokens = text.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val idx = tokens.indexOfFirst { it in WAKE_WORDS }
        if (idx < 0) return null
        return tokens.drop(idx + 1).joinToString(" ").trim()
    }

    private fun extractField(jsonStr: String, field: String): String = runCatching {
        Json.parseToJsonElement(jsonStr).jsonObject[field]?.jsonPrimitive?.content?.trim().orEmpty()
    }.getOrDefault("")

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val COMMAND_WINDOW_MS = 8_000L
        private const val HOT_DEBOUNCE_MS = 1_500L
        private const val WAKE_DEBOUNCE_MS = 2_500L
        private const val TABLE_MIC_GAIN = 2.8f
        private const val HEADSET_MIC_GAIN = 1.35f
        const val MODEL_FOLDER = "vosk-model-small-ru-0.22"

        private val WAKE_WORDS = setOf("макс", "макса", "максу")
        private val HOT_WORDS = setOf(
            "дальше", "следующий", "назад", "предыдущий",
            "стоп", "пауза", "продолжи", "продолжить",
            "громче", "тише", "погромче", "потише",
            "лайк", "не нравится"
        )
    }
}