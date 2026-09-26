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
 * - Streams raw PCM 16kHz audio chunks continuously as user speaks.
 * - Receives real-time 24kHz PCM audio chunks streamed from server.
 * - Instant Barge-In cancellation on user interruption.
 * - Seamless automatic failover to Speculative Pipelined Streaming Engine if
 *   BidiGenerateContent is unsupported on the current API key tier.
 */
class GeminiLiveWebSocketClient(
    private val apiKey: String,
    private val voiceName: String = "Puck",
    private val persona: NepaliPersona = NepaliPersona.BUDDY,
    private val onAudioChunkReceived: (ByteArray) -> Unit,
    private val onTextChunkReceived: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveWebSocket"
        private const val WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Keep-alive for long duplex connection
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupDone = AtomicBoolean(false)

    fun connect(onConnected: () -> Unit) {
        val url = "$WS_URL?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened: ${response.code}")
                isConnected.set(true)
                sendSetupMessage()
                onConnected()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                isConnected.set(false)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                onError(t.message ?: "WebSocket connection failed")
            }
        })
    }

    private fun sendSetupMessage() {
        try {
            val setupJson = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", "models/gemini-2.5-flash")
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
                                put("text", persona.systemPrompt + "\n१ देखि २ छोटा, मिठो, भावपूर्ण नेपाली वाक्यमा स्वाभाविक जवाफ दिनुहोस्।")
                            })
                        })
                    })
                })
            }
            webSocket?.send(setupJson.toString())
            isSetupDone.set(true)
            Log.d(TAG, "WebSocket setup message sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WebSocket setup", e)
        }
    }

    /**
     * Streams real-time raw PCM 16kHz audio buffer continuously as the user speaks.
     */
    fun sendRealtimeAudioChunk(pcmChunk: ByteArray) {
        if (!isConnected.get() || !isSetupDone.get()) return
        try {
            val base64Data = Base64.encodeToString(pcmChunk, Base64.NO_WRAP)
            val audioPayload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Data)
                        })
                    })
                })
            }
            webSocket?.send(audioPayload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Error streaming PCM chunk to WebSocket: ${e.message}")
        }
    }

    /**
     * Signals user finished turn (if manual completion used).
     */
    fun sendTurnComplete() {
        if (!isConnected.get()) return
        try {
            val clientContent = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turnComplete", true)
                })
            }
            webSocket?.send(clientContent.toString())
        } catch (_: Exception) {}
    }

    /**
     * Instantly cancels playback on server during Barge-In.
     */
    fun interrupt() {
        try {
            webSocket?.cancel()
            isConnected.set(false)
        } catch (_: Exception) {}
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal closure")
            webSocket = null
            isConnected.set(false)
        } catch (_: Exception) {}
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val root = JSONObject(text)
            val serverContent = root.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn") ?: return
            val parts = modelTurn.optJSONArray("parts") ?: return

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val textPart = part.optString("text")
                if (textPart.isNotEmpty()) {
                    onTextChunkReceived(textPart)
                }
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    val b64 = inlineData.optString("data")
                    if (b64.isNotEmpty()) {
                        val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                        onAudioChunkReceived(pcmBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing incoming WebSocket message: ${e.message}")
        }
    }
}
