package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.audiomatrix.app.ChannelMode
import com.audiomatrix.app.PcmDspEngine
import com.audiomatrix.app.ShizukuAudioController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AudioMatrix", appName)
    }

    @Test
    fun `shizuku audio focus lock logs adb appops command`() = runTest {
        ShizukuAudioController.clearCommandLogs()
        ShizukuAudioController.setAudioFocusLock("com.google.android.apps.youtube.music", true)
        val logs = ShizukuAudioController.commandLogs.value
        assertTrue(logs.any { it.command.contains("TAKE_AUDIO_FOCUS ignore") })
    }

    @Test
    fun `pcm dsp engine splits left and right channels with independent gain`() {
        val input = shortArrayOf(1000, -2000, 3000, -4000)
        val output = ShortArray(4)

        PcmDspEngine.processStereoPcmBuffer(
            source = input,
            sampleCount = 4,
            destination = output,
            deviceVolume = 0.5f,
            deviceMuted = false,
            appVolume = 1.0f,
            channelMode = ChannelMode.LEFT_ONLY
        )
        assertEquals(500.toShort(), output[0])
        assertEquals(500.toShort(), output[1])
        assertEquals(1500.toShort(), output[2])
        assertEquals(1500.toShort(), output[3])

        PcmDspEngine.processStereoPcmBuffer(
            source = input,
            sampleCount = 4,
            destination = output,
            deviceVolume = 0.5f,
            deviceMuted = false,
            appVolume = 1.0f,
            channelMode = ChannelMode.RIGHT_ONLY
        )
        assertEquals((-1000).toShort(), output[0])
        assertEquals((-1000).toShort(), output[1])
        assertEquals((-2000).toShort(), output[2])
        assertEquals((-2000).toShort(), output[3])
    }
}
