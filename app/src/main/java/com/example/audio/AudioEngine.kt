package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ultra-low latency audio engine with complete audio playback guarantee.
 * - Captures clean microphone input.
 * - Streams audio to AudioTrack and waits for full hardware drain before stopping,
 *   preventing audio cut-off / truncation at sentence end.
 * - Real-time amplitude streaming for cosmic orb & waveform visualizers.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val RECORD_SAMPLE_RATE = 16000
        const val GEMINI_PCM_SAMPLE_RATE = 24000
        private const val SILENCE_AMPLITUDE_THRESHOLD = 200
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private val recordedPcmStream = ByteArrayOutputStream()

    private var audioTrack: AudioTrack? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null

    /**
     * Start recording user speech.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        stopPlayback()
        if (isRecording) return true

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                RECORD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            2048
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RECORD_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            recordedPcmStream.reset()
            audioRecord?.startRecording()
            isRecording = true

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                val byteBuffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN)

                while (isActive && isRecording) {
                    val readShorts = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readShorts > 0) {
                        byteBuffer.clear()
                        var sumSquared = 0.0
                        for (i in 0 until readShorts) {
                            val sample = buffer[i]
                            byteBuffer.putShort(sample)
                            sumSquared += sample * sample
                        }
                        recordedPcmStream.write(byteBuffer.array(), 0, readShorts * 2)

                        val rms = sqrt(sumSquared / readShorts)
                        val normalized = (rms / 7000.0).coerceIn(0.0, 1.0).toFloat()
                        _amplitude.value = normalized
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
            stopRecording()
            return false
        }
    }

    /**
     * Stops recording, trims excessive dead silence while preserving speech margins,
     * and returns Base64 encoded WAV.
     */
    fun stopRecording(): String? {
        if (!isRecording && recordedPcmStream.size() == 0) return null

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
        }

        _amplitude.value = 0f

        val rawPcm = recordedPcmStream.toByteArray()
        if (rawPcm.size < 1000) return null

        val trimmedPcm = trimSilence(rawPcm, RECORD_SAMPLE_RATE)
        val wavBytes = pcmToWav(trimmedPcm, RECORD_SAMPLE_RATE, 1, 16)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    /**
     * Safely trims dead silence at edges with generous padding (150ms before, 250ms after)
     * so no user words or consonants are ever clipped.
     */
    private fun trimSilence(pcm: ByteArray, sampleRate: Int): ByteArray {
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        var startIdx = 0
        while (startIdx < shorts.size && abs(shorts[startIdx].toInt()) < SILENCE_AMPLITUDE_THRESHOLD) {
            startIdx++
        }

        var endIdx = shorts.size - 1
        while (endIdx > startIdx && abs(shorts[endIdx].toInt()) < SILENCE_AMPLITUDE_THRESHOLD) {
            endIdx--
        }

        // Generous padding: 150ms before, 250ms after to avoid cutting consonants
        val padBefore = (sampleRate * 0.15).toInt()
        val padAfter = (sampleRate * 0.25).toInt()
        val finalStart = maxOf(0, startIdx - padBefore)
        val finalEnd = minOf(shorts.size, endIdx + padAfter)

        if (finalEnd <= finalStart) return pcm

        val trimmedByteCount = (finalEnd - finalStart) * 2
        val result = ByteArray(trimmedByteCount)
        System.arraycopy(pcm, finalStart * 2, result, 0, trimmedByteCount)
        return result
    }

    /**
     * Ultra-fast playback for Gemini voice audio.
     * Uses in-memory AudioTrack for zero-disk-delay instant playback.
     */
    fun playGeminiVoice(
        audioBytes: ByteArray,
        mimeType: String,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        stopPlayback()
        _isAudioPlaying.value = true

        val pcmInfo = extractPcm(audioBytes, mimeType)
        if (pcmInfo != null) {
            playWithAudioTrack(pcmInfo.first, pcmInfo.second, coroutineScope, onCompletion)
        } else {
            playWithMediaPlayerFallback(audioBytes, mimeType, coroutineScope, onCompletion)
        }
    }

    private fun extractPcm(bytes: ByteArray, mimeType: String): Pair<ByteArray, Int>? {
        if (bytes.size < 4) return null
        val header = String(bytes, 0, 4)

        // If WAV container, read sample rate from byte 24-27 and skip 44-byte header
        if (header == "RIFF" && bytes.size > 44) {
            val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val pcmData = ByteArray(bytes.size - 44)
            System.arraycopy(bytes, 44, pcmData, 0, pcmData.size)
            return Pair(pcmData, if (sampleRate in 8000..48000) sampleRate else GEMINI_PCM_SAMPLE_RATE)
        }

        // If raw PCM from Gemini (e.g. audio/pcm;rate=24000)
        if (!header.startsWith("ID3") && header != "OggS") {
            val rate = if (mimeType.contains("16000")) 16000 else GEMINI_PCM_SAMPLE_RATE
            return Pair(bytes, rate)
        }

        return null
    }

    /**
     * Direct AudioTrack in-memory playback.
     * CRITICAL FIX: Waits for hardware audio buffer to completely play out (drain)
     * before stopping or releasing, preventing audio cut-off at the end of sentences!
     */
    private fun playWithAudioTrack(
        pcmBytes: ByteArray,
        sampleRate: Int,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, 4096)

            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            audioTrack = track
            track.play()

            playbackJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    val chunkSize = 2048
                    var offset = 0
                    val shortBuffer = ShortArray(chunkSize / 2)

                    while (isActive && _isAudioPlaying.value && offset < pcmBytes.size) {
                        val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                        val written = track.write(pcmBytes, offset, toWrite)
                        if (written <= 0) break

                        // Calculate live amplitude for visualizer
                        val shortsCount = written / 2
                        ByteBuffer.wrap(pcmBytes, offset, written)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuffer, 0, shortsCount)

                        var sum = 0.0
                        for (i in 0 until shortsCount) {
                            sum += shortBuffer[i] * shortBuffer[i]
                        }
                        val rms = sqrt(sum / shortsCount)
                        val norm = (rms / 6000.0).coerceIn(0.15, 1.0).toFloat()
                        _amplitude.value = norm

                        offset += written
                    }

                    // CRITICAL: All bytes are written into AudioTrack's internal buffer,
                    // but the speaker is still playing the remaining buffered audio.
                    // We must calculate remaining time and wait for full playback completion!
                    if (isActive && _isAudioPlaying.value) {
                        val totalFrames = pcmBytes.size / 2 // 16-bit mono = 2 bytes per frame
                        var currentHead = track.playbackHeadPosition
                        var maxWaitLoops = 60 // 60 * 50ms = 3000ms max timeout guard

                        while (isActive && _isAudioPlaying.value && currentHead < totalFrames && maxWaitLoops > 0) {
                            delay(50)
                            currentHead = track.playbackHeadPosition
                            maxWaitLoops--
                        }
                        // Extra 100ms safety pad for DAC output
                        delay(100)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack write error", e)
                } finally {
                    _amplitude.value = 0f
                    _isAudioPlaying.value = false
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                    if (audioTrack === track) {
                        audioTrack = null
                    }
                    onCompletion()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating AudioTrack, falling back to MediaPlayer", e)
            playWithMediaPlayerFallback(pcmBytes, "audio/pcm", coroutineScope, onCompletion)
        }
    }

    private fun playWithMediaPlayerFallback(
        audioBytes: ByteArray,
        mimeType: String,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        try {
            val extension = when {
                mimeType.contains("mp3") -> ".mp3"
                mimeType.contains("ogg") -> ".ogg"
                else -> ".wav"
            }
            val playableBytes = if (extension == ".wav" && (audioBytes.size < 4 || String(audioBytes, 0, 4) != "RIFF")) {
                pcmToWav(audioBytes, GEMINI_PCM_SAMPLE_RATE, 1, 16)
            } else {
                audioBytes
            }

            val tempFile = File.createTempFile("gemini_voice_", extension, context.cacheDir)
            FileOutputStream(tempFile).use { it.write(playableBytes) }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                prepare()
            }
            mediaPlayer = player

            playbackJob = coroutineScope.launch(Dispatchers.Default) {
                while (isActive && _isAudioPlaying.value && player.isPlaying) {
                    val t = System.currentTimeMillis() % 1000 / 1000f
                    val wave = 0.45f + 0.45f * abs(kotlin.math.sin(t * Math.PI.toFloat() * 4))
                    _amplitude.value = wave
                    delay(45)
                }
            }

            player.setOnCompletionListener {
                tempFile.delete()
                stopPlayback()
                onCompletion()
            }

            player.setOnErrorListener { _, _, _ ->
                tempFile.delete()
                stopPlayback()
                onCompletion()
                true
            }

            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer fallback error", e)
            stopPlayback()
            onCompletion()
        }
    }

    /**
     * Stop current playback and reset amplitude immediately.
     */
    fun stopPlayback() {
        _isAudioPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
        } catch (_: Exception) {} finally {
            audioTrack = null
        }

        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {} finally {
            mediaPlayer = null
        }

        _amplitude.value = 0f
    }

    /**
     * Convert raw PCM byte array to a standard RIFF/WAVE byte array.
     */
    fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavData = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavData, 0, 44)
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.size)
        return wavData
    }
}
