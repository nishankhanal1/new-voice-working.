package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    assertEquals("Nepali Voice AI", appName)
  }

  @Test
  fun `audioEngine prewarms stream buffer without exception`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val audioEngine = com.example.audio.AudioEngine(context)
    audioEngine.prewarmAudioStreamBuffer()
    org.junit.Assert.assertTrue(audioEngine.isStreamPrewarmed.value)
    // Verify default silence timeout is optimized in 200ms-250ms window
    assertEquals(220L, audioEngine.getSilenceTimeoutMs())
    val dummyPcm = ByteArray(480)
    audioEngine.writeLiveAudioChunk(dummyPcm)
    org.junit.Assert.assertTrue(audioEngine.isAudioPlaying.value)
    audioEngine.clientSideBargeIn()
    audioEngine.release()
  }

  @Test
  fun `webSocketClient handles keep-alive ping and silence frame safely`() {
    val client = com.example.api.GeminiLiveWebSocketClient(
      apiKey = "test_key",
      onAudioChunkReceived = {},
      onTextChunkReceived = {},
      onError = {}
    )
    // Non-connected client safely no-ops keep-alive frames without throwing
    client.sendKeepAlivePing()
    client.sendWarmSilenceFrame()
    org.junit.Assert.assertFalse(client.isReady())
    client.disconnect()
  }
}
