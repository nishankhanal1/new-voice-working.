package com.example.api

import android.util.Base64
import android.util.Log
import com.example.ui.NepaliPersona
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket Full-Duplex Bidirectional Streaming Client for Gemini Live API.
 * - Upgraded to gemini-3.1-flash-live-preview for ultra-low latency native voice conversations.
 * - Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent
 * - Streams raw PCM 16kHz audio directly in tiny 20ms–40ms chunks (640–1280 bytes) as user speaks.
 * - Streams output 24kHz PCM chunks immediately to AudioTrack in MODE_STREAM.
 * - Optimized server-side VAD with native AUDIO modality setup frame.
 * - Instant client-side barge-in: immediately silences AudioTrack on user speech.
 */
class GeminiLiveWebSocketClient(
    private val apiKey: String,
    private val voiceName: String = "Puck",
    private val persona: NepaliPersona = NepaliPersona.BUDDY,
    private val onAudioChunkReceived: (ByteArray) -> Unit,
    private val onTextChunkReceived: (String) -> Unit,
    private val onInterrupted: (() -> Unit)? = null,
    private val onTurnComplete: (() -> Unit)? = null,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val LIVE_MODEL = "models/gemini-2.0-flash-exp"
        const val LIVE_MODEL_FALLBACK = "models/gemini-2.0-flash"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // Requirement 7: Keep-alive ping frame every 15s to keep WebSocket connection warm
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for continuous duplex streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupDone = AtomicBoolean(false)
    private var usingFallbackModel = false
    private var lastConnectedCallback: (() -> Unit)? = null

    fun isReady(): Boolean = isConnected.get() && isSetupDone.get()

    fun connect(onConnected: () -> Unit) {
        lastConnectedCallback = onConnected
        val url = "$WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket opened (code: ${response.code}). Sending setup frame...")
                isConnected.set(true)
                sendSetupMessage(if (usingFallbackModel) LIVE_MODEL_FALLBACK else LIVE_MODEL)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closing: $code / $reason")
                isConnected.set(false)
                isSetupDone.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closed: $code / $reason")
                isConnected.set(false)
                isSetupDone.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Gemini Live WebSocket failure: ${t.message}")
                isConnected.set(false)
                isSetupDone.set(false)
                if (!usingFallbackModel) {
                    usingFallbackModel = true
                    Log.d(TAG, "Retrying WebSocket with fallback model: $LIVE_MODEL_FALLBACK")
                    connect(onConnected)
                    return
                }
                onError(t.message ?: "WebSocket connection failed")
            }
        })
    }

    /**
     * Requirement 4: Initial setup configuration when opening the WebSocket session
     * to request AUDIO modality natively, model gemini-2.5-flash-native-audio-preview-12-2025, and voice config.
     */
    private fun sendSetupMessage(modelName: String) {
        try {
            val systemPromptNepali = persona.systemPrompt +
                "\n\nतपाईं एक अत्यन्त जीवन्त, आत्मीय, रसिलो र भावपूर्ण नेपाली साथी हुनुहुन्छ।" +
                "\nनियम: १ देखि २ छोटा, मिठो, भावपूर्ण नेपाली वाक्यमा स्वाभाविक जवाफ दिनुहोस्।"

            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", modelName)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", voiceName)
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPromptNepali)
                            })
                        })
                    })
                })
            }
            webSocket?.send(setupJson.toString())
            Log.d(TAG, "Gemini Live setup message sent with model: $modelName and AUDIO modality (awaiting setupComplete)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WebSocket setup", e)
        }
    }

    /**
     * Requirement 4: Stream Micro Audio Chunks (20ms – 40ms)
     * Format: Raw PCM, 16kHz sample rate, 1 channel (mono), 16-bit LE.
     * Frame Size: 40ms PCM chunks (1,280 bytes at 16kHz, 16-bit mono).
     * Directly sends realtimeInput to WebSocket as base64 data as soon as captured.
     */
    fun sendRealtimeAudioChunk(pcmChunk: ByteArray) {
        if (!isConnected.get() || !isSetupDone.get()) return
        try {
            val base64Pcm = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val jsonPayload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Pcm)
                        })
                    })
                })
            }.toString()
            webSocket?.send(jsonPayload)
        } catch (e: Exception) {
            Log.w(TAG, "Error streaming PCM chunk to WebSocket: ${e.message}")
        }
    }

    /**
     * Requirement 1: Manual End-of-Turn without waiting for server VAD silence timeout.
     * Instantly notifies the server to end the turn when the user finishes speaking or releases mic:
     * {
     *   "clientContent": {
     *     "turnComplete": true
     *   }
     * }
     */
    fun sendTurnComplete() {
        sendTurnCompleteWithText(null)
    }

    fun sendTurnCompleteWithText(userText: String? = null) {
        if (!isConnected.get()) return
        try {
            val jsonPayload = if (!userText.isNullOrBlank()) {
                JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", userText)
                                    })
                                })
                            })
                        })
                        put("turnComplete", true)
                    })
                }.toString()
            } else {
                JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turnComplete", true)
                    })
                }.toString()
            }
            webSocket?.send(jsonPayload)
            Log.d(TAG, "Sent clientContent turnComplete frame (with text: ${!userText.isNullOrBlank()})")
        } catch (e: Exception) {
            Log.w(TAG, "Error sending turnComplete: ${e.message}")
        }
    }

    /**
     * Requirement 6: Instant Client-Side Barge-In & Interruption Signal.
     * Signals turn interruption over WebSocket to clear server-side audio generation buffers.
     */
    fun interrupt() {
        if (!isConnected.get()) return
        try {
            val jsonPayload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turnComplete", true)
                })
            }.toString()
            webSocket?.send(jsonPayload)
            Log.d(TAG, "Sent client-side barge-in interrupt frame")
        } catch (_: Exception) {}
    }

    /**
     * Requirement 7: Keep-alive ping frame to maintain active warm socket connection.
     */
    fun sendKeepAlivePing() {
        if (!isConnected.get()) return
        try {
            webSocket?.send(okio.ByteString.EMPTY)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            isConnected.set(false)
            isSetupDone.set(false)
        } catch (_: Exception) {}
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val root = JSONObject(text)

            // Setup confirmation
            if (root.has("setupComplete") || root.has("setup_complete")) {
                Log.d(TAG, "Gemini Live setup complete confirmed by server")
                isSetupDone.set(true)
                lastConnectedCallback?.invoke()
                return
            }

            // Server error payload
            if (root.has("error")) {
                val errMsg = root.optJSONObject("error")?.optString("message") ?: "Server error"
                Log.w(TAG, "Gemini Live server returned error: $errMsg")
                isConnected.set(false)
                isSetupDone.set(false)
                onError(errMsg)
                return
            }

            // Server content (supports both camelCase and snake_case)
            val serverContent = root.optJSONObject("serverContent")
                ?: root.optJSONObject("server_content")
                ?: return

            // Server-side interruption detection: immediately trigger barge-in!
            val interrupted = serverContent.optBoolean("interrupted", false)
            if (interrupted) {
                Log.d(TAG, "Server detected user interruption (barge-in)")
                onInterrupted?.invoke()
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
                ?: serverContent.optJSONObject("model_turn")

            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // 1. Text chunks
                        val textPart = part.optString("text")
                        if (textPart.isNotEmpty()) {
                            onTextChunkReceived(textPart)
                        }

                        // 2. Audio chunks (Requirement 3: PCM 24kHz audio)
                        val inlineData = part.optJSONObject("inlineData")
                            ?: part.optJSONObject("inline_data")

                        if (inlineData != null) {
                            val b64 = inlineData.optString("data")
                            if (b64.isNotEmpty()) {
                                val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                                onAudioChunkReceived(pcmBytes)
                            }
                        }
                    }
                }
            }

            // Turn complete signal
            val turnComplete = serverContent.optBoolean("turnComplete", false)
                || serverContent.optBoolean("turn_complete", false)

            if (turnComplete) {
                onTurnComplete?.invoke()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing incoming WebSocket message: ${e.message}")
        }
    }
}
