package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiLiveWebSocketClient
import com.example.api.GeminiVoiceService
import com.example.audio.AudioEngine
import com.example.data.ConversationDatabase
import com.example.data.ConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

data class ChatItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "User" or "Gemini"
    val text: String,
    val timestamp: String = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()),
    val audioBytes: ByteArray? = null,
    val mimeType: String? = null,
    val isBookmarked: Boolean = false
)

class NepaliVoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val audioEngine = AudioEngine(application.applicationContext)
    private val speechRecognizer = com.example.audio.RealtimeSpeechRecognizer(application.applicationContext)
    private val geminiService = GeminiVoiceService()
    private val conversationRepo = ConversationRepository(
        ConversationDatabase.getInstance(application.applicationContext).conversationDao()
    )
    private val prefs = application.getSharedPreferences("nepali_voice_prefs", Context.MODE_PRIVATE)
    val hapticHelper = HapticHelper(application.applicationContext)

    // Full-duplex Live WebSocket Client (gemini-3.1-flash-live-preview)
    private var liveWebSocketClient: GeminiLiveWebSocketClient? = null
    private val liveAudioAccumulator = java.io.ByteArrayOutputStream()
    private val liveTextAccumulator = StringBuilder()
    private var liveTurnStartTime = 0L
    private var liveFirstChunkReceived = false
    private var hasLiveChunksEmittedInTurn = false

    private var lastRecognizedSpeech: String? = null
    private var activeSpeculativeJob: Job? = null
    @Volatile
    private var cachedSpeculativePrediction: com.example.api.SpeculativePrediction? = null
    private val speculativeLock = Any()

    private fun onAiSpeakingFinished() {
        if (_voiceState.value == VoiceState.SPEAKING) {
            if (_isContinuousMode.value) {
                _voiceState.value = VoiceState.LISTENING
                _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
                startListening()
            } else {
                _voiceState.value = VoiceState.IDLE
            }
        }
    }

    // Multi-key pool for free tier quota rotation
    private var currentKeyIndex = 0
    @Volatile
    private var lastUserActivityTimestamp = System.currentTimeMillis()

    private fun getAvailableApiKeys(): List<String> {
        val userEntered = _apiKey.value.trim()
        val customKeys = if (userEntered.isNotBlank()) {
            userEntered.split(",", ";", "\n", " ")
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length > 10 }
        } else {
            emptyList()
        }
        val envKeys = listOfNotNull(
            runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()?.trim(),
            runCatching { BuildConfig.INJECTED_GEMINI_API_KEY }.getOrNull()?.trim(),
            runCatching { System.getenv("GEMINI_API_KEY") }.getOrNull()?.trim()
        ).filter { it.isNotBlank() && !it.startsWith("MY_") && !it.startsWith("your_") }

        val combined = (customKeys + envKeys).distinct()
        return if (combined.isNotEmpty()) combined else listOfNotNull(geminiService.getApiKey(userEntered))
    }

    private fun getActiveApiKey(): String? {
        val keys = getAvailableApiKeys()
        if (keys.isEmpty()) return null
        val idx = currentKeyIndex % keys.size
        return keys[idx]
    }

    private fun rotateToNextApiKey(reason: String) {
        val keys = getAvailableApiKeys()
        if (keys.size <= 1) {
            Log.w("NepaliVoiceVM", "Cannot rotate API key: only ${keys.size} key available ($reason)")
            return
        }
        currentKeyIndex = (currentKeyIndex + 1) % keys.size
        val nextKey = keys[currentKeyIndex]
        val masked = if (nextKey.length > 8) "${nextKey.take(4)}...${nextKey.takeLast(4)}" else "***"
        Log.i("NepaliVoiceVM", "Rate-limit/Quota encountered ($reason). Instantly rotating to key pool index #$currentKeyIndex ($masked)")
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = null
        viewModelScope.launch {
            geminiService.prewarm(nextKey, _selectedVoice.value, _currentPersona.value, viewModelScope)
            ensureLiveClientConnected()
        }
    }

    private fun ensureLiveClientConnected() {
        val key = getActiveApiKey() ?: return
        if (liveWebSocketClient != null && liveWebSocketClient!!.isReady()) {
            return
        }
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = GeminiLiveWebSocketClient(
            apiKey = key,
            voiceName = _selectedVoice.value,
            persona = _currentPersona.value,
            onAudioChunkReceived = { pcmChunk ->
                lastUserActivityTimestamp = System.currentTimeMillis()
                // Requirement 3: Instant Playback via AudioTrack Streaming (MODE_STREAM)
                hasLiveChunksEmittedInTurn = true
                viewModelScope.launch(Dispatchers.Main) {
                    if (!liveFirstChunkReceived && liveTurnStartTime > 0L) {
                        liveFirstChunkReceived = true
                        _latencyMs.value = System.currentTimeMillis() - liveTurnStartTime
                    }
                    if (_voiceState.value != VoiceState.SPEAKING) {
                        _voiceState.value = VoiceState.SPEAKING
                    }
                }
                synchronized(liveAudioAccumulator) {
                    liveAudioAccumulator.write(pcmChunk)
                }
                audioEngine.writeLiveAudioChunk(pcmChunk)
            },
            onTextChunkReceived = { textChunk ->
                lastUserActivityTimestamp = System.currentTimeMillis()
                viewModelScope.launch(Dispatchers.Main) {
                    liveTextAccumulator.append(textChunk)
                    val fullText = liveTextAccumulator.toString().trim()
                    if (fullText.isNotEmpty()) {
                        _currentAiResponse.value = fullText
                    }
                }
            },
            onInterrupted = {
                lastUserActivityTimestamp = System.currentTimeMillis()
                // Server-side VAD interruption detection
                viewModelScope.launch(Dispatchers.Main) {
                    onBargeInTriggered()
                }
            },
            onTurnComplete = {
                lastUserActivityTimestamp = System.currentTimeMillis()
                viewModelScope.launch(Dispatchers.Main) {
                    onLiveTurnFinished()
                }
            },
            onError = { err ->
                Log.w("NepaliVoiceVM", "Gemini Live WebSocket notice: $err")
                val isQuotaErr = err.contains("quota", ignoreCase = true) ||
                    err.contains("429") ||
                    err.contains("resource_exhausted", ignoreCase = true) ||
                    err.contains("limit", ignoreCase = true)
                if (isQuotaErr) {
                    viewModelScope.launch(Dispatchers.Main) {
                        rotateToNextApiKey("WebSocket 429 quota error: $err")
                    }
                }
            }
        ).also { client ->
            client.connect {
                Log.d("NepaliVoiceVM", "Gemini Live WebSocket successfully connected and setup completed!")
            }
        }
    }

    private fun onLiveTurnFinished() {
        val audioBytes = synchronized(liveAudioAccumulator) {
            val bytes = liveAudioAccumulator.toByteArray()
            liveAudioAccumulator.reset()
            bytes
        }
        val text = liveTextAccumulator.toString().trim()
        liveTextAccumulator.setLength(0)

        if (text.isNotBlank()) {
            val aiItem = ChatItem(
                sender = "Gemini",
                text = text,
                audioBytes = if (audioBytes.isNotEmpty()) audioBytes else null,
                mimeType = "audio/pcm;rate=24000"
            )
            _history.value = listOf(aiItem) + _history.value
            _currentAiResponse.value = text
            viewModelScope.launch {
                conversationRepo.addMessage("model", text)
            }
        }
        if (audioBytes.isNotEmpty()) {
            _latestAudioBytes.value = audioBytes
            _latestMimeType.value = "audio/pcm;rate=24000"
        }

        audioEngine.drainLiveAudioTrack(viewModelScope) {
            if (_voiceState.value == VoiceState.SPEAKING) {
                if (_isContinuousMode.value) {
                    _voiceState.value = VoiceState.LISTENING
                    _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
                    startListening()
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        }
    }

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _currentPrompt = MutableStateFlow("तपाईंलाई कस्तो मद्दत चाहिन्छ?")
    val currentPrompt: StateFlow<String> = _currentPrompt.asStateFlow()

    private val _currentAiResponse = MutableStateFlow("नमस्ते! म तपाईंको नेपाली भ्वाइस साथी हुँ।")
    val currentAiResponse: StateFlow<String> = _currentAiResponse.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _latencyMs = MutableStateFlow<Long?>(null)
    val latencyMs: StateFlow<Long?> = _latencyMs.asStateFlow()

    private val _isContinuousMode = MutableStateFlow(false)
    val isContinuousMode: StateFlow<Boolean> = _isContinuousMode.asStateFlow()

    private val _currentPersona = MutableStateFlow(
        NepaliPersona.fromId(prefs.getString("selected_persona", NepaliPersona.BUDDY.id) ?: NepaliPersona.BUDDY.id)
    )
    val currentPersona: StateFlow<NepaliPersona> = _currentPersona.asStateFlow()

    val audioAmplitude: StateFlow<Float> = audioEngine.amplitude
        .stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    val isSpeechDetected: StateFlow<Boolean> = audioEngine.isSpeechDetected
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val isAudioPlaying: StateFlow<Boolean> = audioEngine.isAudioPlaying
    val isStreamPrewarmed: StateFlow<Boolean> = audioEngine.isStreamPrewarmed
    val initialPlaybackLagMs: StateFlow<Long> = audioEngine.initialPlaybackLagMs

    private val _latestAudioBytes = MutableStateFlow<ByteArray?>(null)
    val latestAudioBytes: StateFlow<ByteArray?> = _latestAudioBytes.asStateFlow()

    private val _latestMimeType = MutableStateFlow<String?>("audio/L16;codec=pcm;rate=24000")
    val latestMimeType: StateFlow<String?> = _latestMimeType.asStateFlow()

    private val _selectedVoice = MutableStateFlow(prefs.getString("selected_voice", "Puck") ?: "Puck")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString("gemini_api_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(prefs.getFloat("playback_speed", 1.0f))
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _silenceTimeoutMs = MutableStateFlow(prefs.getLong("silence_timeout_ms", 220L).coerceIn(150L, 1000L))
    val silenceTimeoutMs: StateFlow<Long> = _silenceTimeoutMs.asStateFlow()

    fun setSilenceTimeout(timeout: Long) {
        val valid = timeout.coerceIn(150L, 2000L)
        _silenceTimeoutMs.value = valid
        prefs.edit().putLong("silence_timeout_ms", valid).apply()
        audioEngine.setSilenceTimeoutMs(valid)
        hapticHelper.tick()
    }

    fun setPlaybackSpeed(speed: Float) {
        val validSpeed = speed.coerceIn(0.7f, 1.5f)
        _playbackSpeed.value = validSpeed
        prefs.edit().putFloat("playback_speed", validSpeed).apply()
        audioEngine.setPlaybackSpeed(validSpeed)
        hapticHelper.tick()
    }

    fun askQuickTopic(topicText: String) {
        hapticHelper.click()
        sendTextMessage(topicText)
    }

    private val _history = MutableStateFlow<List<ChatItem>>(
        listOf(
            ChatItem(
                sender = "Gemini",
                text = "नमस्ते! म तपाईंको व्यक्तिगत नेपाली भ्वाइस एआई साथी हुँ। तपाईं जे पनि सोध्न सक्नुहुन्छ।"
            )
        )
    )
    val history: StateFlow<List<ChatItem>> = _history.asStateFlow()

    private var activeCallJob: Job? = null

    init {
        audioEngine.setPlaybackSpeed(_playbackSpeed.value)
        audioEngine.setSilenceTimeoutMs(_silenceTimeoutMs.value)
        // Pre-warm AudioTrack stream buffer ahead of time to eliminate initial playback lag
        audioEngine.prewarmAudioStreamBuffer(viewModelScope)
        viewModelScope.launch {
            conversationRepo.allMessages.collect { entities ->
                if (entities.isNotEmpty()) {
                    val items = entities.reversed().map { entity ->
                        ChatItem(
                            id = entity.id.toString(),
                            sender = if (entity.role == "user") "User" else "Gemini",
                            text = entity.text,
                            timestamp = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(entity.timestamp)),
                            isBookmarked = entity.isBookmarked
                        )
                    }
                    _history.value = items
                }
            }
        }
        viewModelScope.launch {
            val key = geminiService.getApiKey(_apiKey.value)
            if (!key.isNullOrBlank()) {
                ensureLiveClientConnected()
                geminiService.prewarm(key, _selectedVoice.value, _currentPersona.value, viewModelScope)
            }
        }
        // Requirement 2 & 3: Smart Idle Management
        // - Closes the WebSocket session if idle for >35 seconds to avoid consuming keep-alive tokens on free quotas.
        // - Reconnects instantly when mic or conversation activity resumes.
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(8000L)
                val idleDuration = System.currentTimeMillis() - lastUserActivityTimestamp
                if (_voiceState.value == VoiceState.IDLE) {
                    val client = liveWebSocketClient
                    if (idleDuration > 35000L) {
                        // User inactive >35 seconds: disconnect socket to preserve free quota tokens
                        if (client != null && client.isConnected()) {
                            Log.d("NepaliVoiceVM", "Smart Idle: Inactive for ${idleDuration / 1000}s. Closing WebSocket to preserve free quota.")
                            client.disconnect()
                            liveWebSocketClient = null
                        }
                    } else {
                        // Active session: keep connection warm
                        if (client != null && client.isReady()) {
                            client.sendKeepAlivePing()
                        } else if (client == null || !client.isConnected()) {
                            ensureLiveClientConnected()
                        }
                    }
                }
            }
        }
    }

    private var isTogglingMic = false
    private var lastMicToggleTime = 0L

    /**
     * Primary mic button toggle:
     * - If IDLE: starts listening.
     * - If LISTENING: stops listening, immediately processes voice.
     * - If SPEAKING or PROCESSING: INTERRUPTS Gemini immediately and starts fresh listening!
     */
    fun onMicToggled() {
        val now = System.currentTimeMillis()
        if (now - lastMicToggleTime < 250L || isTogglingMic) {
            return
        }
        lastMicToggleTime = now
        isTogglingMic = true
        hapticHelper.click()

        try {
            when (_voiceState.value) {
                VoiceState.IDLE, VoiceState.ERROR -> {
                    startListening()
                }
                VoiceState.LISTENING -> {
                    stopListeningAndProcess()
                }
                VoiceState.SPEAKING, VoiceState.PROCESSING -> {
                    // Barge-In: user interrupts Gemini speech!
                    // Instantly abort previous audio & network call, and start listening fresh
                    interruptAndStop()
                    hapticHelper.bargeInPulse()
                    startListening()
                }
            }
        } finally {
            isTogglingMic = false
        }
    }

    /**
     * Instantly aborts ongoing Gemini speech, network streaming, and active jobs.
     */
    private fun interruptAndStop() {
        activeCallJob?.cancel()
        geminiService.cancelAllActiveCalls()
        liveWebSocketClient?.interrupt()
        audioEngine.clientSideBargeIn()
        audioEngine.stopPlayback()
        audioEngine.stopRecording()
        speechRecognizer.stopListening()
    }

    private fun startListening() {
        _errorMessage.value = null
        interruptAndStop()
        lastRecognizedSpeech = null

        val key = geminiService.getApiKey(_apiKey.value)
        if (key.isNullOrEmpty()) {
            _errorMessage.value = "Gemini API Key फेला परेन। कृपया Settings ⚙️ मा आफ्नो नि:शुल्क Gemini API Key राख्नुहोस्।"
            _voiceState.value = VoiceState.ERROR
            return
        }

        // Connect/verify live WebSocket session (gemini-3.1-flash-live-preview)
        ensureLiveClientConnected()
        liveTurnStartTime = 0L
        liveFirstChunkReceived = false
        hasLiveChunksEmittedInTurn = false
        synchronized(liveAudioAccumulator) { liveAudioAccumulator.reset() }
        liveTextAccumulator.setLength(0)

        // Start concurrent on-device real-time speech recognition
        if (speechRecognizer.isRecognitionAvailable) {
            speechRecognizer.startListening(
                onFinalResult = { text ->
                    if (text.isNotBlank()) {
                        lastRecognizedSpeech = text
                        _currentPrompt.value = text
                    }
                },
                onPartialResult = { partial ->
                    if (partial.isNotBlank()) {
                        _currentPrompt.value = partial
                        triggerSpeculativePrediction(partial)
                    }
                },
                onError = {
                    // Fallback to raw AudioEngine recording if speech recognizer encounters issue
                }
            )
        }

        val started = audioEngine.startRecording(
            coroutineScope = viewModelScope,
            autoSilenceDetection = true,
            isBargeInActive = _isContinuousMode.value,
            onSpeechDetected = {
                val partial = speechRecognizer.partialText.value
                _currentPrompt.value = if (partial.isNotBlank()) partial else "सुन्दैछ... बोल्नुहोस्"
                hapticHelper.tick()
            },
            onSpeechFinished = {
                viewModelScope.launch(Dispatchers.Main) {
                    if (_voiceState.value == VoiceState.LISTENING) {
                        stopListeningAndProcess()
                    }
                }
            },
            onBargeIn = {
                viewModelScope.launch(Dispatchers.Main) {
                    onBargeInTriggered()
                }
            },
            onAudioChunkRecorded = { chunkBytes, _ ->
                // Requirement 4: Temporarily pause streaming microphone chunks to Gemini until the model finishes its audio response
                if (_voiceState.value == VoiceState.LISTENING) {
                    liveWebSocketClient?.sendRealtimeAudioChunk(chunkBytes)
                }
            }
        )

        if (started) {
            _voiceState.value = VoiceState.LISTENING
            // Pre-warm audio stream buffer so sound output is instantaneous
            audioEngine.prewarmAudioStreamBuffer(viewModelScope)
            _currentPrompt.value = if (_isContinuousMode.value) {
                "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
            } else {
                "सुन्दैछ... बोल्नुहोस् (Listening...)"
            }
            viewModelScope.launch {
                geminiService.prewarm(_apiKey.value, _selectedVoice.value, _currentPersona.value, viewModelScope)
            }
        } else {
            _errorMessage.value = "माइक्रोफोन सुरु हुन सकेन। कृपया अडियो अनुमति जाँच गर्नुहोस्।"
            _voiceState.value = VoiceState.ERROR
        }
    }

    private fun onBargeInTriggered() {
        hapticHelper.bargeInPulse()
        // Requirement 4: Client-side barge-in: immediately clear AudioTrack buffer (pause & flush)
        audioEngine.clientSideBargeIn()
        liveWebSocketClient?.interrupt()
        interruptAndStop()
        _currentPrompt.value = "सुन्दैछ... बोल्नुहोस्"
        _voiceState.value = VoiceState.LISTENING
        startListening()
    }

    private fun stopListeningAndProcess() {
        _voiceState.value = VoiceState.PROCESSING
        // Instant <15ms audible acoustic response cue so sound starts playing immediately
        audioEngine.playInstantAcknowledgmentCue(viewModelScope)
        hapticHelper.tick()

        speechRecognizer.stopListening()
        val fastRecognizedText = lastRecognizedSpeech?.takeIf { it.isNotBlank() }
            ?: speechRecognizer.partialText.value.takeIf { it.isNotBlank() }

        // ULTRA-FAST PATH (Full-Duplex Gemini Live WebSocket):
        // Audio has already been streamed in real-time 40ms chunks to the WebSocket!
        if (liveWebSocketClient != null && liveWebSocketClient!!.isReady()) {
            liveTurnStartTime = System.currentTimeMillis()
            liveFirstChunkReceived = false
            hasLiveChunksEmittedInTurn = false
            synchronized(liveAudioAccumulator) { liveAudioAccumulator.reset() }
            liveTextAccumulator.setLength(0)

            audioEngine.prewarmAudioStreamBuffer(viewModelScope)
            audioEngine.initDirectLiveAudioTrack()
            audioEngine.stopRecording()

            if (!fastRecognizedText.isNullOrBlank()) {
                _currentPrompt.value = fastRecognizedText
                val userItem = ChatItem(sender = "User", text = fastRecognizedText)
                _history.value = listOf(userItem) + _history.value
                viewModelScope.launch {
                    conversationRepo.addMessage("user", fastRecognizedText)
                }
                liveWebSocketClient?.sendTurnCompleteWithText(fastRecognizedText)
            } else {
                _currentPrompt.value = "तपाईंको आवाज विश्लेषण गर्दै..."
                liveWebSocketClient?.sendTurnComplete()
            }
            return
        }

        // FASTEST REST PATH (<350ms): If real-time recognition captured text, NEVER wait or send raw audio WAV!
        if (!fastRecognizedText.isNullOrBlank()) {
            _currentPrompt.value = fastRecognizedText
            val userItem = ChatItem(sender = "User", text = fastRecognizedText)
            _history.value = listOf(userItem) + _history.value
            viewModelScope.launch {
                conversationRepo.addMessage("user", fastRecognizedText)
            }
            callGeminiApi(audioBase64Wav = null, textPrompt = fastRecognizedText)
            return
        }

        // REST PATH FALLBACK: If speech recognizer had no text, extract recorded audio
        val audioBase64Wav = if (_isContinuousMode.value) {
            audioEngine.extractCurrentRecordedAudio() ?: audioEngine.stopRecording()
        } else {
            audioEngine.stopRecording()
        }

        if (audioBase64Wav.isNullOrEmpty()) {
            if (_isContinuousMode.value) {
                _voiceState.value = VoiceState.LISTENING
                startListening()
            } else {
                _voiceState.value = VoiceState.IDLE
            }
            return
        }

        _currentPrompt.value = "तपाईंको आवाज विश्लेषण गर्दै..."
        callGeminiApi(audioBase64Wav = audioBase64Wav, textPrompt = null)
    }

    /**
     * Sends typed text prompt directly.
     * INSTANTLY stops any ongoing Gemini voice generation, speech playback, or mic recording
     * and replies to the new input in the current conversational situation.
     */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        // Instant interruption: abort ongoing speech/network calls
        interruptAndStop()
        hapticHelper.click()

        _voiceState.value = VoiceState.PROCESSING
        _currentPrompt.value = text

        val userItem = ChatItem(sender = "User", text = text)
        _history.value = listOf(userItem) + _history.value
        viewModelScope.launch {
            conversationRepo.addMessage("user", text)
        }

        audioEngine.prewarmAudioStreamBuffer(viewModelScope)
        if (liveWebSocketClient != null && liveWebSocketClient!!.isReady()) {
            liveTurnStartTime = System.currentTimeMillis()
            liveFirstChunkReceived = false
            hasLiveChunksEmittedInTurn = false
            synchronized(liveAudioAccumulator) { liveAudioAccumulator.reset() }
            liveTextAccumulator.setLength(0)
            audioEngine.initDirectLiveAudioTrack()
            liveWebSocketClient?.sendTurnCompleteWithText(text)
        } else {
            callGeminiApi(audioBase64Wav = null, textPrompt = text)
        }
    }

    private fun callGeminiApi(audioBase64Wav: String?, textPrompt: String?) {
        activeCallJob?.cancel()
        audioEngine.prewarmAudioStreamBuffer(viewModelScope)
        activeCallJob = viewModelScope.launch {
            // Requirement 1: Limit conversation context window to last 2-3 turns (max 4 history items)
            // Drastically reduces tokens per turn to stay well beneath free TPM / RPM rate limits
            val allHistoryReversed = _history.value.reversed()
            val priorItems = if (textPrompt != null && allHistoryReversed.lastOrNull()?.text == textPrompt) {
                allHistoryReversed.dropLast(1)
            } else {
                allHistoryReversed
            }
            val historyPairs = priorItems.takeLast(4).map { item ->
                val role = if (item.sender == "User") "user" else "model"
                Pair(role, item.text)
            }

            val onPlaybackFinished: () -> Unit = {
                if (_voiceState.value == VoiceState.SPEAKING) {
                    if (_isContinuousMode.value) {
                        _voiceState.value = VoiceState.LISTENING
                        _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
                        startListening()
                    } else {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            }

            var userTranscribedEmitted = false
            var firstChunkReceived = false

            val activeKey = getActiveApiKey()
            val result = geminiService.converseNepaliStreaming(
                audioBase64Wav = audioBase64Wav,
                textPrompt = textPrompt,
                voiceName = _selectedVoice.value,
                customApiKey = activeKey,
                persona = _currentPersona.value,
                history = historyPairs,
                onUserSpeechTranscribed = { recognizedUserSpeech ->
                    userTranscribedEmitted = true
                    _currentPrompt.value = recognizedUserSpeech
                    val userItem = ChatItem(sender = "User", text = recognizedUserSpeech)
                    _history.value = listOf(userItem) + _history.value
                    viewModelScope.launch {
                        conversationRepo.addMessage("user", recognizedUserSpeech)
                    }
                },
                onFirstAudioChunk = {
                    firstChunkReceived = true
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.startStreamingPlayback(
                        coroutineScope = viewModelScope,
                        sampleRate = 24000,
                        onFinished = onPlaybackFinished
                    )
                },
                onAudioChunk = { chunk ->
                    if (!firstChunkReceived) {
                        firstChunkReceived = true
                        audioEngine.startStreamingPlayback(
                            coroutineScope = viewModelScope,
                            sampleRate = 24000,
                            onFinished = onPlaybackFinished
                        )
                    }
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.enqueueStreamChunk(chunk)
                },
                onTextChunk = { partialText ->
                    _currentAiResponse.value = partialText
                }
            )

            result.onSuccess { voiceResult ->
                _latencyMs.value = voiceResult.latencyMs
                _currentAiResponse.value = voiceResult.textResponse
                _latestAudioBytes.value = voiceResult.audioBytes
                _latestMimeType.value = voiceResult.mimeType

                if (audioBase64Wav != null && !userTranscribedEmitted) {
                    val fallbackSpeech = if (!_currentPrompt.value.startsWith("तपाईंको आवाज विश्लेषण")) {
                        _currentPrompt.value
                    } else {
                        "तपाईंको आवाज (Voice Input)"
                    }
                    _currentPrompt.value = fallbackSpeech
                    val userItem = ChatItem(sender = "User", text = fallbackSpeech)
                    _history.value = listOf(userItem) + _history.value
                }

                val aiItem = ChatItem(
                    sender = "Gemini",
                    text = voiceResult.textResponse,
                    audioBytes = voiceResult.audioBytes,
                    mimeType = voiceResult.mimeType
                )
                _history.value = listOf(aiItem) + _history.value
                viewModelScope.launch {
                    conversationRepo.addMessage("model", voiceResult.textResponse)
                }

                // If streaming chunks were queued, signal finalization so track drains cleanly
                if (firstChunkReceived) {
                    audioEngine.finalizeStreaming()
                } else if (voiceResult.audioBytes != null && voiceResult.audioBytes.isNotEmpty()) {
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.playGeminiVoice(
                        audioBytes = voiceResult.audioBytes,
                        mimeType = voiceResult.mimeType ?: "audio/L16;codec=pcm;rate=24000",
                        coroutineScope = viewModelScope,
                        onCompletion = {
                            onPlaybackFinished()
                        }
                    )
                } else {
                    if (_isContinuousMode.value) {
                        _voiceState.value = VoiceState.LISTENING
                        _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
                        startListening()
                    } else {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            }.onFailure { error ->
                audioEngine.stopPlayback()
                _voiceState.value = VoiceState.ERROR
                _errorMessage.value = error.message ?: "त्रुटि भयो। कृपया फेरि प्रयास गर्नुहोस्।"
                val isQuotaError = error is com.example.api.QuotaExceededException ||
                    error.message?.contains("Quota", ignoreCase = true) == true ||
                    error.message?.contains("429") == true ||
                    error.message?.contains("Rate Limit", ignoreCase = true) == true ||
                    error.message?.contains("resource_exhausted", ignoreCase = true) == true
                if (isQuotaError) {
                    rotateToNextApiKey("HTTP 429 quota error")
                }
                if (_isContinuousMode.value) {
                    if (isQuotaError && getAvailableApiKeys().size <= 1) {
                        _isContinuousMode.value = false
                    } else {
                        viewModelScope.launch {
                            delay(1200)
                            if (_isContinuousMode.value && _voiceState.value == VoiceState.ERROR) {
                                _voiceState.value = VoiceState.IDLE
                                _errorMessage.value = null
                                startListening()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Early Predictive Generation & Speculative Execution.
     * Evaluates partial transcripts in the background while user is speaking.
     */
    private fun triggerSpeculativePrediction(partialText: String) {
        val trimmed = partialText.trim()
        if (trimmed.length < 5) return
        activeSpeculativeJob?.cancel()
        activeSpeculativeJob = viewModelScope.launch(Dispatchers.IO) {
            delay(120) // Debounce rapid syllable updates
            val apiKey = geminiService.getApiKey(_apiKey.value) ?: return@launch
            val prediction = geminiService.generateSpeculativePrediction(
                partialPrompt = trimmed,
                apiKey = apiKey,
                voiceName = _selectedVoice.value,
                persona = _currentPersona.value
            )
            if (prediction != null && isActive) {
                synchronized(speculativeLock) {
                    cachedSpeculativePrediction = prediction
                }
                Log.d("NepaliVoiceVM", "Speculative response pre-computed: '${prediction.prompt}' -> '${prediction.textResponse}'")
            }
        }
    }

    private fun isSpeculativeMatch(actual: String, predicted: String): Boolean {
        val a = actual.trim().lowercase()
        val p = predicted.trim().lowercase()
        if (a == p) return true
        if (a.startsWith(p) && (a.length - p.length) <= 15) return true
        if (p.startsWith(a) && (p.length - a.length) <= 15) return true
        return false
    }

    fun toggleContinuousMode() {
        val newState = !_isContinuousMode.value
        _isContinuousMode.value = newState
        if (newState) {
            if (_voiceState.value == VoiceState.IDLE || _voiceState.value == VoiceState.ERROR) {
                startListening()
            }
        }
    }

    fun copyResponse(context: Context, textToCopy: String? = null) {
        val text = textToCopy?.takeIf { it.isNotBlank() } ?: _currentAiResponse.value
        if (text.isBlank()) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Gemini Nepali Response", text)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(context, "जवाफ प्रतिलिपि भयो (Copied to clipboard)", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun clearHistory() {
        _history.value = listOf(
            ChatItem(
                sender = "Gemini",
                text = "कुराकानी रिसेट भयो। नयाँ कुराकानी सुरु गर्न बोल्नुहोस् वा टाइप गर्नुहोस्।"
            )
        )
        _currentAiResponse.value = "नमस्ते! म तपाईंलाई कसरी सहयोग गर्न सक्छु?"
        viewModelScope.launch {
            conversationRepo.clearHistory()
        }
        hapticHelper.click()
    }

    private var testPromptIndex = 0

    fun testGeminiVoice() {
        val testPrompts = listOf(
            "नमस्ते! म तपाईंको नेपाली भ्वाइस एआई साथी हुँ। आवाज एकदमै स्पष्ट सुनिँदैछ।",
            "नमस्ते! म तपाईंको आवाज सुन्न तयार छु। माइक थिचेर नेपालीमा बोल्नुहोस्।",
            "नेपाली भाषामा कुराकानी गर्न मलाई धेरै रमाइलो लाग्छ। तपाईंलाई कस्तो छ?"
        )
        val prompt = testPrompts[testPromptIndex % testPrompts.size]
        testPromptIndex++

        interruptAndStop()
        _voiceState.value = VoiceState.PROCESSING
        _currentPrompt.value = "आवाज परीक्षण गर्दै..."

        viewModelScope.launch {
            val synthesized = geminiService.synthesizeGeminiVoicePublic(
                text = prompt,
                voiceName = _selectedVoice.value,
                customApiKey = _apiKey.value.takeIf { it.isNotBlank() }
            )

            if (synthesized != null && synthesized.first.isNotEmpty()) {
                _currentAiResponse.value = prompt
                _latestAudioBytes.value = synthesized.first
                _latestMimeType.value = synthesized.second
                _voiceState.value = VoiceState.SPEAKING

                val testItem = ChatItem(
                    sender = "Gemini",
                    text = prompt,
                    audioBytes = synthesized.first,
                    mimeType = synthesized.second
                )
                _history.value = listOf(testItem) + _history.value

                audioEngine.playGeminiVoice(
                    audioBytes = synthesized.first,
                    mimeType = synthesized.second,
                    coroutineScope = viewModelScope,
                    onCompletion = {
                        if (_voiceState.value == VoiceState.SPEAKING) {
                            _voiceState.value = VoiceState.IDLE
                        }
                    }
                )
            } else {
                sendTextMessage(prompt)
            }
        }
    }

    fun onAppBackgrounded() {
        if (_voiceState.value == VoiceState.LISTENING || _voiceState.value == VoiceState.SPEAKING) {
            cancelCurrentOperation()
        }
    }

    fun togglePlayLatestAudio() {
        if (audioEngine.isAudioPlaying.value) {
            audioEngine.stopPlayback()
            _voiceState.value = VoiceState.IDLE
            return
        }

        val audio = _latestAudioBytes.value
        val text = _currentAiResponse.value

        if (audio != null && audio.isNotEmpty()) {
            _voiceState.value = VoiceState.SPEAKING
            audioEngine.playGeminiVoice(
                audioBytes = audio,
                mimeType = _latestMimeType.value ?: "audio/L16;codec=pcm;rate=24000",
                coroutineScope = viewModelScope,
                onCompletion = {
                    if (_voiceState.value == VoiceState.SPEAKING) {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            )
        } else if (text.isNotBlank()) {
            viewModelScope.launch {
                _voiceState.value = VoiceState.PROCESSING
                val synthesized = geminiService.synthesizeGeminiVoicePublic(
                    text = text,
                    voiceName = _selectedVoice.value,
                    customApiKey = _apiKey.value.takeIf { it.isNotBlank() }
                )
                if (synthesized != null) {
                    _latestAudioBytes.value = synthesized.first
                    _latestMimeType.value = synthesized.second
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.playGeminiVoice(
                        audioBytes = synthesized.first,
                        mimeType = synthesized.second,
                        coroutineScope = viewModelScope,
                        onCompletion = {
                            if (_voiceState.value == VoiceState.SPEAKING) {
                                _voiceState.value = VoiceState.IDLE
                            }
                        }
                    )
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        }
    }

    fun cancelCurrentOperation() {
        interruptAndStop()
        _voiceState.value = VoiceState.IDLE
    }

    fun replayAudio(item: ChatItem) {
        interruptAndStop()
        _currentAiResponse.value = item.text
        if (item.audioBytes != null && item.audioBytes.isNotEmpty()) {
            _voiceState.value = VoiceState.SPEAKING
            _latestAudioBytes.value = item.audioBytes
            _latestMimeType.value = item.mimeType
            audioEngine.playGeminiVoice(
                audioBytes = item.audioBytes,
                mimeType = item.mimeType ?: "audio/L16;codec=pcm;rate=24000",
                coroutineScope = viewModelScope,
                onCompletion = {
                    if (_voiceState.value == VoiceState.SPEAKING) {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            )
        } else if (item.text.isNotBlank()) {
            viewModelScope.launch {
                _voiceState.value = VoiceState.PROCESSING
                val synthesized = geminiService.synthesizeGeminiVoicePublic(
                    text = item.text,
                    voiceName = _selectedVoice.value,
                    customApiKey = _apiKey.value.takeIf { it.isNotBlank() }
                )
                if (synthesized != null) {
                    _latestAudioBytes.value = synthesized.first
                    _latestMimeType.value = synthesized.second
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.playGeminiVoice(
                        audioBytes = synthesized.first,
                        mimeType = synthesized.second,
                        coroutineScope = viewModelScope,
                        onCompletion = {
                            if (_voiceState.value == VoiceState.SPEAKING) {
                                _voiceState.value = VoiceState.IDLE
                            }
                        }
                    )
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }
        }
    }

    fun setVoice(voice: String) {
        _selectedVoice.value = voice
        prefs.edit().putString("selected_voice", voice).apply()
        hapticHelper.tick()
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = null
        viewModelScope.launch {
            geminiService.prewarm(_apiKey.value, voice, _currentPersona.value, viewModelScope)
            ensureLiveClientConnected()
        }
    }

    fun setPersona(persona: NepaliPersona) {
        _currentPersona.value = persona
        prefs.edit().putString("selected_persona", persona.id).apply()
        hapticHelper.click()
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = null
        if (persona.defaultVoice != _selectedVoice.value) {
            setVoice(persona.defaultVoice)
        } else {
            viewModelScope.launch {
                geminiService.prewarm(_apiKey.value, _selectedVoice.value, persona, viewModelScope)
                ensureLiveClientConnected()
            }
        }
    }

    fun toggleBookmark(itemId: String) {
        val longId = itemId.toLongOrNull()
        if (longId != null) {
            val current = _history.value.find { it.id == itemId }
            val newBookmarked = !(current?.isBookmarked ?: false)
            viewModelScope.launch {
                conversationRepo.toggleBookmark(longId, newBookmarked)
            }
        }
        _history.value = _history.value.map { item ->
            if (item.id == itemId) {
                item.copy(isBookmarked = !item.isBookmarked)
            } else {
                item
            }
        }
        hapticHelper.tick()
    }

    fun exportTranscript(): String {
        val sb = StringBuilder()
        sb.append("🇳🇵 नेपाली भ्वाइस कुराकानी ट्रान्सक्रिप्ट\n")
        sb.append("शैली (Persona): ${_currentPersona.value.titleNepali} (${_selectedVoice.value})\n")
        sb.append("समय: ${SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date())}\n")
        sb.append("=========================================\n\n")
        _history.value.reversed().forEach { item ->
            val prefix = if (item.sender == "User") "👤 तपाईं" else "✨ नेपाली AI"
            val bookmark = if (item.isBookmarked) " ⭐" else ""
            sb.append("[$prefix - ${item.timestamp}$bookmark]:\n")
            sb.append("${item.text}\n\n")
        }
        return sb.toString()
    }

    fun generateSessionSummary(onSummaryReady: (String) -> Unit) {
        val items = _history.value.filter { it.text.isNotBlank() }
        if (items.isEmpty()) {
            onSummaryReady("कुनै कुराकानी रेकर्ड भएको छैन।")
            return
        }
        val conversationText = items.reversed().joinToString("\n") { "${it.sender}: ${it.text}" }
        viewModelScope.launch(Dispatchers.IO) {
            val prompt = "तपाईं एक नेपाली सहायक हुनुहुन्छ। तलको कुराकानीको २ देखि ३ वटा स्पष्ट नेपाली बुँदामा छोटो सारांश तयार गर्नुहोस्:\n\n$conversationText"
            val result = geminiService.generateSummaryText(prompt, _apiKey.value.takeIf { it.isNotBlank() })
            withContext(Dispatchers.Main) {
                onSummaryReady(result ?: "सारांश तयार गर्न सकिएन।")
            }
        }
    }

    fun setApiKey(key: String) {
        _apiKey.value = key.trim()
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
        _errorMessage.value = null
        hapticHelper.click()
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = null
        viewModelScope.launch {
            geminiService.prewarm(key.trim(), _selectedVoice.value, _currentPersona.value, viewModelScope)
            ensureLiveClientConnected()
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer.destroy()
        audioEngine.release()
        liveWebSocketClient?.disconnect()
        liveWebSocketClient = null
        cancelCurrentOperation()
    }
}
