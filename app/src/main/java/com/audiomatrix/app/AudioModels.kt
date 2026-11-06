package com.audiomatrix.app

import android.media.AudioDeviceInfo
import kotlin.math.roundToInt

enum class ChannelMode {
    STEREO,
    LEFT_ONLY,
    RIGHT_ONLY
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class BufferBurstPreset(val framesPerBurst: Int, val label: String, val latencyHint: String) {
    ULTRA_LOW_128(128, "Ultra-Low (128f)", "~2.6 ms"),
    BALANCED_192(192, "Balanced (192f)", "~4.0 ms"),
    STABLE_256(256, "Stable (256f)", "~5.3 ms")
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val useDynamicColor: Boolean = true,
    val amoledPitchBlack: Boolean = false,
    val autoSilentBypassOnLaunch: Boolean = true,
    val hybridZeroLagHalRouting: Boolean = true,
    val bufferBurstPreset: BufferBurstPreset = BufferBurstPreset.BALANCED_192,
    val autoFocusLockNewApps: Boolean = false,
    val keepAliveOnScreenOff: Boolean = true
)

data class RuntimePermissionStatus(
    val recordAudioGranted: Boolean = false,
    val bluetoothConnectGranted: Boolean = false,
    val postNotificationsGranted: Boolean = false
) {
    val allRequiredGranted: Boolean
        get() = recordAudioGranted && bluetoothConnectGranted && postNotificationsGranted
}

data class OutputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val address: String,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val channelMode: ChannelMode = ChannelMode.STEREO,
    val isEnabledForDualShare: Boolean = false
) {
    val typeLabel: String
        get() = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE Audio"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Audio"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC"
            else -> "Hardware Output"
        }

    val isBluetooth: Boolean
        get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
}

data class ActiveAppStream(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val assignedDeviceId: Int?,
    val volume: Float = 1.0f,
    val focusLocked: Boolean = false
)

enum class ShizukuStatus {
    BINDER_ALIVE_GRANTED,
    BINDER_ALIVE_DENIED,
    BINDER_DEAD_FALLBACK
}

data class CommandLogEntry(
    val timestampMs: Long,
    val command: String,
    val output: String,
    val isSuccess: Boolean,
    val isSimulated: Boolean
)

data class DspTelemetry(
    val isCapturing: Boolean = false,
    val isSimulatedDsp: Boolean = false,
    val sampleRateHz: Int = 48000,
    val bufferSizeSamples: Int = 384,
    val activePipelineCount: Int = 0,
    val processedFrames: Long = 0L
)

object PcmDspEngine {
    /**
     * Ultra-fast zero-allocation 16-bit Stereo interleaved PCM processor (`index` = Left, `index + 1` = Right)
     * applying Feature E (Channel Split: STEREO, LEFT_ONLY, RIGHT_ONLY) and
     * Feature B (Independent Volume scaling: device.volume * app.volume) using fixed-point integer math.
     */
    fun processStereoPcmBuffer(
        source: ShortArray,
        sampleCount: Int,
        destination: ShortArray,
        deviceVolume: Float,
        deviceMuted: Boolean,
        appVolume: Float,
        channelMode: ChannelMode
    ) {
        val safeCount = sampleCount.coerceAtMost(source.size).coerceAtMost(destination.size)
        if (deviceMuted) {
            destination.fill(0, 0, safeCount)
            return
        }

        val combinedGain = (deviceVolume.coerceIn(0f, 1.5f) * appVolume.coerceIn(0f, 1.5f))
        if (combinedGain <= 0.0001f) {
            destination.fill(0, 0, safeCount)
            return
        }

        // Fixed-point Q12 multiplier (4096 = 1.0f) to avoid per-sample float conversions in hot loop
        val gainFixed = (combinedGain * 4096f).roundToInt()

        var index = 0
        when (channelMode) {
            ChannelMode.STEREO -> {
                if (gainFixed == 4096) {
                    System.arraycopy(source, 0, destination, 0, safeCount)
                    return
                }
                while (index + 1 < safeCount) {
                    val scaledL = ((source[index].toInt() * gainFixed) shr 12)
                        .coerceIn(-32768, 32767).toShort()
                    val scaledR = ((source[index + 1].toInt() * gainFixed) shr 12)
                        .coerceIn(-32768, 32767).toShort()
                    destination[index] = scaledL
                    destination[index + 1] = scaledR
                    index += 2
                }
            }

            ChannelMode.LEFT_ONLY -> {
                while (index + 1 < safeCount) {
                    val scaledL = ((source[index].toInt() * gainFixed) shr 12)
                        .coerceIn(-32768, 32767).toShort()
                    destination[index] = scaledL
                    destination[index + 1] = scaledL
                    index += 2
                }
            }

            ChannelMode.RIGHT_ONLY -> {
                while (index + 1 < safeCount) {
                    val scaledR = ((source[index + 1].toInt() * gainFixed) shr 12)
                        .coerceIn(-32768, 32767).toShort()
                    destination[index] = scaledR
                    destination[index + 1] = scaledR
                    index += 2
                }
            }
        }
    }
}
