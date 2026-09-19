package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.GeminiVoiceService
import com.example.audio.AudioEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val mimeType: String? = null
)

class NepaliVoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val audioEngine = AudioEngine(application.applicationContext)
    private val geminiService = GeminiVoiceService()
    private val prefs = application.getSharedPreferences("nepali_voice_prefs", Context.MODE_PRIVATE)

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

    val audioAmplitude: StateFlow<Float> = audioEngine.amplitude
        .stateIn(viewModelScope, SharingStarted.Lazily, 0f)

    val isAudioPlaying: StateFlow<Boolean> = audioEngine.isAudioPlaying

    private val _latestAudioBytes = MutableStateFlow<ByteArray?>(null)
    val latestAudioBytes: StateFlow<ByteArray?> = _latestAudioBytes.asStateFlow()

    private val _latestMimeType = MutableStateFlow<String?>("audio/pcm;rate=24000")
    val latestMimeType: StateFlow<String?> = _latestMimeType.asStateFlow()

    private val _selectedVoice = MutableStateFlow(prefs.getString("selected_voice", "Puck") ?: "Puck")
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString("gemini_api_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

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

    /**
     * Primary mic button toggle:
     * - If IDLE: starts listening.
     * - If LISTENING: stops listening, immediately calls Gemini AI.
     * - If SPEAKING: stops speech, starts listening fresh.
     * - If PROCESSING: cancels ongoing network call.
     */
    fun onMicToggled() {
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
    }

    private fun startListening() {
        _errorMessage.value = null
        audioEngine.stopPlayback()
        val started = audioEngine.startRecording(viewModelScope)
        if (started) {
            _voiceState.value = VoiceState.LISTENING
            _currentPrompt.value = "सुन्दैछ... बोल्नुहोस् (Listening... Speak now)"
            // Pre-warm HTTP/2 TLS connection in background while user speaks to eliminate connection latency
            viewModelScope.launch {
                geminiService.prewarm(_apiKey.value)
            }
        } else {
            _errorMessage.value = "माइक्रोफोन सुरु हुन सकेन। कृपया अनुमति जाँच गर्नुहोस्।"
            _voiceState.value = VoiceState.ERROR
        }
    }

    private fun stopListeningAndProcess() {
        _voiceState.value = VoiceState.PROCESSING
        val audioBase64Wav = audioEngine.stopRecording()

        if (audioBase64Wav.isNullOrEmpty()) {
            _voiceState.value = VoiceState.IDLE
            _currentPrompt.value = "कुनै आवाज सुनिएन। फेरि प्रयास गर्नुहोस्।"
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
            val result = geminiService.converseNepali(
                audioBase64Wav = audioBase64Wav,
                textPrompt = textPrompt,
                voiceName = _selectedVoice.value,
                customApiKey = _apiKey.value.takeIf { it.isNotBlank() },
                onPartialTextReady = { immediateText ->
                    // Instantly show the response in the UI while audio is preparing/streaming
                    _currentAiResponse.value = immediateText
                }
            )

            result.onSuccess { voiceResult ->
                _latencyMs.value = voiceResult.latencyMs
                _currentAiResponse.value = voiceResult.textResponse
                _latestAudioBytes.value = voiceResult.audioBytes
                _latestMimeType.value = voiceResult.mimeType

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
                            if (_voiceState.value == VoiceState.SPEAKING) {
                                _voiceState.value = VoiceState.IDLE
                            }
                        }
                    )
                } else {
                    _voiceState.value = VoiceState.IDLE
                }
            }.onFailure { error ->
                _voiceState.value = VoiceState.ERROR
                val rawMsg = error.message ?: ""
                _errorMessage.value = when {
                    rawMsg.contains("high demand", ignoreCase = true) || rawMsg.contains("UNAVAILABLE", ignoreCase = true) ->
                        "सर्भरमा अत्यधिक चाप छ। कृपया केही क्षणपछि फेरि प्रयास गर्नुहोस्।"
                    rawMsg.contains("API Key", ignoreCase = true) ->
                        "Gemini API Key आवश्यक छ। कृपया सेटिङ ⚙️ मा गएर API Key राख्नुहोस्।"
                    else -> rawMsg.ifEmpty { "त्रुटि भयो। कृपया फेरि प्रयास गर्नुहोस्।" }
                }
            }
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
     * Replay a previous voice response from history.
     */
    fun replayAudio(item: ChatItem) {
        if (item.audioBytes != null && item.audioBytes.isNotEmpty()) {
            audioEngine.stopPlayback()
            _voiceState.value = VoiceState.SPEAKING
            _currentAiResponse.value = item.text
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
        }
    }

    fun setVoice(voice: String) {
        _selectedVoice.value = voice
        prefs.edit().putString("selected_voice", voice).apply()
    }

    fun setApiKey(key: String) {
        _apiKey.value = key.trim()
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelCurrentOperation()
    }
}
