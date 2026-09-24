package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiVoiceService
import com.example.audio.AudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val geminiService = GeminiVoiceService()
    private val prefs = application.getSharedPreferences("nepali_voice_prefs", Context.MODE_PRIVATE)
    val hapticHelper = HapticHelper(application.applicationContext)

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

    private val _latestMimeType = MutableStateFlow<String?>("audio/pcm;rate=24000")
    val latestMimeType: StateFlow<String?> = _latestMimeType.asStateFlow()

    private val _selectedVoice = MutableStateFlow(prefs.getString("selected_voice", "Puck") ?: "Puck")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString("gemini_api_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(prefs.getFloat("playback_speed", 1.0f))
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    fun setPlaybackSpeed(speed: Float) {
        val validSpeed = speed.coerceIn(0.7f, 1.5f)
        _playbackSpeed.value = validSpeed
        prefs.edit().putFloat("playback_speed", validSpeed).apply()
        audioEngine.setPlaybackSpeed(validSpeed)
        hapticHelper.tick()
    }

    fun askQuickTopic(topicText: String) {
        if (_voiceState.value == VoiceState.PROCESSING) return
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
        viewModelScope.launch {
            geminiService.prewarm(_apiKey.value, _selectedVoice.value, _currentPersona.value, viewModelScope)
            // Pre-synthesize the initial greeting voice in background so audio is pre-buffered on app launch
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
     * - If LISTENING: stops listening, immediately calls Gemini AI.
     * - If SPEAKING: stops speech, starts listening fresh.
     * - If PROCESSING: cancels ongoing network call.
     */
    fun onMicToggled() {
        val now = System.currentTimeMillis()
        if (now - lastMicToggleTime < 350L || isTogglingMic) {
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
                VoiceState.SPEAKING -> {
                    audioEngine.stopPlayback()
                    startListening()
                }
                VoiceState.PROCESSING -> {
                    cancelCurrentOperation()
                }
            }
        } finally {
            isTogglingMic = false
        }
    }

    private fun startListening() {
        _errorMessage.value = null
        audioEngine.stopPlayback()
        val started = audioEngine.startRecording(
            coroutineScope = viewModelScope,
            autoSilenceDetection = true,
            isBargeInActive = _isContinuousMode.value,
            onSpeechDetected = {
                _currentPrompt.value = "सुन्दैछ... बोल्नुहोस्"
                hapticHelper.tick()
            },
            onSpeechFinished = {
                geminiService.liveVoiceClient.commitRealtimeTurn()
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
            onAudioChunkRecorded = { pcmChunk, sampleRate ->
                geminiService.liveVoiceClient.sendRealtimeAudioChunk(pcmChunk, sampleRate)
            }
        )
        if (started) {
            _voiceState.value = VoiceState.LISTENING
            _currentPrompt.value = if (_isContinuousMode.value) {
                "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
            } else {
                "सुन्दैछ... बोल्नुहोस् (Listening... Speak now)"
            }
            // Pre-warm connection and Live WebSocket in background while user speaks to eliminate latency
            viewModelScope.launch {
                geminiService.prewarm(_apiKey.value, _selectedVoice.value, _currentPersona.value, viewModelScope)
            }
        } else {
            _errorMessage.value = "माइक्रोफोन सुरु हुन सकेन। कृपया अनुमति जाँच गर्नुहोस्।"
            _voiceState.value = VoiceState.ERROR
        }
    }

    /**
     * Instantly interrupts AI playback when user begins speaking,
     * canceling the outgoing audio and streaming turn cleanly.
     */
    private fun onBargeInTriggered() {
        hapticHelper.bargeInPulse()
        audioEngine.stopPlayback()
        geminiService.liveVoiceClient.cancelCurrentTurn()
        activeCallJob?.cancel()
        _currentPrompt.value = "सुन्दैछ... बोल्नुहोस्"
        _voiceState.value = VoiceState.LISTENING
    }

    private fun stopListeningAndProcess() {
        _voiceState.value = VoiceState.PROCESSING
        val audioBase64Wav = if (_isContinuousMode.value) {
            audioEngine.extractCurrentRecordedAudio() ?: audioEngine.stopRecording()
        } else {
            audioEngine.stopRecording()
        }

        if (audioBase64Wav.isNullOrEmpty()) {
            if (_isContinuousMode.value) {
                // In continuous mode, continue listening seamlessly without breaking
                _voiceState.value = VoiceState.LISTENING
                _currentPrompt.value = "अविरल कुराकानी सुन्दैछ... बोल्नुहोस्"
            } else {
                _voiceState.value = VoiceState.IDLE
                _currentPrompt.value = "कुनै आवाज सुनिएन। फेरि प्रयास गर्नुहोस्।"
            }
            return
        }

        _currentPrompt.value = "तपाईंको आवाज विश्लेषण गर्दै..."
        callGeminiApi(audioBase64Wav = audioBase64Wav, textPrompt = null)
    }

    /**
     * Sends typed text prompt directly.
     */
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        audioEngine.stopPlayback()
        _voiceState.value = VoiceState.PROCESSING
        _currentPrompt.value = text

        val userItem = ChatItem(sender = "User", text = text)
        _history.value = listOf(userItem) + _history.value

        callGeminiApi(audioBase64Wav = null, textPrompt = text)
    }

    private fun callGeminiApi(audioBase64Wav: String?, textPrompt: String?) {
        activeCallJob?.cancel()
        activeCallJob = viewModelScope.launch {
            var streamingActive = false

            // Extract previous turns for long-term multi-turn conversation memory (bounded window for fast latency)
            val historyPairs = _history.value.reversed().takeLast(16).map { item ->
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
                    streamingActive = true
                    _voiceState.value = VoiceState.SPEAKING
                    hapticHelper.tick()
                    audioEngine.prepareStreamingPlayback(sampleRate = 24000)
                },
                onAudioChunk = { pcmChunk ->
                    audioEngine.writeStreamingChunk(pcmChunk)
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

                if (voiceResult.audioBytes != null && voiceResult.audioBytes.isNotEmpty()) {
                    _voiceState.value = VoiceState.SPEAKING
                    audioEngine.playGeminiVoice(
                        audioBytes = voiceResult.audioBytes,
                        mimeType = voiceResult.mimeType ?: "audio/pcm;rate=24000",
                        coroutineScope = viewModelScope,
                        onCompletion = {
                            onPlaybackFinished()
                        }
                    )
                } else if (streamingActive) {
                    audioEngine.finishStreamingPlayback(viewModelScope, sampleRate = 24000) {
                        onPlaybackFinished()
                    }
                } else if (voiceResult.textResponse.isNotBlank()) {
                    // Safety fallback: ensure real Gemini vocalization is always played
                    viewModelScope.launch {
                        val synthesized = geminiService.synthesizeGeminiVoicePublic(
                            text = voiceResult.textResponse,
                            voiceName = _selectedVoice.value,
                            customApiKey = _apiKey.value.takeIf { it.isNotBlank() }
                        )
                        if (synthesized != null && synthesized.first.isNotEmpty()) {
                            _latestAudioBytes.value = synthesized.first
                            _latestMimeType.value = synthesized.second
                            _voiceState.value = VoiceState.SPEAKING
                            audioEngine.playGeminiVoice(
                                audioBytes = synthesized.first,
                                mimeType = synthesized.second,
                                coroutineScope = viewModelScope,
                                onCompletion = {
                                    onPlaybackFinished()
                                }
                            )
                        } else {
                            onPlaybackFinished()
                        }
                    }
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

    fun toggleContinuousMode() {
        val newState = !_isContinuousMode.value
        _isContinuousMode.value = newState
        if (newState) {
            if (_voiceState.value == VoiceState.IDLE || _voiceState.value == VoiceState.ERROR) {
                startListening()
            }
        }
    }

    /**
     * Copies the Gemini response to the clipboard.
     */
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

    /**
     * Clears conversation history to start fresh.
     */
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

    /**
     * Quick action to test Gemini real voice response immediately with natural rotating prompts.
     * Directly vocalizes the prompt in under 400ms so user has immediate audible proof of voice output.
     */
    fun testGeminiVoice() {
        val testPrompts = listOf(
            "नमस्ते! म तपाईंको नेपाली भ्वाइस एआई साथी हुँ। आवाज एकदमै स्पष्ट सुनिँदैछ।",
            "नमस्ते! म तपाईंको आवाज सुन्न तयार छु। माइक थिचेर नेपालीमा बोल्नुहोस्।",
            "नेपाली भाषामा कुराकानी गर्न मलाई धेरै रमाइलो लाग्छ। तपाईंलाई कस्तो छ?"
        )
        val prompt = testPrompts[testPromptIndex % testPrompts.size]
        testPromptIndex++

        audioEngine.stopPlayback()
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
                // If direct synthesis fails, fallback to full conversation route
                sendTextMessage(prompt)
            }
        }
    }

    /**
     * Lifecycle callback when app moves to background.
     */
    fun onAppBackgrounded() {
        if (_voiceState.value == VoiceState.LISTENING || _voiceState.value == VoiceState.SPEAKING) {
            cancelCurrentOperation()
        }
    }

    /**
     * Toggles or plays the latest audio response directly from the Audio Output UI.
     */
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
                mimeType = _latestMimeType.value ?: "audio/pcm;rate=24000",
                coroutineScope = viewModelScope,
                onCompletion = {
                    if (_voiceState.value == VoiceState.SPEAKING) {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            )
        } else if (text.isNotBlank()) {
            // Synthesize Gemini real voice on-demand
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

    /**
     * Stop all current playback or recording.
     */
    fun cancelCurrentOperation() {
        activeCallJob?.cancel()
        audioEngine.stopRecording()
        audioEngine.stopPlayback()
        _voiceState.value = VoiceState.IDLE
    }

    /**
     * Replay a previous voice response from history (synthesizes on-demand if needed).
     */
    fun replayAudio(item: ChatItem) {
        audioEngine.stopPlayback()
        _currentAiResponse.value = item.text
        if (item.audioBytes != null && item.audioBytes.isNotEmpty()) {
            _voiceState.value = VoiceState.SPEAKING
            _latestAudioBytes.value = item.audioBytes
            _latestMimeType.value = item.mimeType
            audioEngine.playGeminiVoice(
                audioBytes = item.audioBytes,
                mimeType = item.mimeType ?: "audio/pcm;rate=24000",
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
        // If persona has a recommended default voice and user hasn't explicitly locked it, use it
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
        cancelCurrentOperation()
        geminiService.liveVoiceClient.close()
    }
}
