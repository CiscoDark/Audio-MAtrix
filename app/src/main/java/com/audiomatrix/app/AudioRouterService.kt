package com.audiomatrix.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AudioRouterService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.audiomatrix.app.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.audiomatrix.app.action.STOP_CAPTURE"
        const val ACTION_SYNC_MATRIX = "com.audiomatrix.app.action.SYNC_MATRIX"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIFICATION_CHANNEL_ID = "audiomatrix_router_channel"
        private const val NOTIFICATION_ID = 8401
        private const val STEREO_CHANNELS = 2
        private const val DEFAULT_NATIVE_SAMPLE_RATE = 48000
        private const val DEFAULT_FRAMES_PER_BURST = 192

        private val outputDeviceMap = ConcurrentHashMap<Int, OutputDevice>()
        private val activeStreamMap = ConcurrentHashMap<Int, ActiveAppStream>()

        @Volatile
        private var dualBluetoothShareEnabled: Boolean = false

        @Volatile
        private var hybridZeroLagEnabled: Boolean = true

        @Volatile
        private var customFramesPerBurst: Int? = null

        private val configSequence = AtomicLong(0L)

        private val _telemetry = MutableStateFlow(
            DspTelemetry(
                sampleRateHz = DEFAULT_NATIVE_SAMPLE_RATE,
                bufferSizeSamples = DEFAULT_FRAMES_PER_BURST * STEREO_CHANNELS
            )
        )
        val telemetry: StateFlow<DspTelemetry> = _telemetry.asStateFlow()

        fun updateRoutingMatrix(
            devices: List<OutputDevice>,
            streams: List<ActiveAppStream>,
            dualShareEnabled: Boolean
        ) {
            outputDeviceMap.clear()
            for (device in devices) {
                outputDeviceMap[device.id] = device
            }

            activeStreamMap.clear()
            for (stream in streams) {
                activeStreamMap[stream.uid] = stream
            }

            dualBluetoothShareEnabled = dualShareEnabled
            configSequence.incrementAndGet()
        }

        fun updateEngineTuning(
            enableHybridZeroLagHal: Boolean,
            framesPerBurstOverride: Int?
        ) {
            hybridZeroLagEnabled = enableHybridZeroLagHal
            customFramesPerBurst = framesPerBurstOverride
            configSequence.incrementAndGet()
        }
    }

    private inner class UidAudioPipelineWorker(
        private val projection: MediaProjection,
        private val targetUid: Int,
        private val nativeSampleRate: Int,
        private val framesPerBurst: Int
    ) {
        private val isRunning = AtomicBoolean(true)
        private val flushRequested = AtomicBoolean(true)
        private var workerThread: Thread? = null

        fun start() {
            val thread = Thread(
                { runUrgentAudioLoop() },
                "AudioMatrix-UrgentPCM-UID-$targetUid"
            )
            thread.isDaemon = true
            workerThread = thread
            thread.start()
        }

        fun requestFlush() {
            flushRequested.set(true)
        }

        fun stop() {
            isRunning.set(false)
            workerThread?.interrupt()
        }

        fun isAlive(): Boolean = isRunning.get() && (workerThread?.isAlive == true)

        @SuppressLint("MissingPermission", "NewApi")
        private fun runUrgentAudioLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val captureConfig = try {
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUid(targetUid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            } catch (_: Throwable) {
                isRunning.set(false)
                return
            }

            val microChunkShorts = (framesPerBurst * STEREO_CHANNELS).coerceIn(192, 512)
            val minInBufferBytes = AudioRecord.getMinBufferSize(
                nativeSampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(microChunkShorts * 2)

            val captureFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(nativeSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val audioRecord = try {
                AudioRecord.Builder()
                    .setAudioFormat(captureFormat)
                    .setBufferSizeInBytes(minInBufferBytes)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } catch (_: Throwable) {
                isRunning.set(false)
                return
            }

            val rawPcmBuffer = ShortArray(microChunkShorts)
            val processedPcmBuffer = ShortArray(microChunkShorts)
            val targetDeviceBuffer = arrayOfNulls<OutputDevice>(8)
            val boundTracks = HashMap<Int, AudioTrack>(8)

            var lastObservedConfigSeq = configSequence.get()
            var lastAssignedDeviceId: Int? = null
            var lastDualShareFlag = dualBluetoothShareEnabled

            try {
                audioRecord.startRecording()

                while (isRunning.get() && !Thread.currentThread().isInterrupted) {
                    val stream = activeStreamMap[targetUid]
                    if (stream == null) {
                        Thread.sleep(5)
                        continue
                    }

                    val targetCount = fillTargetDevicesPreallocated(stream, targetDeviceBuffer)
                    if (targetCount == 0) {
                        Thread.sleep(5)
                        continue
                    }

                    val currentSeq = configSequence.get()
                    if (currentSeq != lastObservedConfigSeq ||
                        stream.assignedDeviceId != lastAssignedDeviceId ||
                        dualBluetoothShareEnabled != lastDualShareFlag
                    ) {
                        lastObservedConfigSeq = currentSeq
                        lastAssignedDeviceId = stream.assignedDeviceId
                        lastDualShareFlag = dualBluetoothShareEnabled
                        flushRequested.set(true)
                    }

                    val readCount = audioRecord.read(
                        rawPcmBuffer,
                        0,
                        microChunkShorts,
                        AudioRecord.READ_BLOCKING
                    )

                    if (readCount <= 0) {
                        continue
                    }

                    val shouldFlushTracks = flushRequested.getAndSet(false)

                    for (i in 0 until targetCount) {
                        val device = targetDeviceBuffer[i] ?: continue
                        val track = getOrCreateLowLatencyTrack(
                            deviceId = device.id,
                            sampleRate = nativeSampleRate,
                            microChunkShorts = microChunkShorts,
                            cache = boundTracks
                        ) ?: continue

                        if (shouldFlushTracks) {
                            flushAudioTrackImmediately(track)
                        }

                        PcmDspEngine.processStereoPcmBuffer(
                            source = rawPcmBuffer,
                            sampleCount = readCount,
                            destination = processedPcmBuffer,
                            deviceVolume = device.volume,
                            deviceMuted = device.isMuted,
                            appVolume = stream.volume,
                            channelMode = device.channelMode
                        )

                        track.write(
                            processedPcmBuffer,
                            0,
                            readCount,
                            AudioTrack.WRITE_NON_BLOCKING
                        )
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            } finally {
                runCatching {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                    audioRecord.release()
                }
                for (track in boundTracks.values) {
                    runCatching {
                        track.pause()
                        track.flush()
                        track.release()
                    }
                }
                boundTracks.clear()
            }
        }
    }

    private val uidPipelines = ConcurrentHashMap<Int, UidAudioPipelineWorker>()
    private var mediaProjection: MediaProjection? = null
    private lateinit var audioManager: AudioManager

    private var nativeSampleRate: Int = DEFAULT_NATIVE_SAMPLE_RATE
    private var nativeFramesPerBurst: Int = DEFAULT_FRAMES_PER_BURST

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        queryNativeHardwareAudioParameters()
        createNotificationChannel()
    }

    private fun queryNativeHardwareAudioParameters() {
        nativeSampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it in 44100..192000 }
            ?: DEFAULT_NATIVE_SAMPLE_RATE

        nativeFramesPerBurst = customFramesPerBurst
            ?: audioManager
                .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull()
                ?.takeIf { it in 64..512 }
            ?: DEFAULT_FRAMES_PER_BURST

        _telemetry.value = _telemetry.value.copy(
            sampleRateHz = nativeSampleRate,
            bufferSizeSamples = nativeFramesPerBurst * STEREO_CHANNELS
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        queryNativeHardwareAudioParameters()

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithProjectionType()
                    startMediaProjectionRouting(resultCode, resultData)
                }
            }

            ACTION_SYNC_MATRIX -> {
                if (mediaProjection != null) {
                    reconcileUidPipelines()
                    for (worker in uidPipelines.values) {
                        worker.requestFlush()
                    }
                }
            }

            ACTION_STOP_CAPTURE -> {
                stopAllPipelines()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
        }
    }

    private fun startMediaProjectionRouting(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection?.stop()
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)?.apply {
            registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopAllPipelines()
                }
            }, null)
        }

        reconcileUidPipelines()
    }

    private fun reconcileUidPipelines() {
        val projection = mediaProjection ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        // Only spin up user-space PCM capture for UIDs when Dual Share (2+ simultaneous sinks)
        // is actively required. Single-sink routing, volume, and pan are handled natively via Shizuku with 0ms lag!
        val requiresMultiSinkBridge = dualBluetoothShareEnabled &&
            outputDeviceMap.values.count { it.isEnabledForDualShare } >= 2

        val activeUids = if (requiresMultiSinkBridge) {
            activeStreamMap.keys.toSet()
        } else {
            emptySet()
        }

        for ((uid, worker) in uidPipelines) {
            if (uid !in activeUids) {
                worker.stop()
                uidPipelines.remove(uid)
            }
        }

        for (uid in activeUids) {
            val existing = uidPipelines[uid]
            if (existing == null || !existing.isAlive()) {
                val worker = UidAudioPipelineWorker(
                    projection = projection,
                    targetUid = uid,
                    nativeSampleRate = nativeSampleRate,
                    framesPerBurst = nativeFramesPerBurst
                )
                uidPipelines[uid] = worker
                worker.start()
            }
        }

        _telemetry.value = _telemetry.value.copy(
            isCapturing = true,
            isSimulatedDsp = false,
            sampleRateHz = nativeSampleRate,
            bufferSizeSamples = nativeFramesPerBurst * STEREO_CHANNELS,
            activePipelineCount = uidPipelines.size
        )
    }

    private fun getOrCreateLowLatencyTrack(
        deviceId: Int,
        sampleRate: Int,
        microChunkShorts: Int,
        cache: MutableMap<Int, AudioTrack>
    ): AudioTrack? {
        cache[deviceId]?.let { return it }

        val minOutBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(microChunkShorts * 2)

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minOutBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
        } catch (_: Throwable) {
            null
        } ?: return null

        val hardwareSink: AudioDeviceInfo? = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == deviceId }

        if (hardwareSink != null) {
            track.setPreferredDevice(hardwareSink)
        }

        flushAudioTrackImmediately(track)
        cache[deviceId] = track
        return track
    }

    private fun flushAudioTrackImmediately(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.play()
        } catch (_: Throwable) {
        }
    }

    private fun fillTargetDevicesPreallocated(
        stream: ActiveAppStream,
        targetBuffer: Array<OutputDevice?>
    ): Int {
        for (i in targetBuffer.indices) {
            targetBuffer[i] = null
        }

        var count = 0
        if (dualBluetoothShareEnabled) {
            for (device in outputDeviceMap.values) {
                if (device.isEnabledForDualShare && count < targetBuffer.size) {
                    targetBuffer[count++] = device
                }
            }
            if (count >= 2) {
                return count
            }
            count = 0
        }

        val assigned = stream.assignedDeviceId?.let { outputDeviceMap[it] }
            ?: outputDeviceMap.values.firstOrNull()

        if (assigned != null) {
            targetBuffer[0] = assigned
            return 1
        }
        return 0
    }

    private fun stopAllPipelines() {
        for ((_, worker) in uidPipelines) {
            worker.stop()
        }
        uidPipelines.clear()

        mediaProjection?.stop()
        mediaProjection = null

        _telemetry.value = DspTelemetry(
            isCapturing = false,
            isSimulatedDsp = false,
            sampleRateHz = nativeSampleRate,
            bufferSizeSamples = nativeFramesPerBurst * STEREO_CHANNELS,
            activePipelineCount = 0,
            processedFrames = 0L
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_content)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioRouterService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                "${nativeSampleRate}Hz Native Shizuku HAL Router Active"
            )
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop_engine),
                pendingStop
            )
            .build()
    }

    override fun onDestroy() {
        stopAllPipelines()
        super.onDestroy()
    }
}
