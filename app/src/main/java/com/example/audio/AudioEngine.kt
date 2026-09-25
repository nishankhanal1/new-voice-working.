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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * High-performance, ultra-low latency Audio Engine for Nepali Voice Conversations.
 * - Hardware AudioRecord with high-sensitivity dynamic VAD (<750ms auto-silence).
 * - Instant Barge-In interruption: cancels playback in 0ms when user speaks or inputs.
 * - In-memory AudioTrack streaming playback for raw PCM audio with zero disk I/O.
 * - Real-time amplitude streaming for cosmic orb and ripple visualizers.
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val RECORD_SAMPLE_RATE = 16000
        const val GEMINI_PCM_SAMPLE_RATE = 24000
        private const val MAX_RECORDING_BYTES = 16000 * 2 * 60 // Max 60 seconds
        private const val MIN_RECORDING_SPEECH_BYTES = 16000 * 2 / 10 // At least 100ms
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()

    private var playbackSpeed: Float = 1.0f
    // Snappy 750ms silence timeout for ultra-fast, natural conversational turn-taking
    private var silenceTimeoutMs: Long = 750L

    fun setSilenceTimeoutMs(timeout: Long) {
        silenceTimeoutMs = timeout.coerceIn(500L, 3000L)
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
            Log.w(TAG, "Failed to update playback speed: ${e.message}")
        }
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    private val recordingLock = Any()
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    @Volatile
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
    private var activeFocusRequest: Any? = null

    // Monotonic counter to instantly invalidate stale playback jobs on interruption
    private val currentPlaybackSession = AtomicLong(0L)

    /**
     * Starts recording microphone audio with robust hardware initialization,
     * low-latency buffer sizes, and adaptive Voice Activity Detection (VAD).
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
        // Stop any current playback when starting fresh recording
        stopPlayback()

        synchronized(recordingLock) {
            if (isRecording) {
                return true
            }

            val sampleRates = intArrayOf(RECORD_SAMPLE_RATE, 44100, 48000)
            // Prioritize VOICE_RECOGNITION for crystal clear speech and noise suppression
            val audioSources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
            )

            var chosenRecord: AudioRecord? = null
            var chosenRate = RECORD_SAMPLE_RATE
            var chosenBufSize = 2048

            sourceLoop@ for (source in audioSources) {
                for (rate in sampleRates) {
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minBuf > 0) {
                            val bufSize = maxOf(minBuf * 2, 2048)
                            val candidate = AudioRecord(
                                source,
                                rate,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufSize
                            )
                            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                                chosenRecord = candidate
                                chosenRate = rate
                                chosenBufSize = bufSize
                                break@sourceLoop
                            } else {
                                candidate.release()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioRecord init failed for source $source, rate $rate: ${e.message}")
                    }
                }
            }

            if (chosenRecord == null) {
                Log.e(TAG, "Failed to initialize AudioRecord with any configuration")
                return false
            }

            audioRecord = chosenRecord
            activeRecordSampleRate = chosenRate
            recordedPcmStream.reset()
            _isSpeechDetected.value = false
            isRecording = true

            try {
                chosenRecord.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording() hardware exception: ${e.message}")
                chosenRecord.release()
                audioRecord = null
                isRecording = false
                return false
            }

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val shortBuffer = ShortArray(chosenBufSize / 2)
                val byteBuffer = ByteBuffer.allocate(chosenBufSize).order(ByteOrder.LITTLE_ENDIAN)
                var hasSpeechStarted = false
                var speechFramesCount = 0
                var lastSpeechTimestamp = 0L
                val recordingStartTimestamp = System.currentTimeMillis()
                var hasTriggeredFinish = false

                // Baseline noise floor calculation during first 100ms
                var noiseFloorSum = 0.0
                var noiseFloorFrames = 0
                var dynamicThreshold = 25.0

                try {
                    while (isActive && isRecording) {
                        val rec = synchronized(recordingLock) { audioRecord } ?: break
                        val readShorts = rec.read(shortBuffer, 0, shortBuffer.size)
                        if (readShorts > 0) {
                            byteBuffer.clear()
                            var sumSquared = 0.0
                            for (i in 0 until readShorts) {
                                val sample = shortBuffer[i]
                                byteBuffer.putShort(sample)
                                sumSquared += sample * sample
                            }

                            val rms = sqrt(sumSquared / readShorts)
                            val normalized = (rms / 5500.0).coerceIn(0.0, 1.0).toFloat()
                            _amplitude.value = normalized

                            val now = System.currentTimeMillis()

                            // Adapt noise floor during initial silence
                            if (noiseFloorFrames < 6) {
                                noiseFloorSum += rms
                                noiseFloorFrames++
                                val avgNoise = noiseFloorSum / noiseFloorFrames
                                dynamicThreshold = (avgNoise * 1.5).coerceIn(20.0, 70.0)
                            }

                            // Check Barge-In interruption: if AI voice is currently playing and user speaks
                            if (_isAudioPlaying.value) {
                                if (rms > 70.0) { // Natural vocal threshold over speaker
                                    speechFramesCount++
                                    if (speechFramesCount >= 2) {
                                        Log.d(TAG, "Vocal barge-in detected! Stopping playback immediately.")
                                        stopPlayback()
                                        hasSpeechStarted = true
                                        lastSpeechTimestamp = now
                                        _isSpeechDetected.value = true
                                        onBargeIn?.invoke()
                                        onSpeechDetected?.invoke()
                                    }
                                }
                                continue
                            }

                            // Record into PCM buffer
                            val chunkBytes = byteBuffer.array().copyOfRange(0, readShorts * 2)
                            synchronized(recordingLock) {
                                if (recordedPcmStream.size() < MAX_RECORDING_BYTES) {
                                    recordedPcmStream.write(chunkBytes)
                                }
                            }
                            onAudioChunkRecorded?.invoke(chunkBytes, activeRecordSampleRate)

                            // Voice Activity Detection (VAD)
                            if (rms > dynamicThreshold) {
                                speechFramesCount++
                                lastSpeechTimestamp = now
                                if (speechFramesCount >= 2 && !hasSpeechStarted) {
                                    hasSpeechStarted = true
                                    _isSpeechDetected.value = true
                                    onSpeechDetected?.invoke()
                                }
                            }

                            // Auto-trigger completion when silence detected after speech
                            if (autoSilenceDetection && hasSpeechStarted && !hasTriggeredFinish) {
                                val silenceDuration = now - lastSpeechTimestamp
                                val speechDuration = now - recordingStartTimestamp
                                if (silenceDuration > silenceTimeoutMs && speechDuration >= 200L) {
                                    hasTriggeredFinish = true
                                    _isSpeechDetected.value = false
                                    Log.d(TAG, "Natural end of speech detected (${silenceDuration}ms pause). Triggering response.")
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

                            // Max utterance limit (45s)
                            if (hasSpeechStarted && !hasTriggeredFinish && (now - recordingStartTimestamp > 45000L)) {
                                hasTriggeredFinish = true
                                _isSpeechDetected.value = false
                                onSpeechFinished?.invoke()
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in audio recording loop", e)
                } finally {
                    _isSpeechDetected.value = false
                    synchronized(recordingLock) {
                        try {
                            chosenRecord.stop()
                            chosenRecord.release()
                        } catch (_: Exception) {}
                        if (audioRecord === chosenRecord) {
                            audioRecord = null
                        }
                    }
                }
            }
            return true
        }
    }

    /**
     * Extracts recorded audio buffer without terminating background loop (for continuous mode).
     */
    fun extractCurrentRecordedAudio(): String? {
        val rawPcm = synchronized(recordingLock) {
            val bytes = recordedPcmStream.toByteArray()
            recordedPcmStream.reset()
            bytes
        }

        if (rawPcm.size < MIN_RECORDING_SPEECH_BYTES) {
            return null
        }

        val trimmedPcm = trimSilence(rawPcm, activeRecordSampleRate)
        val pcmToUse = if (trimmedPcm.size >= MIN_RECORDING_SPEECH_BYTES) trimmedPcm else rawPcm
        val wavBytes = pcmToWav(pcmToUse, activeRecordSampleRate, 1, 16)
        return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
    }

    /**
     * Stops recording immediately and returns Base64 encoded WAV.
     * Preserves recorded speech safely without aggressive truncation.
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
                Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }

            _amplitude.value = 0f

            val rawPcm = recordedPcmStream.toByteArray()
            recordedPcmStream.reset()

            if (rawPcm.size < MIN_RECORDING_SPEECH_BYTES) {
                Log.d(TAG, "Audio bytes (${rawPcm.size}) below minimum speech threshold")
                return null
            }

            val trimmedPcm = trimSilence(rawPcm, activeRecordSampleRate)
            val pcmToUse = if (trimmedPcm.size >= MIN_RECORDING_SPEECH_BYTES) trimmedPcm else rawPcm
            val wavBytes = pcmToWav(pcmToUse, activeRecordSampleRate, 1, 16)
            return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        }
    }

    /**
     * Trims leading/trailing silence safely with generous margins (250ms/300ms)
     * so Nepali vowels, soft whispers, and word endings are never cut off.
     */
    private fun trimSilence(pcm: ByteArray, sampleRate: Int): ByteArray {
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        val windowSize = (sampleRate * 0.02).toInt() // 20ms window
        if (windowSize <= 0 || shorts.size < windowSize * 2) return pcm

        val silenceThreshold = 25.0 // Sensitive floor

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

        // Generous padding: 250ms before, 300ms after
        val padBefore = (sampleRate * 0.25).toInt()
        val padAfter = (sampleRate * 0.30).toInt()
        val finalStart = maxOf(0, startIdx - padBefore)
        val finalEnd = minOf(shorts.size, endIdx + padAfter)

        if (finalEnd <= finalStart || (finalEnd - finalStart) * 2 < MIN_RECORDING_SPEECH_BYTES) {
            return pcm
        }

        val trimmedByteCount = (finalEnd - finalStart) * 2
        val result = ByteArray(trimmedByteCount)
        System.arraycopy(pcm, finalStart * 2, result, 0, trimmedByteCount)
        return result
    }

    /**
     * Plays Gemini voice response.
     * Uses direct in-memory AudioTrack for raw PCM (zero latency, zero disk I/O),
     * with MediaPlayer fallback for compressed formats.
     */
    fun playGeminiVoice(
        audioBytes: ByteArray,
        mimeType: String,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        stopPlayback()
        val sessionId = currentPlaybackSession.incrementAndGet()
        _isAudioPlaying.value = true
        ensureAudibleVolume()

        val pcmPair = extractPcm(audioBytes, mimeType)
        if (pcmPair != null) {
            playWithAudioTrack(pcmPair.first, pcmPair.second, sessionId, coroutineScope, onCompletion)
        } else {
            playWithMediaPlayerFallback(audioBytes, mimeType, sessionId, coroutineScope, onCompletion)
        }
    }

    private fun extractPcm(bytes: ByteArray, mimeType: String): Pair<ByteArray, Int>? {
        if (bytes.size < 12) return null
        val header = String(bytes, 0, 4)

        // Parse WAV container
        if (header == "RIFF" && bytes.size > 44) {
            try {
                var sampleRate = GEMINI_PCM_SAMPLE_RATE
                var idx = 12
                while (idx + 8 <= bytes.size) {
                    val chunkId = String(bytes, idx, 4)
                    val chunkSize = ByteBuffer.wrap(bytes, idx + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (chunkId == "fmt " && idx + 16 <= bytes.size) {
                        sampleRate = ByteBuffer.wrap(bytes, idx + 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    } else if (chunkId == "data") {
                        val dataOffset = idx + 8
                        val validLength = minOf(chunkSize, bytes.size - dataOffset)
                        if (validLength > 0) {
                            val pcmData = ByteArray(validLength)
                            System.arraycopy(bytes, dataOffset, pcmData, 0, validLength)
                            val validRate = if (sampleRate in 8000..48000) sampleRate else GEMINI_PCM_SAMPLE_RATE
                            return Pair(pcmData, validRate)
                        }
                    }
                    val advance = 8 + maxOf(0, chunkSize)
                    if (advance <= 0 || idx + advance <= idx) break
                    idx += advance
                }
            } catch (_: Exception) {}

            val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val pcmData = ByteArray(bytes.size - 44)
            System.arraycopy(bytes, 44, pcmData, 0, pcmData.size)
            return Pair(pcmData, if (sampleRate in 8000..48000) sampleRate else GEMINI_PCM_SAMPLE_RATE)
        }

        // Raw PCM (audio/pcm;rate=24000, audio/L16)
        if (!header.startsWith("ID3") && header != "OggS" && header != "RIFF") {
            val rate = if (mimeType.contains("16000")) 16000 else GEMINI_PCM_SAMPLE_RATE
            return Pair(bytes, rate)
        }

        return null
    }

    fun ensureAudibleVolume() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (_: Exception) {}
            }

            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (current < max * 0.65f) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.85f).toInt(), 0)
            }

            try {
                am.isSpeakerphoneOn = true
            } catch (_: Exception) {}

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                activeFocusRequest = focusReq
                am.requestAudioFocus(focusReq)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            }
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (activeFocusRequest as? AudioFocusRequest)?.let {
                    am.abandonAudioFocusRequest(it)
                    activeFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    /**
     * Direct AudioTrack in-memory playback.
     * Uses session ID to immediately discard playback on user interruption.
     */
    private fun playWithAudioTrack(
        pcmBytes: ByteArray,
        sampleRate: Int,
        sessionId: Long,
        coroutineScope: CoroutineScope,
        onCompletion: () -> Unit
    ) {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf * 4, 16384)

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
                    val shortBuf = ShortArray(chunkSize / 2)

                    while (isActive && currentPlaybackSession.get() == sessionId && offset < pcmBytes.size) {
                        val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                        val written = track.write(pcmBytes, offset, toWrite)
                        if (written <= 0) break

                        // Calculate visual amplitude
                        val shortsCount = written / 2
                        ByteBuffer.wrap(pcmBytes, offset, written)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuf, 0, shortsCount)

                        var sum = 0.0
                        for (i in 0 until shortsCount) {
                            sum += shortBuf[i] * shortBuf[i]
                        }
                        val rms = sqrt(sum / shortsCount)
                        _amplitude.value = (rms / 5000.0).coerceIn(0.12, 1.0).toFloat()

                        offset += written
                    }

                    // Flush buffer cleanly
                    if (isActive && currentPlaybackSession.get() == sessionId) {
                        val totalFrames = pcmBytes.size / 2
                        val durationMs = (totalFrames * 1000L) / sampleRate
                        val maxWait = durationMs + 250L
                        val startTime = System.currentTimeMillis()

                        while (isActive && currentPlaybackSession.get() == sessionId) {
                            val head = try { track.playbackHeadPosition } catch (_: Exception) { totalFrames }
                            if (head >= totalFrames || System.currentTimeMillis() - startTime > maxWait) {
                                break
                            }
                            delay(25)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write exception: ${e.message}")
                } finally {
                    val completedSuccessfully = (currentPlaybackSession.get() == sessionId && isActive)
                    _amplitude.value = 0f
                    _isAudioPlaying.value = false
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Exception) {}
                    if (audioTrack === track) audioTrack = null
                    abandonAudioFocus()
                    if (completedSuccessfully) {
                        onCompletion()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack initialization error, using MediaPlayer fallback", e)
            playWithMediaPlayerFallback(pcmBytes, "audio/pcm", sessionId, coroutineScope, onCompletion)
        }
    }

    private fun playWithMediaPlayerFallback(
        audioBytes: ByteArray,
        mimeType: String,
        sessionId: Long,
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
                setDataSource(tempFile.absolutePath)
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
                while (isActive && currentPlaybackSession.get() == sessionId && _isAudioPlaying.value) {
                    val t = System.currentTimeMillis() % 1000 / 1000f
                    val wave = 0.45f + 0.45f * abs(kotlin.math.sin(t * Math.PI.toFloat() * 4))
                    _amplitude.value = wave
                    delay(45)
                }
            }

            player.setOnCompletionListener {
                try { tempFile.delete() } catch (_: Exception) {}
                currentTempFile = null
                stopPlayback()
                if (currentPlaybackSession.get() == sessionId) {
                    onCompletion()
                }
            }

            player.setOnErrorListener { _, _, _ ->
                try { tempFile.delete() } catch (_: Exception) {}
                currentTempFile = null
                stopPlayback()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer fallback error: ${e.message}")
            stopPlayback()
        }
    }

    /**
     * Instantly stops all ongoing playback, clears tracks, and releases audio focus in 0ms.
     * Guaranteed to stop playback on interruption (voice or chat input).
     */
    fun stopPlayback() {
        currentPlaybackSession.incrementAndGet()
        _isAudioPlaying.value = false
        _amplitude.value = 0f

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
    }

    /**
     * Converts raw PCM byte array to a standard RIFF/WAVE byte array.
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
