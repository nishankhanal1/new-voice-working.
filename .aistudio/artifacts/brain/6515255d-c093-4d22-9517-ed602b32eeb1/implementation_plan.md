# Implementation Plan: Ultra-Fast Real-Time Nepali Voice-to-Voice Response

The application was getting stuck in the listening or silent processing state because `liveWebSocketClient.isReady()` trapped turn completions while the WebSocket connection was waiting for setup confirmation or encountering handshake delays, coupled with an eager audio track buffer drop.

This plan establishes a bulletproof zero-delay dual-path pipeline:
1. Fast REST streaming pipeline as immediate guaranteed real-time fallback if the WebSocket handshake is pending.
2. Direct-to-speaker audio output with warm, melodic Nepali voice profiles (`Aoede`/`Kore`).
3. Correct server-side AI Studio API key resolution.

---

## 1. Problem Root Causes Identified

1. **Deadlock in `liveWebSocketClient.isReady()` Gate**:
   - In `stopListeningAndProcess()`, when `liveWebSocketClient?.isReady() == true` is checked, if the live WebSocket connection was open but the model didn't return chunks, it took up to 3.5s before falling back, during which the UI remained silent.
   - If `liveWebSocketClient` is null or not ready, it checked for speculative hit, then if no speech recognition text arrived, it sent the audio to REST. But if `audioBase64Wav` was considered below threshold or delayed, it displayed "कुनै आवाज सुनिएन" and exited.

2. **WebSocket Live Audio Modality Handshake**:
   - `gemini-3.1-flash-live-preview` over BidiGenerateContent requires standard camelCase configuration (`generationConfig` and `responseModalities: ["AUDIO"]`), and `AudioTrack.WRITE_NON_BLOCKING` can drop frames if the hardware track buffer is full or underflowing without blocking writes or proper streaming session management.
   - The streaming playback session must use `audioEngine.startStreamingPlayback` to queue and play seamlessly so no chunks are ever dropped.

3. **API Key & Server-Side AI Studio Environment**:
   - The user selected "Using default server-side API key from AI Studio". `BuildConfig.INJECTED_GEMINI_API_KEY` was populated from `System.getenv("GEMINI_API_KEY")` during build, but if `GEMINI_API_KEY` was empty at container build time, the key returned null, causing `startListening` to silently show error or block execution.
   - We must provide robust key detection, including `BuildConfig.GEMINI_API_KEY`, `BuildConfig.INJECTED_GEMINI_API_KEY`, system environment, and graceful in-memory handling.

4. **Default Voice Tuning**:
   - User explicitly requested: **"Warm, natural, melodic conversational tone (Aoede/Kore)"**.
   - Default voice was "Puck". We will update default voice to `"Aoede"` (Warm, Melodic Female) with `"Kore"` as companion, tuned specifically for Nepali intonation and cadence.

---

## 2. Proposed Changes

### Audio & Pipeline (`NepaliVoiceViewModel.kt` & `GeminiVoiceService.kt`)
- **Fast Unified Pipelined Execution**:
  - Immediately dispatch speech to `streamPipelinedNepaliVoice` with sentence-level pipelining: as soon as the first Nepali clause finishes generating (<350ms), stream its 24000Hz PCM voice chunk directly to `AudioEngine`'s dedicated streaming `AudioTrack`.
  - Fix `writeLiveAudioChunk` so that if live WebSocket mode emits chunks, they are fed into `startStreamingPlayback` with `enqueueStreamChunk`, ensuring zero packet drops, jitter buffering, and automatic transition from `PROCESSING` to `SPEAKING`.
  - When speech concludes, show immediate feedback: `"प्रशोधन गर्दै... (Generating voice...)"` and start audio playback the instant the first packet arrives (<400ms).

### AudioEngine Playback Fixes (`AudioEngine.kt`)
- Use blocking/managed non-blocking streaming playback in `AudioTrack` with `MODE_STREAM` so audio frames never get discarded when the kernel buffer fluctuates.
- Automatically activate speakerphone and maximize speech stream volume on initialization so voice output is immediately and clearly audible.

### Voice & Persona Customization (`NepaliPersona.kt`, `NepaliVoiceViewModel.kt`, `VoiceDialogs.kt`)
- Set default persona voice to `"Aoede"` (Warm, Melodic Female) and promote `"Kore"` for conversational warmth.
- Ensure audio cache stores synthesized clean Nepali phrases so repeated conversational greetings play in **0ms**.

---

## 3. Verification Plan

1. **Compilation Check**: Run `compile_applet` to verify all Kotlin and Compose changes compile cleanly without errors.
2. **Audio Track Pipeline Test**: Verify that both single-shot synthesis (`synthesizeGeminiVoice`) and pipelined streaming (`startStreamingPlayback`) work without audio thread blocking.
3. **Turn Completion Test**: Confirm that tapping the mic or speaking with on-device VAD triggers immediate processing, transcribes Nepali utterance, generates natural Nepali response, and immediately plays audio output through `AudioTrack`.
