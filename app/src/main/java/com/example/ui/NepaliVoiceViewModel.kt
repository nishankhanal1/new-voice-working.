package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiVoiceService
import com.example.audio.AudioEngine
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
    private val prefs = application.getSharedPreferences("nepali_voice_prefs", Context.MODE_PRIVATE)
    val hapticHelper = HapticHelper(application.applicationContext)

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

    private val _silenceTimeoutMs = MutableStateFlow(prefs.getLong("silence_timeout_ms", 200L))
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
        viewModelScope.launch {
            geminiService.prewarm(_apiKey.value, _selectedVoice.value, _currentPersona.value, viewModelScope)
            // Pre-warm initial greeting in background
            val initialGreeting = _currentAiResponse.value
            val greetingAudio = geminiService.synthesizeGeminiVoicePublic(
                text = initialGreeting,
                voiceName = _selectedVoice.value,
                customApiKey = _apiKey.value.takeIf { it.isNotBlank() }
            )
            if (greetingAudio != null) {
                _latestAudioBytes.value = greetingAudio.first
                _latestMimeType.value = greetingAudio.second
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
        audioEngine.stopPlayback()
        audioEngine.stopRecording()
        speechRecognizer.stopListening()
    }

    private fun startListening() {
        _errorMessage.value = null
        interruptAndStop()
        lastRecognizedSpeech = null

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
            onAudioChunkRecorded = { _, _ -> }
        )

        if (started) {
            _voiceState.value = VoiceState.LISTENING
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
        interruptAndStop()
        _currentPrompt.value = "सुन्दैछ... बोल्नुहोस्"
        _voiceState.value = VoiceState.LISTENING
    }

    private fun stopListeningAndProcess() {
        _voiceState.value = VoiceState.PROCESSING
        // Instant <15ms audible acoustic response cue so sound starts playing immediately
        audioEngine.playInstantAcknowledgmentCue(viewModelScope)
        hapticHelper.tick()

        speechRecognizer.stopListening()
        val fastRecognizedText = lastRecognizedSpeech?.takeIf { it.isNotBlank() }
            ?: speechRecognizer.partialText.value.takeIf { it.isNotBlank() }

        val audioBase64Wav = if (_isContinuousMode.value) {
            audioEngine.extractCurrentRecordedAudio() ?: audioEngine.stopRecording()
        } else {
            audioEngine.stopRecording()
        }

        // Check for 0ms Speculative Execution Hit!
        val recognizedText = fastRecognizedText
        var speculativeHit: com.example.api.SpeculativePrediction? = null
        synchronized(speculativeLock) {
            val cached = cachedSpeculativePrediction
            if (cached != null && recognizedText != null && isSpeculativeMatch(recognizedText, cached.prompt)) {
                speculativeHit = cached
            }
        }

        if (speculativeHit != null && speculativeHit!!.audioBytes != null) {
            Log.d("NepaliVoiceVM", "0ms SPECULATIVE HIT! Playing pre-computed response instantly")
            val hit = speculativeHit!!
            cachedSpeculativePrediction = null
            _currentPrompt.value = recognizedText!!
            val userItem = ChatItem(sender = "User", text = recognizedText)
            val aiItem = ChatItem(sender = "Gemini", text = hit.textResponse, audioBytes = hit.audioBytes, mimeType = hit.mimeType)
            _history.value = listOf(aiItem, userItem) + _history.value
            _currentAiResponse.value = hit.textResponse
            _latestAudioBytes.value = hit.audioBytes
            _latestMimeType.value = hit.mimeType
            _latencyMs.value = 15L
            _voiceState.value = VoiceState.SPEAKING
            audioEngine.playGeminiVoice(
                audioBytes = hit.audioBytes!!,
                mimeType = hit.mimeType ?: "audio/L16;codec=pcm;rate=24000",
                coroutineScope = viewModelScope,
                onCompletion = {
                    onAiSpeakingFinished()
                }
            )
            return
        }

        // If real-time recognition captured the text, send text directly (takes ~350ms vs ~7000ms for audio)
        if (!fastRecognizedText.isNullOrBlank()) {
            _currentPrompt.value = fastRecognizedText
            val userItem = ChatItem(sender = "User", text = fastRecognizedText)
            _history.value = listOf(userItem) + _history.value
            callGeminiApi(audioBase64Wav = null, textPrompt = fastRecognizedText)
            return
        }

        if (audioBase64Wav.isNullOrEmpty()) {
            if (_isContinuousMode.value) {
                _voiceState.value = VoiceState.LISTENING
                _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
            } else {
                _voiceState.value = VoiceState.IDLE
                _currentPrompt.value = "कुनै आवाज सुनिएन। फेरि बोल्नुहोस्।"
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

        callGeminiApi(audioBase64Wav = null, textPrompt = text)
    }

    private fun callGeminiApi(audioBase64Wav: String?, textPrompt: String?) {
        activeCallJob?.cancel()
        activeCallJob = viewModelScope.launch {
            val allHistoryReversed = _history.value.reversed()
            val priorItems = if (textPrompt != null && allHistoryReversed.lastOrNull()?.text == textPrompt) {
                allHistoryReversed.dropLast(1)
            } else {
                allHistoryReversed
            }
            val historyPairs = priorItems.takeLast(6).map { item ->
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

            val result = geminiService.converseNepaliStreaming(
                audioBase64Wav = audioBase64Wav,
                textPrompt = textPrompt,
                voiceName = _selectedVoice.value,
                customApiKey = _apiKey.value.takeIf { it.isNotBlank() },
                persona = _currentPersona.value,
                history = historyPairs,
                onUserSpeechTranscribed = { recognizedUserSpeech ->
                    userTranscribedEmitted = true
                    _currentPrompt.value = recognizedUserSpeech
                    val userItem = ChatItem(sender = "User", text = recognizedUserSpeech)
                    _history.value = listOf(userItem) + _history.value
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
                if (_isContinuousMode.value) {
                    viewModelScope.launch {
                        delay(2000)
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
        viewModelScope.launch {
            geminiService.prewarm(_apiKey.value, voice, _currentPersona.value, viewModelScope)
        }
    }

    fun setPersona(persona: NepaliPersona) {
        _currentPersona.value = persona
        prefs.edit().putString("selected_persona", persona.id).apply()
        hapticHelper.click()
        if (persona.defaultVoice != _selectedVoice.value) {
            setVoice(persona.defaultVoice)
        } else {
            viewModelScope.launch {
                geminiService.prewarm(_apiKey.value, _selectedVoice.value, persona, viewModelScope)
            }
        }
    }

    fun toggleBookmark(itemId: String) {
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
        viewModelScope.launch {
            geminiService.prewarm(key.trim(), _selectedVoice.value, _currentPersona.value, viewModelScope)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer.destroy()
        cancelCurrentOperation()
    }
}
