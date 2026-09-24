package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
import java.io.FileInputStream
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
        private const val MAX_RECORDING_BYTES = 16000 * 2 * 90 // Max 90 seconds to prevent OOM while allowing long conversations
        private const val MIN_RECORDING_SPEECH_BYTES = 16000 * 2 * 2 / 10 // At least 200ms speech (captures short replies like 'नमस्ते', 'हो', 'हजुर')
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private var playbackSpeed: Float = 1.0f
    private var silenceTimeoutMs: Long = 1800L // 1.8 seconds of natural pause before concluding utterance

    fun setSilenceTimeoutMs(timeout: Long) {
        silenceTimeoutMs = timeout.coerceIn(1200L, 5000L)
    }

    fun getSilenceTimeoutMs(): Long = silenceTimeoutMs

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.7f, 1.5f)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = android.media.PlaybackParams().setSpeed(playbackSpeed)
                audioTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) it.playbackParams = params }
                streamingTrack?.let { if (it.state == AudioTrack.STATE_INITIALIZED) it.playbackParams = params }
                mediaPlayer?.let { if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(playbackSpeed) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update live playback speed: ${e.message}")
        }
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    private val recordingLock = Any()
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private val recordedPcmStream = ByteArrayOutputStream()
    private var activeRecordSampleRate = RECORD_SAMPLE_RATE

    private val streamingLock = Any()
    private var audioTrack: AudioTrack? = null
    private var streamingTrack: AudioTrack? = null
    private var streamingTotalBytes = 0
    private var mediaPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private var playbackJob: Job? = null
    private var activeFocusRequest: Any? = null // AudioFocusRequest on API 26+

    /**
     * Start recording user speech with safe hardware initialization, fallback,
     * hardware Acoustic Echo Cancellation (AEC), and real-time Voice Activity Detection (VAD)
     * with Barge-In interruption support for hands-free natural conversations.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        coroutineScope: CoroutineScope,
        autoSilenceDetection: Boolean = true,
        isBargeInActive: Boolean = false,
        onSpeechDetected: (() -> Unit)? = null,
        onSpeechFinished: (() -> Unit)? = null,
        onBargeIn: (() -> Unit)? = null,
        onAudioChunkRecorded: ((ByteArray, Int) -> Unit)? = null
    ): Boolean {
        if (!isBargeInActive) {
            stopPlayback()
        }
        synchronized(recordingLock) {
            if (isRecording) return true

            val sampleRatesToTry = intArrayOf(RECORD_SAMPLE_RATE, 44100, 48000)
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.DEFAULT
            )
            var initializedRecord: AudioRecord? = null
            var chosenRate = RECORD_SAMPLE_RATE
            var chosenBufSize = 2048

            sourceLoop@ for (source in audioSources) {
                for (rate in sampleRatesToTry) {
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf > 0) {
                            val bufSize = maxOf(minBuf * 2, 4096)
                            val candidate = AudioRecord(
                                source,
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufSize
                            )
                            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                                // Enable Hardware Acoustic Echo Cancellation if supported
                                if (AcousticEchoCanceler.isAvailable()) {
                                    try {
                                        AcousticEchoCanceler.create(candidate.audioSessionId)?.enabled = true
                                    } catch (_: Exception) {}
                                }
                                // Enable Hardware Noise Suppression if supported
                                if (NoiseSuppressor.isAvailable()) {
                                    try {
                                        NoiseSuppressor.create(candidate.audioSessionId)?.enabled = true
                                    } catch (_: Exception) {}
                                }

                                initializedRecord = candidate
                                chosenRate = rate
                                chosenBufSize = bufSize
                                break@sourceLoop
                            } else {
                                candidate.release()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Sample rate $rate with source $source not supported: ${e.message}")
                    }
                }
            }

            if (initializedRecord == null) {
                Log.e(TAG, "Could not initialize AudioRecord with any sample rate")
                return false
            }

            audioRecord = initializedRecord
            activeRecordSampleRate = chosenRate
            recordedPcmStream.reset()
            initializedRecord.startRecording()
            isRecording = true
            _isSpeechDetected.value = false

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(chosenBufSize / 2)
                val byteBuffer = ByteBuffer.allocate(chosenBufSize).order(ByteOrder.LITTLE_ENDIAN)
                var hasSpeechStarted = false
                var speechFramesCount = 0
                var lastSpeechTimestamp = 0L
                val recordingStartTimestamp = System.currentTimeMillis()
                var hasTriggeredFinish = false

                try {
                    while (isActive && isRecording) {
                        val rec = synchronized(recordingLock) { audioRecord } ?: break
                        val readShorts = rec.read(buffer, 0, buffer.size)
                        if (readShorts > 0) {
                            byteBuffer.clear()
                            var sumSquared = 0.0
                            for (i in 0 until readShorts) {
                                val sample = buffer[i]
                                byteBuffer.putShort(sample)
                                sumSquared += sample * sample
                            }

                            val rms = sqrt(sumSquared / readShorts)
                            val normalized = (rms / 6500.0).coerceIn(0.0, 1.0).toFloat()
                            _amplitude.value = normalized

                            val now = System.currentTimeMillis()

                            // Check Barge-In interruption: If AI is currently speaking and user speaks over it
                            if (_isAudioPlaying.value) {
                                if (rms > 500.0) { // Clear vocal utterance over speaker
                                    speechFramesCount++
                                    if (speechFramesCount >= 2) {
                                        Log.d(TAG, "Barge-in triggered! Stopping AI voice playback immediately.")
                                        stopPlayback()
                                        hasSpeechStarted = true
                                        lastSpeechTimestamp = now
                                        _isSpeechDetected.value = true
                                        onBargeIn?.invoke()
                                        onSpeechDetected?.invoke()
                                    }
                                }
                                // Do not record AI speaker output into recorded PCM stream to prevent echo loops
                                continue
                            }

                            // Normal recording into PCM buffer when AI is NOT playing
                            val chunkBytes = byteBuffer.array().copyOfRange(0, readShorts * 2)
                            synchronized(recordingLock) {
                                if (recordedPcmStream.size() < MAX_RECORDING_BYTES) {
                                    recordedPcmStream.write(chunkBytes)
                                }
                            }
                            onAudioChunkRecorded?.invoke(chunkBytes, activeRecordSampleRate)

                            // Voice Activity Detection (VAD) during normal listening
                            val speechThreshold = 175.0
                            if (rms > speechThreshold) {
                                speechFramesCount++
                                lastSpeechTimestamp = now
                                if (speechFramesCount >= 2 && !hasSpeechStarted) {
                                    hasSpeechStarted = true
                                    _isSpeechDetected.value = true
                                    onSpeechDetected?.invoke()
                                }
                            }

                            // If speech was active and user paused for natural silence duration (~1.8s), auto-complete voice input
                            if (autoSilenceDetection && hasSpeechStarted && !hasTriggeredFinish) {
                                val silenceDuration = now - lastSpeechTimestamp
                                val speechDuration = now - recordingStartTimestamp
                                val silenceLimit = if (isBargeInActive) maxOf(1400L, silenceTimeoutMs - 300L) else silenceTimeoutMs
                                if (silenceDuration > silenceLimit && speechDuration >= 250L) {
                                    hasTriggeredFinish = true
                                    _isSpeechDetected.value = false
                                    Log.d(TAG, "VAD: Natural end of speech detected after ${silenceDuration}ms silence (${speechDuration}ms speech). Triggering response.")
                                    onSpeechFinished?.invoke()

                                    if (isBargeInActive) {
                                        // Reset turn tracking so the loop remains alive for continuous barge-in and next turn!
                                        hasSpeechStarted = false
                                        speechFramesCount = 0
                                        lastSpeechTimestamp = 0L
                                        hasTriggeredFinish = false
                                    } else {
                                        break
                                    }
                                }
                            }

                            // Maximum utterance safeguard (60 seconds continuous speech)
                            if (hasSpeechStarted && !hasTriggeredFinish && (now - recordingStartTimestamp > 60000L)) {
                                hasTriggeredFinish = true
                                _isSpeechDetected.value = false
                                onSpeechFinished?.invoke()
                                if (isBargeInActive) {
                                    hasSpeechStarted = false
                                    speechFramesCount = 0
                                    lastSpeechTimestamp = 0L
                                    hasTriggeredFinish = false
                                } else {
                                    break
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio recording loop", e)
                } finally {
                    _isSpeechDetected.value = false
                    synchronized(recordingLock) {
                        try {
                            initializedRecord.stop()
                            initializedRecord.release()
                        } catch (_: Exception) {}
                        if (audioRecord === initializedRecord) audioRecord = null
                    }
                }
            }
            return true
        }
    }

    /**
     * Extracts recorded audio buffer without terminating the background recording loop.
     * Ideal for continuous conversation mode and zero-latency turn-taking.
     */
    fun extractCurrentRecordedAudio(): String? {
        val rawPcm = synchronized(recordingLock) {
            val bytes = recordedPcmStream.toByteArray()
            recordedPcmStream.reset()
            bytes
        }

        if (rawPcm.size < MIN_RECORDING_SPEECH_BYTES) {
            Log.d(TAG, "Audio too short (< 300ms speech) - ignoring noise")
            return null
        }

        val trimmedPcm = trimSilence(rawPcm, activeRecordSampleRate)
        if (trimmedPcm.size < MIN_RECORDING_SPEECH_BYTES) {
            return null
        }

        val wavBytes = pcmToWav(trimmedPcm, activeRecordSampleRate, 1, 16)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    /**
     * Stops recording, trims excessive dead silence while preserving speech margins,
     * and returns Base64 encoded WAV.
     */
    fun stopRecording(): String? {
        synchronized(recordingLock) {
            if (!isRecording && recordedPcmStream.size() == 0) return null

            isRecording = false
            recordingJob?.cancel()
            recordingJob = null
            _isSpeechDetected.value = false

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
            recordedPcmStream.reset()

            if (rawPcm.size < MIN_RECORDING_SPEECH_BYTES) {
                Log.d(TAG, "Audio too short (< 300ms speech) - ignoring noise")
                return null
            }

            val trimmedPcm = trimSilence(rawPcm, activeRecordSampleRate)
            if (trimmedPcm.size < MIN_RECORDING_SPEECH_BYTES) {
                return null
            }

            val wavBytes = pcmToWav(trimmedPcm, activeRecordSampleRate, 1, 16)
            return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        }
    }

    /**
     * Safely trims dead silence at edges with energy-based RMS threshold and generous padding (250ms)
     * so no user words, pauses, or consonants are ever clipped.
     */
    private fun trimSilence(pcm: ByteArray, sampleRate: Int): ByteArray {
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        val windowSize = (sampleRate * 0.02).toInt() // 20ms window
        if (windowSize <= 0 || shorts.size < windowSize * 2) return pcm

        val silenceThreshold = 120.0 // True acoustic speech threshold, preserves soft speech and subtle consonants

        var startIdx = 0
        while (startIdx + windowSize < shorts.size) {
            var sumSquared = 0.0
            for (i in 0 until windowSize) {
                val s = shorts[startIdx + i].toDouble()
                sumSquared += s * s
            }
            val rms = sqrt(sumSquared / windowSize)
            if (rms > silenceThreshold) break
            startIdx += windowSize
        }

        var endIdx = shorts.size - 1
        while (endIdx - windowSize > startIdx) {
            var sumSquared = 0.0
            for (i in 0 until windowSize) {
                val s = shorts[endIdx - i].toDouble()
                sumSquared += s * s
            }
            val rms = sqrt(sumSquared / windowSize)
            if (rms > silenceThreshold) break
            endIdx -= windowSize
        }

        // Generous padding: 250ms before, 300ms after to avoid cutting consonants
        val padBefore = (sampleRate * 0.25).toInt()
        val padAfter = (sampleRate * 0.30).toInt()
        val finalStart = maxOf(0, startIdx - padBefore)
        val finalEnd = minOf(shorts.size, endIdx + padAfter)

        if (finalEnd <= finalStart) return pcm

        val trimmedByteCount = (finalEnd - finalStart) * 2
        val result = ByteArray(trimmedByteCount)
        System.arraycopy(pcm, finalStart * 2, result, 0, trimmedByteCount)
        return result
    }

    /**
     * Plays Gemini voice response.
     * Uses rock-solid, ultra-clear Android MediaPlayer with in-memory WAV container,
     * ensuring zero-glitch, full-volume speaker output across all devices and emulators.
     */
    fun playGeminiVoice(
        audioBytes: ByteArray,
        mimeType: String,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        stopPlayback()
        _isAudioPlaying.value = true
        ensureAudibleVolume()

        playWithMediaPlayerFallback(audioBytes, mimeType, coroutineScope, onCompletion)
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

    fun ensureAudibleVolume() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                // Unmute music stream if muted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    } catch (_: Exception) {}
                }

                try {
                    val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (current < max * 0.7f) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.90f).toInt(), 0)
                    }
                } catch (_: Exception) {}

                // Ensure output routes to speaker
                try {
                    am.isSpeakerphoneOn = true
                } catch (_: Exception) {}

                // Request direct transient audio focus (not ducked) for voice clarity
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .build()
                        activeFocusRequest = focusRequest
                        am.requestAudioFocus(focusRequest)
                    } else {
                        @Suppress("DEPRECATION")
                        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val req = activeFocusRequest as? AudioFocusRequest
                    if (req != null) {
                        am.abandonAudioFocusRequest(req)
                        activeFocusRequest = null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Prepares AudioTrack for real-time streaming chunks as they arrive from Gemini Live WebSocket.
     */
    fun prepareStreamingPlayback(sampleRate: Int = GEMINI_PCM_SAMPLE_RATE) {
        stopPlayback()
        _isAudioPlaying.value = true
        ensureAudibleVolume()

        synchronized(streamingLock) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuf * 2, 8192)

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
                track.setVolume(1.0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    try {
                        track.playbackParams = android.media.PlaybackParams().setSpeed(playbackSpeed)
                    } catch (_: Exception) {}
                }
                track.play()
                audioTrack = track
                streamingTrack = track
                streamingTotalBytes = 0
                Log.d(TAG, "Streaming AudioTrack prepared at $sampleRate Hz")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare streaming AudioTrack", e)
            }
        }
    }

    /**
     * Writes a real-time chunk of PCM audio directly to the streaming AudioTrack for immediate playback.
     */
    fun writeStreamingChunk(pcmBytes: ByteArray) {
        if (!_isAudioPlaying.value || pcmBytes.isEmpty()) return

        synchronized(streamingLock) {
            val track = streamingTrack ?: return
            try {
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                track.write(pcmBytes, 0, pcmBytes.size)
                streamingTotalBytes += pcmBytes.size

                // Fast amplitude estimation
                val samplesCount = minOf(128, pcmBytes.size / 2)
                var sum = 0.0
                val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until samplesCount) {
                    val s = bb.get(i)
                    sum += s * s
                }
                val rms = sqrt(sum / samplesCount)
                _amplitude.value = (rms / 5000.0).coerceIn(0.1, 1.0).toFloat()
            } catch (e: Exception) {
                Log.w(TAG, "Error writing streaming chunk: ${e.message}")
            }
        }
    }

    /**
     * Waits for the streaming AudioTrack to finish playing out all buffered audio frames, then releases it.
     */
    fun finishStreamingPlayback(
        coroutineScope: CoroutineScope,
        sampleRate: Int = GEMINI_PCM_SAMPLE_RATE,
        onCompletion: () -> Unit
    ) {
        val track = synchronized(streamingLock) { streamingTrack } ?: run {
            _isAudioPlaying.value = false
            _amplitude.value = 0f
            abandonAudioFocus()
            onCompletion()
            return
        }

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val totalBytes = synchronized(streamingLock) { streamingTotalBytes }
                val totalFrames = totalBytes / 2
                val maxTimeoutMs = ((totalFrames * 1000L) / sampleRate) + 2000L
                val waitStartTime = System.currentTimeMillis()

                while (isActive && _isAudioPlaying.value) {
                    val currentHead = try { track.playbackHeadPosition } catch (_: Exception) { totalFrames }
                    if (currentHead >= totalFrames) {
                        break
                    }
                    if (System.currentTimeMillis() - waitStartTime > maxTimeoutMs) {
                        Log.d(TAG, "Streaming AudioTrack reached drain timeout")
                        break
                    }
                    val progress = currentHead.toFloat() / maxOf(1, totalFrames)
                    _amplitude.value = (0.25f * (1f - progress)).coerceIn(0.05f, 0.35f)
                    delay(25)
                }
                delay(60) // Clean flush
            } catch (e: Exception) {
                Log.w(TAG, "Error during streaming finish: ${e.message}")
            } finally {
                _amplitude.value = 0f
                _isAudioPlaying.value = false
                synchronized(streamingLock) {
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                    if (audioTrack === track) audioTrack = null
                    if (streamingTrack === track) streamingTrack = null
                }
                abandonAudioFocus()
                onCompletion()
            }
        }
    }

    /**
     * Direct AudioTrack in-memory playback.
     * Waits for hardware audio buffer to play out before stopping, preventing cutoff.
     */
    private fun playWithAudioTrack(
        pcmBytes: ByteArray,
        sampleRate: Int,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        try {
            ensureAudibleVolume()
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 8, 32768)

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
            track.setVolume(1.0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                try {
                    track.playbackParams = android.media.PlaybackParams().setSpeed(playbackSpeed)
                } catch (_: Exception) {}
            }
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

                    // CRITICAL FIX: Ensure full audio playback without premature cutoff!
                    // Calculate exact duration of the PCM stream and wait until hardware finishes playing.
                    if (isActive && _isAudioPlaying.value) {
                        val totalFrames = pcmBytes.size / 2 // 16-bit mono = 2 bytes per frame
                        val durationMs = (totalFrames * 1000L) / sampleRate
                        val maxTimeoutMs = durationMs + 400L // Natural duration plus 400ms flush margin
                        val waitStartTime = System.currentTimeMillis()

                        while (isActive && _isAudioPlaying.value) {
                            val currentHead = try { track.playbackHeadPosition } catch (_: Exception) { totalFrames }
                            if (currentHead >= totalFrames) {
                                break
                            }
                            if (System.currentTimeMillis() - waitStartTime > maxTimeoutMs) {
                                break
                            }
                            // Keep gentle amplitude alive while hardware is emptying buffer
                            val progress = currentHead.toFloat() / maxOf(1, totalFrames)
                            _amplitude.value = (0.25f * (1f - progress)).coerceIn(0.05f, 0.35f)
                            delay(30)
                        }
                        delay(60)
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
            val sampleRate = if (mimeType.contains("16000")) 16000 else GEMINI_PCM_SAMPLE_RATE
            val playableBytes = if (extension == ".wav" && (audioBytes.size < 4 || String(audioBytes, 0, 4) != "RIFF")) {
                pcmToWav(audioBytes, sampleRate, 1, 16)
            } else {
                audioBytes
            }

            val tempFile = File.createTempFile("gemini_voice_", extension, context.cacheDir)
            currentTempFile = tempFile
            FileOutputStream(tempFile).use { fos ->
                fos.write(playableBytes)
                fos.flush()
            }
            try { tempFile.setReadable(true, false) } catch (_: Exception) {}

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // Use FileDescriptor to guarantee MediaPlayer in mediaserver has permission to read private app cache
                FileInputStream(tempFile).use { fis ->
                    setDataSource(fis.fd)
                }
                setVolume(1.0f, 1.0f)
                prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    try {
                        playbackParams = playbackParams.setSpeed(playbackSpeed)
                    } catch (_: Exception) {}
                }
                start()
            }
            mediaPlayer = player

            playbackJob = coroutineScope.launch(Dispatchers.Default) {
                while (isActive && _isAudioPlaying.value) {
                    val t = System.currentTimeMillis() % 1000 / 1000f
                    val wave = 0.45f + 0.45f * abs(kotlin.math.sin(t * Math.PI.toFloat() * 4))
                    _amplitude.value = wave
                    delay(45)
                }
            }

            player.setOnCompletionListener {
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
                currentTempFile = null
                stopPlayback()
                onCompletion()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra, attempting AudioTrack fallback")
                try {
                    tempFile.delete()
                } catch (_: Exception) {}
                currentTempFile = null
                stopPlayback()
                val (pcmBytes, rate) = extractPcm(audioBytes, mimeType) ?: Pair(audioBytes, GEMINI_PCM_SAMPLE_RATE)
                playWithAudioTrack(pcmBytes, rate, coroutineScope, onCompletion)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer fallback error, attempting AudioTrack fallback", e)
            val (pcmBytes, rate) = extractPcm(audioBytes, mimeType) ?: Pair(audioBytes, GEMINI_PCM_SAMPLE_RATE)
            playWithAudioTrack(pcmBytes, rate, coroutineScope, onCompletion)
        }
    }

    /**
     * Stop current playback, reset amplitude immediately, and clean up audio resources.
     */
    fun stopPlayback() {
        _isAudioPlaying.value = false
        playbackJob?.cancel()
        playbackJob = null

        synchronized(streamingLock) {
            try {
                audioTrack?.apply {
                    pause()
                    flush()
                    stop()
                    release()
                }
            } catch (_: Exception) {} finally {
                audioTrack = null
                streamingTrack = null
                streamingTotalBytes = 0
            }
        }

        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {} finally {
            mediaPlayer = null
        }

        try {
            currentTempFile?.delete()
        } catch (_: Exception) {}
        currentTempFile = null

        abandonAudioFocus()
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
