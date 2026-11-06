package com.audiomatrix.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

object ShizukuAudioController {

    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4091
    const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private const val CANONICAL_PACKAGE_NAME = "com.audiomatrix.app"
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_.]+$")

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _shizukuStatus = MutableStateFlow(evaluateCurrentStatus())
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    private val _silentBypassActive = MutableStateFlow(false)
    val silentBypassActive: StateFlow<Boolean> = _silentBypassActive.asStateFlow()

    private val _commandLogs = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    val commandLogs: StateFlow<List<CommandLogEntry>> = _commandLogs.asStateFlow()

    private val permissionGrantedCallbacks = CopyOnWriteArraySet<() -> Unit>()
    private val mutedPlayAudioUids = ConcurrentHashMap<Int, Boolean>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AudioRoutingManager.invalidateBinderCache()
        val current = evaluateCurrentStatus()
        _shizukuStatus.value = current
        appendLog(
            command = "shizuku::onBinderReceived",
            output = "Shizuku ADB binder linked (uid=${safeGetShizukuUid()})",
            isSuccess = true,
            isSimulated = false
        )
        if (current == ShizukuStatus.BINDER_ALIVE_GRANTED) {
            permissionGrantedCallbacks.forEach { it.invoke() }
        } else if (current == ShizukuStatus.BINDER_ALIVE_DENIED) {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE) }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AudioRoutingManager.invalidateBinderCache()
        _shizukuStatus.value = ShizukuStatus.BINDER_DEAD_FALLBACK
        _silentBypassActive.value = false
        appendLog(
            command = "shizuku::onBinderDead",
            output = "Shizuku ADB binder disconnected",
            isSuccess = false,
            isSimulated = true
        )
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                AudioRoutingManager.invalidateBinderCache()
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                _shizukuStatus.value = if (granted) {
                    ShizukuStatus.BINDER_ALIVE_GRANTED
                } else {
                    ShizukuStatus.BINDER_ALIVE_DENIED
                }
                appendLog(
                    command = "Shizuku.requestPermission($requestCode)",
                    output = if (granted) "PERMISSION_GRANTED (ADB UID ${safeGetShizukuUid()})" else "PERMISSION_DENIED",
                    isSuccess = granted,
                    isSimulated = false
                )
                if (granted) {
                    permissionGrantedCallbacks.forEach { it.invoke() }
                }
            }
        }

    fun initializeListeners(
        context: Context? = null,
        onPermissionGranted: (() -> Unit)? = null
    ) {
        onPermissionGranted?.let { permissionGrantedCallbacks.add(it) }
        try {
            context?.let {
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(it) }
            }
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            val status = evaluateCurrentStatus()
            _shizukuStatus.value = status
            if (status == ShizukuStatus.BINDER_ALIVE_GRANTED) {
                onPermissionGranted?.invoke()
            }
        } catch (_: Throwable) {
            _shizukuStatus.value = ShizukuStatus.BINDER_DEAD_FALLBACK
        }
    }

    fun cleanupListeners(onPermissionGranted: (() -> Unit)? = null) {
        onPermissionGranted?.let { permissionGrantedCallbacks.remove(it) }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {
        }
    }

    /**
     * Actively pulls the Shizuku binder from `moe.shizuku.privileged.api.provider`,
     * verifies binder liveness, and requests Shizuku permission.
     */
    fun checkAndRequestPermission(context: Context? = null): Boolean {
        return try {
            context?.let {
                runCatching { ShizukuProvider.requestBinderForNonProviderProcess(it) }
            }

            if (!Shizuku.pingBinder()) {
                _shizukuStatus.value = ShizukuStatus.BINDER_DEAD_FALLBACK
                val openedManager = context?.let { openShizukuManagerIfInstalled(it) } ?: false
                appendLog(
                    command = "ShizukuProvider.requestBinder + pingBinder()",
                    output = if (openedManager) {
                        "Shizuku service not running yet — opened Shizuku Manager app."
                    } else {
                        "Shizuku ADB binder not active. Start Shizuku via Wireless Debugging / ADB."
                    },
                    isSuccess = false,
                    isSimulated = true
                )
                false
            } else if (Shizuku.isPreV11()) {
                _shizukuStatus.value = ShizukuStatus.BINDER_ALIVE_DENIED
                appendLog(
                    command = "Shizuku.isPreV11()",
                    output = "Pre-v11 Shizuku is unsupported.",
                    isSuccess = false,
                    isSimulated = false
                )
                false
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _shizukuStatus.value = ShizukuStatus.BINDER_ALIVE_GRANTED
                true
            } else {
                _shizukuStatus.value = ShizukuStatus.BINDER_ALIVE_DENIED
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                false
            }
        } catch (t: Throwable) {
            _shizukuStatus.value = ShizukuStatus.BINDER_DEAD_FALLBACK
            false
        }
    }

    fun openShizukuManagerIfInstalled(context: Context): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun autoGrantSilentCapturePermissions(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isShizukuReady()) {
                _silentBypassActive.value = false
                return@withContext false
            }

            val sanitizedRuntimePkg = packageName.takeIf { PACKAGE_NAME_REGEX.matches(it) }
                ?: CANONICAL_PACKAGE_NAME

            val cmd = "appops set $sanitizedRuntimePkg PROJECT_MEDIA allow && " +
                "appops set --uid $sanitizedRuntimePkg PROJECT_MEDIA allow && " +
                "appops set $sanitizedRuntimePkg RECORD_AUDIO allow"
            val ok = executePrivilegedShellCommand(cmd)
            _silentBypassActive.value = ok
            ok
        }

    fun autoGrantSilentCapturePermissionsAsync(packageName: String) {
        controllerScope.launch {
            autoGrantSilentCapturePermissions(packageName)
        }
    }

    /**
     * Step 1 Core Feature: Simultaneous Playback / Audio Focus Lock (`TAKE_AUDIO_FOCUS`)
     * Sets BOTH package-level and UID-level (`--uid`) TAKE_AUDIO_FOCUS appops so Android 12–14+
     * AudioFocusControl never pauses or ducks the app, then verifies the applied mode with `appops get`.
     */
    suspend fun setAudioFocusLock(packageName: String, locked: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (!PACKAGE_NAME_REGEX.matches(packageName)) {
                appendLog(
                    command = "appops set $packageName TAKE_AUDIO_FOCUS",
                    output = "Rejected invalid package identifier",
                    isSuccess = false,
                    isSimulated = false
                )
                return@withContext false
            }

            val targetMode = if (locked) "ignore" else "allow"
            val compositeCmd = buildString {
                append("appops set $packageName TAKE_AUDIO_FOCUS $targetMode; ")
                append("appops set --uid $packageName TAKE_AUDIO_FOCUS $targetMode; ")
                append("cmd appops set $packageName TAKE_AUDIO_FOCUS $targetMode; ")
                append("appops get $packageName TAKE_AUDIO_FOCUS")
            }

            executePrivilegedShellCommand(compositeCmd)
        }

    /**
     * Queries the current OS `TAKE_AUDIO_FOCUS` state for `packageName` via Shizuku ADB.
     */
    suspend fun queryAudioFocusLocked(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isShizukuReady() || !PACKAGE_NAME_REGEX.matches(packageName)) {
                return@withContext false
            }
            try {
                val process = invokeShizukuNewProcess(
                    arrayOf("sh", "-c", "appops get $packageName TAKE_AUDIO_FOCUS"),
                    null,
                    null
                ) ?: return@withContext false

                val stdout = BufferedReader(InputStreamReader(process.inputStream)).use {
                    it.readText().trim()
                }
                process.waitFor()
                process.destroy()
                stdout.contains("ignore", ignoreCase = true)
            } catch (_: Throwable) {
                false
            }
        }

    suspend fun setNativeAppVolume(
        audioManager: AudioManager,
        targetUid: Int,
        volume: Float,
        packageName: String? = null,
        logChange: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val clamped = volume.coerceIn(0f, 1f)

        // 1. OS-level PLAY_AUDIO mute gate when volume is 0% (or restore when > 0%)
        val cleanPkg = packageName?.trim().orEmpty()
        if (cleanPkg.isNotEmpty() && PACKAGE_NAME_REGEX.matches(cleanPkg) && isShizukuReady()) {
            if (clamped <= 0.001f) {
                mutedPlayAudioUids[targetUid] = true
                executePrivilegedShellCommand(
                    "appops set $cleanPkg PLAY_AUDIO ignore; appops set --uid $cleanPkg PLAY_AUDIO ignore"
                )
            } else if (mutedPlayAudioUids.remove(targetUid) == true) {
                executePrivilegedShellCommand(
                    "appops set $cleanPkg PLAY_AUDIO allow; appops set --uid $cleanPkg PLAY_AUDIO allow"
                )
            }
        }

        // 2. Delegate 0ms IPlayer Binder volume scaling to AudioRoutingManager
        val volumeResult = AudioRoutingManager.setAppPlayerVolumeZeroLag(
            audioManager = audioManager,
            targetUid = targetUid,
            volume = clamped
        )

        if (logChange) {
            appendLog(
                command = volumeResult.commandSummary,
                output = volumeResult.details,
                isSuccess = volumeResult.isSuccess,
                isSimulated = volumeResult.isSimulated
            )
        }

        volumeResult.appliedPiids.isNotEmpty()
    }

    suspend fun setNativeDeviceVolume(
        audioManager: AudioManager,
        device: OutputDevice,
        volume: Float,
        isMuted: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val effectiveVolume = if (isMuted) 0f else volume.coerceIn(0f, 1f)
        return@withContext try {
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val targetIndex = (effectiveVolume * maxMusic).roundToInt().coerceIn(0, maxMusic)

            val iAudio = AudioRoutingManager.getPrivilegedIAudioService()
            if (iAudio != null && device.isBluetooth) {
                val setDeviceVolumeMethod = iAudio.javaClass.methods.firstOrNull {
                    it.name == "setBluetoothA2dpDeviceVolume" || it.name == "setAvrcpAbsoluteVolume"
                }
                if (setDeviceVolumeMethod != null && setDeviceVolumeMethod.parameterTypes.size == 1) {
                    val btVol = (effectiveVolume * 127).roundToInt().coerceIn(0, 127)
                    runCatching { setDeviceVolumeMethod.invoke(iAudio, btVol) }
                }
            }

            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun setNativeChannelSplitMode(
        audioManager: AudioManager,
        device: OutputDevice,
        mode: ChannelMode
    ): Boolean = withContext(Dispatchers.IO) {
        val panValue = when (mode) {
            ChannelMode.STEREO -> 0.0f
            ChannelMode.LEFT_ONLY -> -1.0f
            ChannelMode.RIGHT_ONLY -> 1.0f
        }

        AudioRoutingManager.setActivePlayersPanZeroLag(audioManager, panValue)
        val cmd = "settings put system master_balance $panValue"
        executePrivilegedShellCommand(cmd)
    }

    suspend fun setNativeUidDeviceAffinity(
        uid: Int,
        device: OutputDevice,
        audioManager: AudioManager? = null,
        logChange: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val routingResult = AudioRoutingManager.routeUidToDeviceZeroLag(
            uid = uid,
            device = device,
            audioManager = audioManager
        )
        if (logChange) {
            appendLog(
                command = routingResult.commandSummary,
                output = routingResult.details,
                isSuccess = routingResult.isSuccess,
                isSimulated = routingResult.isSimulated
            )
        }
        routingResult.isSuccess
    }

    suspend fun clearNativeUidDeviceAffinity(uid: Int): Boolean = withContext(Dispatchers.IO) {
        val clearResult = AudioRoutingManager.clearUidDeviceRoute(uid)
        appendLog(
            command = clearResult.commandSummary,
            output = clearResult.details,
            isSuccess = clearResult.isSuccess,
            isSimulated = clearResult.isSimulated
        )
        clearResult.isSuccess
    }

    suspend fun forceDualBluetoothA2dp(
        enable: Boolean,
        audioManager: AudioManager? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val flagValue = if (enable) "true" else "false"
        val numericValue = if (enable) "1" else "0"
        val compositeCommand = buildString {
            append("setprop persist.bluetooth.a2dp.dual_audio $flagValue; ")
            append("setprop persist.vendor.bt.a2dp.dual_audio $flagValue; ")
            append("settings put global bluetooth_dual_audio_state $numericValue")
        }

        val shellSucceeded = executePrivilegedShellCommand(compositeCommand)

        if (audioManager != null) {
            runCatching {
                audioManager.setParameters("dual_audio_mode=$flagValue;bt_multi_sink=$flagValue")
            }
        }

        shellSucceeded
    }

    suspend fun getActivePlaybackUids(audioManager: AudioManager): Set<Int> =
        AudioRoutingManager.getActivePlaybackUids(audioManager)

    private fun executePrivilegedShellCommand(shellCmd: String): Boolean {
        if (isShizukuReady()) {
            return try {
                val process = invokeShizukuNewProcess(
                    arrayOf("sh", "-c", shellCmd),
                    null,
                    null
                )
                if (process == null) {
                    appendLog(
                        command = shellCmd,
                        output = "Shizuku.newProcess unavailable",
                        isSuccess = false,
                        isSimulated = true
                    )
                    return false
                }
                val stdout = BufferedReader(InputStreamReader(process.inputStream)).use {
                    it.readText().trim()
                }
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).use {
                    it.readText().trim()
                }
                val exitCode = process.waitFor()
                process.destroy()
                val combinedOutput = when {
                    stdout.isNotEmpty() && stderr.isNotEmpty() -> "$stdout | $stderr"
                    stdout.isNotEmpty() -> stdout
                    stderr.isNotEmpty() -> stderr
                    else -> "exitCode=$exitCode (ADB OK)"
                }
                appendLog(
                    command = shellCmd,
                    output = combinedOutput,
                    isSuccess = exitCode == 0,
                    isSimulated = false
                )
                exitCode == 0
            } catch (t: Throwable) {
                appendLog(
                    command = shellCmd,
                    output = "Shizuku error: ${t.message ?: t.javaClass.simpleName}",
                    isSuccess = false,
                    isSimulated = false
                )
                false
            }
        } else {
            appendLog(
                command = shellCmd,
                output = "[Shizuku Not Connected] Grant Shizuku ADB to execute on system",
                isSuccess = false,
                isSimulated = true
            )
            return false
        }
    }

    private fun invokeShizukuNewProcess(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?
    ): ShizukuRemoteProcess? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, env, dir) as? ShizukuRemoteProcess
        } catch (_: Throwable) {
            null
        }
    }

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun evaluateCurrentStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.BINDER_DEAD_FALLBACK
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.BINDER_ALIVE_GRANTED
            } else {
                ShizukuStatus.BINDER_ALIVE_DENIED
            }
        } catch (_: Throwable) {
            ShizukuStatus.BINDER_DEAD_FALLBACK
        }
    }

    fun safeGetShizukuUid(): Int {
        return try {
            Shizuku.getUid()
        } catch (_: Throwable) {
            -1
        }
    }

    fun clearCommandLogs() {
        _commandLogs.value = emptyList()
    }

    private fun appendLog(
        command: String,
        output: String,
        isSuccess: Boolean,
        isSimulated: Boolean
    ) {
        val entry = CommandLogEntry(
            timestampMs = System.currentTimeMillis(),
            command = command,
            output = output,
            isSuccess = isSuccess,
            isSimulated = isSimulated
        )
        _commandLogs.value = (listOf(entry) + _commandLogs.value).take(50)
    }
}
