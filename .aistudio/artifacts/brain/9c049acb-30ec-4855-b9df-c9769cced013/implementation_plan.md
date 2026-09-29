# Ultra-Fast Real-Time Nepali Voice Conversation Architecture Plan

This plan establishes the end-to-end engineering roadmap to make the Nepali Voice AI application instantaneous (<400ms perceived latency), rock-solid, and completely free of audio stalls, latency spikes, or WebSocket disconnect crashes.

---

## 1. Architecture Overview: Full-Duplex Real-Time Pipeline

```
[ Microphone (16kHz PCM) ]
         │
         ▼
[ Adaptive VAD (350ms Silence Cutoff) ]
         │ (streaming PCM chunks ~20-50ms)
         ▼
[ Gemini Live WebSocket Client ] ──(BidiGenerateContent)──► [ Gemini 2.5 Flash Native Audio ]
         ▲                                                               │
         │ (Interruption / Barge-in cancel)                              │ (Streaming PCM 24kHz)
         │                                                               ▼
[ Fast Interruption Controller ] ◄─────── [ Low-Latency Jitter Buffer & AudioTrack ]
                                                             │
                                                             ▼
                                                    [ Device Speaker ]
```

---

## 2. Proposed Changes

### Component 1: Ultra-Low-Latency Full-Duplex Audio Engine (`AudioEngine.kt`)
- **Direct 16kHz PCM Capture**: Record audio directly in 16-bit Mono 16kHz via `AudioRecord` with optimized buffer sizes (e.g., 640-1280 bytes / 20-40ms chunks) for immediate WebSocket streaming without high memory allocations.
- **Low-Latency Streaming AudioTrack Playback**: Configure `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY` in streaming mode. Stream Gemini's raw 24kHz PCM chunks immediately into `AudioTrack.write()` using a small ring buffer (50-80ms depth) to achieve near-instantaneous audio output.
- **Instant Interruption / Barge-in**: When speech is detected while the AI is speaking, immediately:
  1. Call `AudioTrack.pause()` and `AudioTrack.flush()` to instantly drop pending speech.
  2. Send a client-side cancellation or interruption frame to Gemini Live WebSocket.
  3. Resume clean capture for user's new turn.

### Component 2: Optimized Voice Activity Detector (`AdaptiveVoiceActivityDetector.kt`)
- **Fast 350ms Turn-Taking**: Tune silence threshold to 350ms of continuous silence following voice activity, preventing awkward conversational pauses while giving Nepali speakers natural pause breathing room.
- **Dynamic Energy Floor**: Dynamically adapt background ambient noise threshold to avoid false-triggering in noisy environments or clipping quiet Nepali syllables.

### Component 3: Gemini Live WebSocket Protocol & Resilience (`GeminiLiveWebSocketClient.kt`)
- **Model Target**: Use `models/gemini-2.5-flash-native-audio-preview-12-2025` with full-duplex Bidi WebSocket protocol (`v1alpha/GenerativeService.BidiGenerateContent`).
- **Nepali Persona Tuning**: Optimized system instruction requiring direct, concise, natural Nepali spoken conversational responses (e.g., avoiding long monologues, textbook formality, or English switching).
- **Proactive Heartbeat & Auto-Reconnect**: Implement exponential backoff auto-reconnect with session state preservation so dropped connections recover in under 500ms.
- **Seamless REST Fallback**: If WebSocket handshake encounters quota/network issues, seamlessly route the turn to streaming REST (`streamGenerateContent`) with native audio response modality so the user never experiences silence.

### Component 4: Unified Key Resolution & State Management (`NepaliVoiceViewModel.kt`)
- **Key Resolution Hierarchy**:
  1. User custom key from SharedPreferences (via in-app Settings dialog).
  2. Injected environment key (`BuildConfig.INJECTED_GEMINI_API_KEY`).
  3. BuildConfig / .env key.
- **Smooth Reactive State Handling**: Eliminate race conditions during rapid tap-to-talk, toggle mute, or continuous hands-free streaming. Ensure `StateFlow` updates drive the UI with 60fps responsiveness.

### Component 5: Polished Feedback UI (`NepaliVoiceScreen.kt` & Components)
- **Immediate Latency Feedback**: Live visual indicator showing latency metrics (end-of-speech to first-audio ms) and connection state.
- **Dynamic Cosmic Orb & Waveform**: Responsive audio visualizer tracking both user input volume and Gemini output waveforms.

---

## 3. Verification Plan

### Automated Verification
1. **Robolectric & Unit Tests**:
   - Run `gradle :app:testDebugUnitTest` to test VAD silence window calculation, PCM buffer encoding/decoding, and WebSocket JSON serialization.
2. **Compilation & Linting**:
   - Run `compile_applet` to guarantee complete compilation with zero build errors or warnings.

### Manual / Device Verification
1. **Latency Measurement**:
   - Speak a short Nepali phrase ("नमस्ते, आज मौसम कस्तो छ?") and record time from silence detection to first audio packet playback (<400ms target).
2. **Interruption Testing**:
   - Speak while the AI is answering; verify audio stops instantly within 50ms without clicking or artifacts.
3. **Network Resilience**:
   - Toggle airplane mode / disconnect Wi-Fi mid-conversation to verify automatic graceful recovery and error handling.
