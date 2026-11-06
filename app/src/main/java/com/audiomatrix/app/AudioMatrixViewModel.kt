package com.audiomatrix.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class AudioMatrixUiState(
    val connectedOutputs: List<OutputDevice> = emptyList(),
    val activeStreams: List<ActiveAppStream> = emptyList(),
    val installedApps: List<ActiveAppStream> = emptyList(),
    val isEngineRunning: Boolean = false,
    val dualBluetoothShareEnabled: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.BINDER_DEAD_FALLBACK,
    val silentBypassActive: Boolean = false,
    val telemetry: DspTelemetry = DspTelemetry(),
    val commandLogs: List<CommandLogEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val permissions: RuntimePermissionStatus = RuntimePermissionStatus(),
    val statusBannerMessage: String? = null
)

class AudioMatrixViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val DUAL_SHARE_MIX_DEVICE_ID = -999
        private const val PREFS_NAME = "audiomatrix_settings_prefs"

        private val ALLOWED_SINK_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )
    }

    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val uidAppInfoCache = ConcurrentHashMap<Int, Pair<String, String>>()
    private val volumeDispatchJobs = ConcurrentHashMap<Int, Job>()

    private val _connectedOutputs = MutableStateFlow<List<OutputDevice>>(emptyList())
    val connectedOutputs: StateFlow<List<OutputDevice>> = _connectedOutputs.asStateFlow()

    private val _activeStreams = MutableStateFlow<List<ActiveAppStream>>(emptyList())
    val activeStreams: StateFlow<List<ActiveAppStream>> = _activeStreams.asStateFlow()

    private val _installedApps = MutableStateFlow<List<ActiveAppStream>>(emptyList())
    val installedApps: StateFlow<List<ActiveAppStream>> = _installedApps.asStateFlow()

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning: StateFlow<Boolean> = _isEngineRunning.asStateFlow()

    private val _settings = MutableStateFlow(loadPersistedSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _permissions = MutableStateFlow(evaluateRuntimePermissions())
    val permissions: StateFlow<RuntimePermissionStatus> = _permissions.asStateFlow()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)

    private val permissionGrantedCallback: () -> Unit = {
        if (_settings.value.autoSilentBypassOnLaunch) {
            ShizukuAudioController.autoGrantSilentCapturePermissionsAsync(appContext.packageName)
        }
        scanActiveAppStreams()
        syncActualFocusLockStates()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scanConnectedOutputs()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scanConnectedOutputs()
        }
    }

    private val audioPlaybackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                scanActiveAppStreams()
            }
        }
    } else {
        null
    }

    val uiState: StateFlow<AudioMatrixUiState> = combine(
        _connectedOutputs,
        _activeStreams,
        _installedApps,
        _isEngineRunning,
        ShizukuAudioController.shizukuStatus
    ) { outputs, streams, installed, running, shizuku ->
        val dualShareCount = outputs.count { it.isEnabledForDualShare }
        AudioMatrixUiState(
            connectedOutputs = outputs,
            activeStreams = streams,
            installedApps = installed,
            isEngineRunning = running,
            dualBluetoothShareEnabled = dualShareCount >= 2,
            shizukuStatus = shizuku
        )
    }.combine(
        combine(
            ShizukuAudioController.silentBypassActive,
            AudioRouterService.telemetry,
            ShizukuAudioController.commandLogs,
            _settings,
            _permissions
        ) { silentBypass, telemetry, logs, settings, perms ->
            SettingsAndTelemetryBundle(silentBypass, telemetry, logs, settings, perms)
        }
    ) { baseState, bundle ->
        baseState.copy(
            silentBypassActive = bundle.silentBypass,
            isEngineRunning = baseState.isEngineRunning || bundle.telemetry.isCapturing,
            telemetry = bundle.telemetry,
            commandLogs = bundle.logs,
            settings = bundle.settings,
            permissions = bundle.permissions
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AudioMatrixUiState(
            settings = _settings.value,
            permissions = _permissions.value
        )
    )

    private data class SettingsAndTelemetryBundle(
        val silentBypass: Boolean,
        val telemetry: DspTelemetry,
        val logs: List<CommandLogEntry>,
        val settings: AppSettings,
        val permissions: RuntimePermissionStatus
    )

    init {
        AudioRouterService.updateEngineTuning(
            enableHybridZeroLagHal = _settings.value.hybridZeroLagHalRouting,
            framesPerBurstOverride = _settings.value.bufferBurstPreset.framesPerBurst
        )
        ShizukuAudioController.initializeListeners(appContext, permissionGrantedCallback)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            audioManager.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
        }
        refreshRuntimePermissions()
        scanConnectedOutputs()
        loadInstalledApplicationsCatalog()
        scanActiveAppStreams()
    }

    fun refreshRuntimePermissions() {
        _permissions.value = evaluateRuntimePermissions()
    }

    private fun evaluateRuntimePermissions(): RuntimePermissionStatus {
        val recordGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return RuntimePermissionStatus(
            recordAudioGranted = recordGranted,
            bluetoothConnectGranted = btGranted,
            postNotificationsGranted = notifGranted
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    fun setUseDynamicColor(enabled: Boolean) {
        updateSettings { it.copy(useDynamicColor = enabled) }
    }

    fun setAmoledPitchBlack(enabled: Boolean) {
        updateSettings { it.copy(amoledPitchBlack = enabled) }
    }

    fun setAutoSilentBypassOnLaunch(enabled: Boolean) {
        updateSettings { it.copy(autoSilentBypassOnLaunch = enabled) }
        if (enabled) {
            ShizukuAudioController.autoGrantSilentCapturePermissionsAsync(appContext.packageName)
        }
    }

    fun setHybridZeroLagHalRouting(enabled: Boolean) {
        updateSettings { it.copy(hybridZeroLagHalRouting = enabled) }
        AudioRouterService.updateEngineTuning(
            enableHybridZeroLagHal = enabled,
            framesPerBurstOverride = _settings.value.bufferBurstPreset.framesPerBurst
        )
    }

    fun setBufferBurstPreset(preset: BufferBurstPreset) {
        updateSettings { it.copy(bufferBurstPreset = preset) }
        AudioRouterService.updateEngineTuning(
            enableHybridZeroLagHal = _settings.value.hybridZeroLagHalRouting,
            framesPerBurstOverride = preset.framesPerBurst
        )
    }

    fun setAutoFocusLockNewApps(enabled: Boolean) {
        updateSettings { it.copy(autoFocusLockNewApps = enabled) }
    }

    fun setKeepAliveOnScreenOff(enabled: Boolean) {
        updateSettings { it.copy(keepAliveOnScreenOff = enabled) }
    }

    fun runSilentBypassGrantNow() {
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuAudioController.autoGrantSilentCapturePermissions(appContext.packageName)
        }
    }

    fun resetAllRoutingAndFocusLocks() {
        val defaultSinkId = _connectedOutputs.value.firstOrNull()?.id
        _connectedOutputs.value = _connectedOutputs.value.map { dev ->
            dev.copy(
                volume = 1.0f,
                isMuted = false,
                channelMode = ChannelMode.STEREO,
                isEnabledForDualShare = false
            )
        }
        val currentStreams = _activeStreams.value
        _activeStreams.value = currentStreams.map { stream ->
            stream.copy(
                assignedDeviceId = defaultSinkId,
                volume = 1.0f,
                focusLocked = false
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (stream in currentStreams) {
                ShizukuAudioController.clearNativeUidDeviceAffinity(stream.uid)
                ShizukuAudioController.setNativeAppVolume(audioManager, stream.uid, 1.0f)
                ShizukuAudioController.setAudioFocusLock(stream.packageName, false)
            }
            _connectedOutputs.value.firstOrNull()?.let { firstDev ->
                ShizukuAudioController.setNativeChannelSplitMode(
                    audioManager,
                    firstDev,
                    ChannelMode.STEREO
                )
            }
            ShizukuAudioController.forceDualBluetoothA2dp(false, audioManager)
        }
    }

    fun clearShizukuLogs() {
        ShizukuAudioController.clearCommandLogs()
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit()
            .putString("theme_mode", updated.themeMode.name)
            .putBoolean("use_dynamic_color", updated.useDynamicColor)
            .putBoolean("amoled_pitch_black", updated.amoledPitchBlack)
            .putBoolean("auto_silent_bypass", updated.autoSilentBypassOnLaunch)
            .putBoolean("hybrid_zero_lag_hal", updated.hybridZeroLagHalRouting)
            .putString("buffer_burst_preset", updated.bufferBurstPreset.name)
            .putBoolean("auto_focus_lock_new_apps", updated.autoFocusLockNewApps)
            .putBoolean("keep_alive_screen_off", updated.keepAliveOnScreenOff)
            .apply()
    }

    private fun loadPersistedSettings(): AppSettings {
        val themeName = prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
        val burstName = prefs.getString("buffer_burst_preset", BufferBurstPreset.BALANCED_192.name)
            ?: BufferBurstPreset.BALANCED_192.name

        return AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.DARK),
            useDynamicColor = prefs.getBoolean("use_dynamic_color", true),
            amoledPitchBlack = prefs.getBoolean("amoled_pitch_black", false),
            autoSilentBypassOnLaunch = prefs.getBoolean("auto_silent_bypass", true),
            hybridZeroLagHalRouting = prefs.getBoolean("hybrid_zero_lag_hal", true),
            bufferBurstPreset = runCatching { BufferBurstPreset.valueOf(burstName) }
                .getOrDefault(BufferBurstPreset.BALANCED_192),
            autoFocusLockNewApps = prefs.getBoolean("auto_focus_lock_new_apps", false),
            keepAliveOnScreenOff = prefs.getBoolean("keep_alive_screen_off", true)
        )
    }

    fun scanConnectedOutputs() {
        viewModelScope.launch(Dispatchers.IO) {
            val previousById = _connectedOutputs.value.associateBy { it.id }
            val rawDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val deduplicated = LinkedHashMap<String, OutputDevice>()

            for (info in rawDevices) {
                if (!info.isSink || info.type !in ALLOWED_SINK_TYPES) continue

                val rawAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.address.orEmpty().trim()
                } else {
                    ""
                }
                val dedupKey = if (rawAddress.isNotEmpty()) {
                    "${info.type}_$rawAddress"
                } else {
                    "${info.type}_${info.id}"
                }

                if (deduplicated.containsKey(dedupKey)) continue

                val existing = previousById[info.id]
                val displayName = resolveHardwareDisplayName(info, rawAddress)
                val displayAddress = rawAddress.ifEmpty { "SINK-${info.id}" }

                deduplicated[dedupKey] = OutputDevice(
                    id = info.id,
                    name = displayName,
                    type = info.type,
                    address = displayAddress,
                    volume = existing?.volume ?: 0.85f,
                    isMuted = existing?.isMuted ?: false,
                    channelMode = existing?.channelMode ?: ChannelMode.STEREO,
                    isEnabledForDualShare = existing?.isEnabledForDualShare ?: false
                )
            }

            if (deduplicated.isEmpty()) {
                val prevSpeaker = previousById[1]
                deduplicated["${AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}_1"] = OutputDevice(
                    id = 1,
                    name = "Phone Stereo Speaker",
                    type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    address = "INTERNAL_SPK",
                    volume = prevSpeaker?.volume ?: 0.85f,
                    isMuted = prevSpeaker?.isMuted ?: false,
                    channelMode = prevSpeaker?.channelMode ?: ChannelMode.STEREO,
                    isEnabledForDualShare = prevSpeaker?.isEnabledForDualShare ?: false
                )
            }

            _connectedOutputs.value = deduplicated.values.toList()
        }
    }

    /**
     * Loads real installed user and media apps from PackageManager on Dispatchers.IO
     * so the user can pick any app on their phone for Audio Focus Lock & Per-App Routing.
     */
    private fun loadInstalledApplicationsCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = appContext.packageManager
            val defaultDeviceId = _connectedOutputs.value.firstOrNull()?.id
            val catalog = runCatching {
                pm.getInstalledApplications(0)
                    .filter { info ->
                        info.packageName != appContext.packageName &&
                            info.packageName != ShizukuAudioController.SHIZUKU_MANAGER_PACKAGE &&
                            ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
                                pm.getLaunchIntentForPackage(info.packageName) != null)
                    }
                    .map { info ->
                        val label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName)
                        uidAppInfoCache[info.uid] = info.packageName to label
                        ActiveAppStream(
                            uid = info.uid,
                            packageName = info.packageName,
                            appName = label,
                            assignedDeviceId = defaultDeviceId,
                            volume = 1.0f,
                            focusLocked = false
                        )
                    }
                    .distinctBy { it.packageName }
                    .sortedBy { it.appName.lowercase() }
            }.getOrDefault(emptyList())

            _installedApps.value = catalog

            // Populate initial streams from real installed apps if none are active yet
            if (_activeStreams.value.isEmpty() && catalog.isNotEmpty()) {
                val preferredKeywords = listOf(
                    "music", "spotify", "youtube", "tiktok", "musically",
                    "vlc", "player", "audio", "chrome", "instagram", "game"
                )
                val prioritized = catalog.sortedByDescending { app ->
                    preferredKeywords.any { kw ->
                        app.packageName.contains(kw, ignoreCase = true) ||
                            app.appName.contains(kw, ignoreCase = true)
                    }
                }.take(5)
                _activeStreams.value = prioritized
                syncActualFocusLockStates()
            }
        }
    }

    fun addAppToRouterList(app: ActiveAppStream) {
        if (_activeStreams.value.any { it.packageName == app.packageName }) return
        val defaultDeviceId = _connectedOutputs.value.firstOrNull()?.id
        val stream = app.copy(assignedDeviceId = app.assignedDeviceId ?: defaultDeviceId)
        _activeStreams.value = listOf(stream) + _activeStreams.value
        viewModelScope.launch(Dispatchers.IO) {
            val isLocked = ShizukuAudioController.queryAudioFocusLocked(stream.packageName)
            if (isLocked != stream.focusLocked) {
                _activeStreams.value = _activeStreams.value.map {
                    if (it.packageName == stream.packageName) it.copy(focusLocked = isLocked) else it
                }
            }
        }
    }

    private fun syncActualFocusLockStates() {
        if (!ShizukuAudioController.isShizukuReady()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = _activeStreams.value
            val updated = current.map { stream ->
                val lockedInOs = ShizukuAudioController.queryAudioFocusLocked(stream.packageName)
                stream.copy(focusLocked = lockedInOs)
            }
            _activeStreams.value = updated
        }
    }

    fun scanActiveAppStreams() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentStreamsByPkg = _activeStreams.value.associateBy { it.packageName }
            val activeUids = ShizukuAudioController.getActivePlaybackUids(audioManager)
            val pm = appContext.packageManager
            val discovered = mutableListOf<ActiveAppStream>()
            val autoLockDefault = _settings.value.autoFocusLockNewApps

            for (uid in activeUids) {
                if (uid == android.os.Process.myUid()) continue
                val (pkgName, appName) = uidAppInfoCache.getOrPut(uid) {
                    val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid.$uid"
                    val label = try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Throwable) {
                        pkg.substringAfterLast('.')
                    }
                    pkg to label
                }
                if (pkgName.startsWith("uid.")) continue

                val prev = currentStreamsByPkg[pkgName]
                val focusLocked = prev?.focusLocked
                    ?: if (ShizukuAudioController.isShizukuReady()) {
                        ShizukuAudioController.queryAudioFocusLocked(pkgName)
                    } else {
                        autoLockDefault
                    }

                discovered.add(
                    ActiveAppStream(
                        uid = uid,
                        packageName = pkgName,
                        appName = appName,
                        assignedDeviceId = prev?.assignedDeviceId
                            ?: _connectedOutputs.value.firstOrNull()?.id,
                        volume = prev?.volume ?: 1.0f,
                        focusLocked = focusLocked
                    )
                )
            }

            if (discovered.isNotEmpty()) {
                val discoveredPkgs = discovered.map { it.packageName }.toSet()
                val retained = _activeStreams.value.filter { it.packageName !in discoveredPkgs }
                _activeStreams.value = discovered + retained

                // Re-apply configured IPlayer volume and UID device affinity to newly spawned players on track change
                if (ShizukuAudioController.isShizukuReady()) {
                    val outputsById = _connectedOutputs.value.associateBy { it.id }
                    for (stream in discovered) {
                        if (stream.volume < 0.999f) {
                            ShizukuAudioController.setNativeAppVolume(
                                audioManager = audioManager,
                                targetUid = stream.uid,
                                volume = stream.volume,
                                packageName = stream.packageName,
                                logChange = false
                            )
                        }
                        val targetDev = stream.assignedDeviceId?.let { outputsById[it] }
                        if (targetDev != null) {
                            ShizukuAudioController.setNativeUidDeviceAffinity(
                                uid = stream.uid,
                                device = targetDev,
                                audioManager = audioManager,
                                logChange = false
                            )
                        }
                    }
                }
            } else if (_activeStreams.value.isEmpty() && _installedApps.value.isEmpty()) {
                val defaultDeviceId = _connectedOutputs.value.firstOrNull()?.id
                _activeStreams.value = listOf(
                    ActiveAppStream(
                        uid = 10142,
                        packageName = "com.google.android.apps.youtube.music",
                        appName = "YouTube Music",
                        assignedDeviceId = defaultDeviceId,
                        volume = 1.0f,
                        focusLocked = false
                    ),
                    ActiveAppStream(
                        uid = 10188,
                        packageName = "com.activision.callofduty.shooter",
                        appName = "Call of Duty Mobile",
                        assignedDeviceId = defaultDeviceId,
                        volume = 1.0f,
                        focusLocked = false
                    ),
                    ActiveAppStream(
                        uid = 10215,
                        packageName = "com.zhiliaoapp.musically",
                        appName = "TikTok",
                        assignedDeviceId = defaultDeviceId,
                        volume = 1.0f,
                        focusLocked = false
                    )
                )
            }
        }
    }

    fun requestShizukuPermission() {
        val granted = ShizukuAudioController.checkAndRequestPermission(appContext)
        if (granted) {
            viewModelScope.launch(Dispatchers.IO) {
                ShizukuAudioController.autoGrantSilentCapturePermissions(appContext.packageName)
                scanActiveAppStreams()
                syncActualFocusLockStates()
            }
        }
    }

    fun prepareAndStartEngine(onLaunchProjection: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isEngineRunning.value = true
            val shizukuReady = ShizukuAudioController.isShizukuReady()

            if (shizukuReady) {
                ShizukuAudioController.autoGrantSilentCapturePermissions(appContext.packageName)
            }

            val outputsById = _connectedOutputs.value.associateBy { it.id }
            for (device in _connectedOutputs.value) {
                if (device.channelMode != ChannelMode.STEREO) {
                    ShizukuAudioController.setNativeChannelSplitMode(
                        audioManager,
                        device,
                        device.channelMode
                    )
                }
            }

            for (stream in _activeStreams.value) {
                val targetDev = stream.assignedDeviceId?.let { outputsById[it] }
                    ?: _connectedOutputs.value.firstOrNull()
                if (targetDev != null) {
                    ShizukuAudioController.setNativeUidDeviceAffinity(
                        uid = stream.uid,
                        device = targetDev,
                        audioManager = audioManager,
                        logChange = true
                    )
                }
                if (stream.volume < 0.999f) {
                    ShizukuAudioController.setNativeAppVolume(
                        audioManager = audioManager,
                        targetUid = stream.uid,
                        volume = stream.volume,
                        packageName = stream.packageName,
                        logChange = true
                    )
                }
                if (stream.focusLocked) {
                    ShizukuAudioController.setAudioFocusLock(stream.packageName, true)
                }
            }

            val dualShareDevices = _connectedOutputs.value.filter { it.isEnabledForDualShare }
            if (dualShareDevices.size >= 2) {
                ShizukuAudioController.forceDualBluetoothA2dp(true, audioManager)
            }
        }
    }

    fun onProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(appContext, AudioRouterService::class.java).apply {
                action = AudioRouterService.ACTION_START_CAPTURE
                putExtra(AudioRouterService.EXTRA_RESULT_CODE, resultCode)
                putExtra(AudioRouterService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            _isEngineRunning.value = true
        }
    }

    fun stopRoutingEngine() {
        _isEngineRunning.value = false
        val stopIntent = Intent(appContext, AudioRouterService::class.java).apply {
            action = AudioRouterService.ACTION_STOP_CAPTURE
        }
        runCatching { appContext.startService(stopIntent) }
    }

    fun toggleDualShareDevice(deviceId: Int) {
        var updatedDualShareCount = 0
        _connectedOutputs.value = _connectedOutputs.value.map { device ->
            val updated = if (device.id == deviceId) {
                device.copy(isEnabledForDualShare = !device.isEnabledForDualShare)
            } else {
                device
            }
            if (updated.isEnabledForDualShare) updatedDualShareCount++
            updated
        }

        viewModelScope.launch(Dispatchers.IO) {
            ShizukuAudioController.forceDualBluetoothA2dp(
                enable = updatedDualShareCount >= 2,
                audioManager = audioManager
            )
        }
    }

    fun setDeviceVolume(deviceId: Int, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        var targetDevice: OutputDevice? = null
        _connectedOutputs.value = _connectedOutputs.value.map { device ->
            if (device.id == deviceId) {
                val updated = device.copy(
                    volume = clamped,
                    isMuted = if (clamped > 0f) false else device.isMuted
                )
                targetDevice = updated
                updated
            } else {
                device
            }
        }

        val dev = targetDevice ?: return
        volumeDispatchJobs[deviceId]?.cancel()
        volumeDispatchJobs[deviceId] = viewModelScope.launch(Dispatchers.IO) {
            delay(25)
            ShizukuAudioController.setNativeDeviceVolume(
                audioManager = audioManager,
                device = dev,
                volume = dev.volume,
                isMuted = dev.isMuted
            )
        }
    }

    fun toggleDeviceMute(deviceId: Int) {
        var targetDevice: OutputDevice? = null
        _connectedOutputs.value = _connectedOutputs.value.map { device ->
            if (device.id == deviceId) {
                val updated = device.copy(isMuted = !device.isMuted)
                targetDevice = updated
                updated
            } else {
                device
            }
        }

        val dev = targetDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuAudioController.setNativeDeviceVolume(
                audioManager = audioManager,
                device = dev,
                volume = dev.volume,
                isMuted = dev.isMuted
            )
        }
    }

    fun setDeviceChannelMode(deviceId: Int, mode: ChannelMode) {
        var targetDevice: OutputDevice? = null
        _connectedOutputs.value = _connectedOutputs.value.map { device ->
            if (device.id == deviceId) {
                val updated = device.copy(channelMode = mode)
                targetDevice = updated
                updated
            } else {
                device
            }
        }

        val dev = targetDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuAudioController.setNativeChannelSplitMode(
                audioManager = audioManager,
                device = dev,
                mode = mode
            )
        }
    }

    fun routeAppToDevice(uid: Int, deviceId: Int?) {
        _activeStreams.value = _activeStreams.value.map { stream ->
            if (stream.uid == uid) stream.copy(assignedDeviceId = deviceId) else stream
        }

        viewModelScope.launch(Dispatchers.IO) {
            when (deviceId) {
                null -> {
                    ShizukuAudioController.clearNativeUidDeviceAffinity(uid)
                }
                DUAL_SHARE_MIX_DEVICE_ID -> {
                    val currentOutputs = _connectedOutputs.value
                    if (currentOutputs.count { it.isEnabledForDualShare } < 2) {
                        _connectedOutputs.value = currentOutputs.mapIndexed { index, dev ->
                            if (index < 2) dev.copy(isEnabledForDualShare = true) else dev
                        }
                    }
                    ShizukuAudioController.forceDualBluetoothA2dp(true, audioManager)
                }
                else -> {
                    val targetDevice = _connectedOutputs.value.firstOrNull { it.id == deviceId }
                        ?: _connectedOutputs.value.firstOrNull()
                    if (targetDevice != null) {
                        ShizukuAudioController.setNativeUidDeviceAffinity(
                            uid = uid,
                            device = targetDevice,
                            audioManager = audioManager,
                            logChange = true
                        )
                    }
                }
            }
        }
    }

    fun setAppVolume(uid: Int, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        var targetPkg: String? = null
        _activeStreams.value = _activeStreams.value.map { stream ->
            if (stream.uid == uid) {
                targetPkg = stream.packageName
                stream.copy(volume = clamped)
            } else {
                stream
            }
        }

        val jobKey = uid + 1_000_000
        volumeDispatchJobs[jobKey]?.cancel()
        volumeDispatchJobs[jobKey] = viewModelScope.launch(Dispatchers.IO) {
            delay(25)
            ShizukuAudioController.setNativeAppVolume(
                audioManager = audioManager,
                targetUid = uid,
                volume = clamped,
                packageName = targetPkg,
                logChange = true
            )
        }
    }

    fun toggleAppFocusLock(packageName: String, locked: Boolean) {
        _activeStreams.value = _activeStreams.value.map { stream ->
            if (stream.packageName == packageName) stream.copy(focusLocked = locked) else stream
        }
        viewModelScope.launch(Dispatchers.IO) {
            val actualLocked = ShizukuAudioController.setAudioFocusLock(packageName, locked)
            if (actualLocked != locked && ShizukuAudioController.isShizukuReady()) {
                _activeStreams.value = _activeStreams.value.map { stream ->
                    if (stream.packageName == packageName) stream.copy(focusLocked = actualLocked) else stream
                }
            }
        }
    }

    fun dismissBanner() {
        _statusBannerMessage.value = null
    }

    private fun resolveHardwareDisplayName(info: AudioDeviceInfo, address: String): String {
        val rawProduct = info.productName?.toString()?.trim().orEmpty()
        val genericModel = Build.MODEL.trim()
        val hasDistinctProductName = rawProduct.isNotEmpty() &&
            !rawProduct.equals(genericModel, ignoreCase = true)

        return when (info.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Stereo Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
                if (hasDistinctProductName) rawProduct else "Bluetooth A2DP Buds ($address)"
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER ->
                if (hasDistinctProductName) rawProduct else "BLE Audio Buds ($address)"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET ->
                if (hasDistinctProductName) rawProduct else "Wired Studio Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE ->
                if (hasDistinctProductName) rawProduct else "USB Hi-Res Audio DAC"
            else -> if (hasDistinctProductName) rawProduct else "Hardware Audio Sink (${info.id})"
        }
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioPlaybackCallback != null) {
            audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
        }
        ShizukuAudioController.cleanupListeners(permissionGrantedCallback)
        super.onCleared()
    }
}
