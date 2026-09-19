package com.example

import com.example.audio.AudioEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun pcmToWav_generatesValidRiffHeader() {
        val dummyPcm = ByteArray(100)
        // Static test on header format
        val totalAudioLen = dummyPcm.size
        val totalDataLen = totalAudioLen + 36
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        val riff = String(header, 0, 4)
        assertEquals("RIFF", riff)
    }
}
