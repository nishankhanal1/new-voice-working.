package com.example.api

import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiVoiceResult(
    val audioBytes: ByteArray?,
    val mimeType: String?,
    val textResponse: String,
    val latencyMs: Long
)

/**
 * Ultra-low-latency, resilient Gemini Voice Service.
 * - Handles server-side high demand / 503 / 429 spikes with instant zero-lag model failover.
 * - Prioritizes stable production Flash models (gemini-2.5-flash, gemini-2.0-flash, gemini-1.5-flash).
 * - Caches the currently healthy model to maximize conversation speed.
 * - Delivers instantaneous text feedback to UI via [onPartialTextReady].
 */
class GeminiVoiceService {

    companion object {
        private const val TAG = "GeminiVoiceService"

        // 1-step native multimodal audio conversation model (Speech In -> Audio Out)
        private const val NATIVE_AUDIO_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"

        // Cascading models for resilient high-availability failover
        private val FLASH_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-3.5-flash"
        )

        // Real-voice audio generation models with fallback
        private val TTS_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        )

        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        @Volatile
        private var nativeAudioSupported: Boolean = true

        @Volatile
        private var cachedWorkingModel: String? = null

        @Volatile
        private var cachedWorkingTtsModel: String? = null
    }

    private val connectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Pre-warms the HTTP/2 TLS socket pool to Google API so subsequent API calls
     * do not incur 200-400ms TLS handshakes.
     */
    suspend fun prewarm(customApiKey: String? = null) = withContext(Dispatchers.IO) {
        val key = getApiKey(customApiKey) ?: return@withContext
        try {
            val targetModel = cachedWorkingModel ?: FLASH_MODELS.first()
            val url = "$BASE_URL/$targetModel?key=$key"
            val req = Request.Builder()
                .url(url)
                .head()
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
            // Best effort socket pre-warm
        }
    }

    private fun getApiKey(customApiKey: String?): String? {
        return customApiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "MY_GEMINI_API_KEY" }
    }

    /**
     * Sends voice or text prompt to Gemini.
     * [onPartialTextReady] callback delivers the text immediately to the UI so the user
     * sees the complete response instantly without waiting for audio buffering.
     */
    suspend fun converseNepali(
        audioBase64Wav: String?,
        textPrompt: String?,
        voiceName: String = "Puck",
        customApiKey: String? = null,
        onPartialTextReady: ((String) -> Unit)? = null
    ): Result<GeminiVoiceResult> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(customApiKey)
        if (apiKey.isNullOrEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API Key is missing. Please set your API key in Settings ⚙️.")
            )
        }

        val startTime = System.currentTimeMillis()

        // -----------------------------------------------------------------------------------------
        // PATH 1: Multimodal 1-Step Native Audio Conversation (Speech in -> Audio + Text out)
        // -----------------------------------------------------------------------------------------
        if (nativeAudioSupported && !audioBase64Wav.isNullOrEmpty()) {
            val nativeResult = tryNativeAudioCall(audioBase64Wav, voiceName, apiKey, startTime, onPartialTextReady)
            if (nativeResult != null) {
                return@withContext Result.success(nativeResult)
            }
            // If native audio encountered high-demand or error, fall through to Path 2 immediately
            nativeAudioSupported = false
        }

        // -----------------------------------------------------------------------------------------
        // PATH 2: Fast Flash Model with Zero-Lag Resilient High-Demand Failover Cascade
        // -----------------------------------------------------------------------------------------
        val candidateModels = ArrayList<String>().apply {
            cachedWorkingModel?.let { add(it) }
            FLASH_MODELS.forEach { if (!contains(it)) add(it) }
        }

        val requestJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put(
                            "text",
                            "तपाईं एक मित्रवत, धाराप्रवाह र द्रुत नेपाली भ्वाइस एआई हुनुहुन्छ। " +
                                    "प्रयोगकर्ताको कुरा सुनेर १ वा २ स्पष्ट, पूर्ण नेपाली वाक्यमा छोटो र सीधा उत्तर दिनुहोस्। " +
                                    "वाक्य सधैं पूर्ण विराम (।) ले अन्त्य गर्नुहोस्। " +
                                    "कुनै चिन्ह वा स्टार (*) प्रयोग नगर्नुहोस् किनकि यो आवाजमा बोलिन्छ।"
                        )
                    })
                })
            })

            val partsArray = JSONArray()
            if (!audioBase64Wav.isNullOrEmpty()) {
                partsArray.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "audio/wav")
                        put("data", audioBase64Wav)
                    })
                })
                partsArray.put(JSONObject().apply {
                    put("text", "सुन्नुहोस् र छोटो, पूर्ण नेपाली वाक्यमा उत्तर दिनुहोस्।")
                })
            } else if (!textPrompt.isNullOrEmpty()) {
                partsArray.put(JSONObject().apply {
                    put("text", textPrompt)
                })
            } else {
                partsArray.put(JSONObject().apply {
                    put("text", "नमस्ते!")
                })
            }

            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                })
            })

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 160)
            })
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        var lastErrorMsg = "कुनै प्रतिक्रिया प्राप्त भएन"

        for (modelName in candidateModels) {
            try {
                val targetUrl = "$BASE_URL/$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(targetUrl)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBodyString = response.body?.string()

                if (!response.isSuccessful || responseBodyString.isNullOrEmpty()) {
                    val errorMsg = try {
                        val errJson = JSONObject(responseBodyString ?: "{}")
                        errJson.optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}: ${response.message}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }

                    Log.w(TAG, "Model $modelName returned error ($errorMsg), trying next available model in cascade...")
                    lastErrorMsg = errorMsg
                    continue
                }

                val rootJson = JSONObject(responseBodyString)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    continue
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")

                val textBuilder = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        if (part.has("text")) {
                            val text = part.optString("text")
                            if (text.isNotBlank()) {
                                if (textBuilder.isNotEmpty()) textBuilder.append(" ")
                                textBuilder.append(text.trim())
                            }
                        }
                    }
                }

                val nepaliText = textBuilder.toString().ifEmpty {
                    "नमस्ते! म तपाईंलाई कसरी सहयोग गर्न सक्छु?"
                }

                // Remember this working model for rapid subsequent turns
                cachedWorkingModel = modelName

                // Instantly update UI with text response
                onPartialTextReady?.invoke(nepaliText)

                // Synthesize voice with automatic model failover
                val voiceAudio = synthesizeGeminiVoice(nepaliText, voiceName, apiKey)
                val latencyMs = System.currentTimeMillis() - startTime

                return@withContext Result.success(
                    GeminiVoiceResult(
                        audioBytes = voiceAudio?.first,
                        mimeType = voiceAudio?.second ?: "audio/pcm;rate=24000",
                        textResponse = nepaliText,
                        latencyMs = latencyMs
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Exception calling model $modelName: ${e.message}, failing over to next model...")
                lastErrorMsg = e.message ?: "Network error"
            }
        }

        // If all fallback models were busy or failed
        Result.failure(Exception(lastErrorMsg))
    }

    /**
     * 1-Step Native Multimodal Audio Conversation.
     * Speech-in -> Audio + Text out in a single turn.
     */
    private fun tryNativeAudioCall(
        audioBase64Wav: String,
        voiceName: String,
        apiKey: String,
        startTime: Long,
        onPartialTextReady: ((String) -> Unit)?
    ): GeminiVoiceResult? {
        return try {
            val requestJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put(
                                "text",
                                "You are a native Nepali conversational voice AI assistant. " +
                                        "Reply in 1 or 2 complete, natural, and friendly sentences in conversational Nepali. " +
                                        "Always complete every sentence with proper Nepali intonation and full stop (।)."
                            )
                        })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/wav")
                                    put("data", audioBase64Wav)
                                })
                            })
                            put(JSONObject().apply {
                                put("text", "Listen and respond directly in spoken Nepali.")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.6)
                    put("maxOutputTokens", 180)
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                        put("TEXT")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", voiceName)
                            })
                        })
                    })
                })
            }

            val requestBody = requestJson.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val url = "$BASE_URL/$NATIVE_AUDIO_MODEL:generateContent?key=$apiKey"
            val req = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val res = client.newCall(req).execute()
            val body = res.body?.string()

            if (res.isSuccessful && !body.isNullOrEmpty()) {
                val rootJson = JSONObject(body)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        var audioBytes: ByteArray? = null
                        var mimeType: String? = null
                        var textResponse: String? = null

                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inline = part.getJSONObject("inlineData")
                                mimeType = inline.optString("mimeType", "audio/pcm;rate=24000")
                                val data = inline.optString("data")
                                if (data.isNotEmpty()) {
                                    audioBytes = Base64.decode(data, Base64.DEFAULT)
                                }
                            } else if (part.has("text")) {
                                val t = part.optString("text").trim()
                                if (t.isNotEmpty()) {
                                    textResponse = t
                                    onPartialTextReady?.invoke(t)
                                }
                            }
                        }

                        if (audioBytes != null || textResponse != null) {
                            val latencyMs = System.currentTimeMillis() - startTime
                            Log.d(TAG, "Native audio roundtrip completed in ${latencyMs}ms")
                            return GeminiVoiceResult(
                                audioBytes = audioBytes,
                                mimeType = mimeType ?: "audio/pcm;rate=24000",
                                textResponse = textResponse ?: "तपाईंको जवाफ तयार छ।",
                                latencyMs = latencyMs
                            )
                        }
                    }
                }
            } else {
                Log.w(TAG, "Native audio model returned HTTP ${res.code}, proceeding to resilient flash cascade")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Native audio request failed: ${e.message}")
            null
        }
    }

    suspend fun synthesizeGeminiVoicePublic(
        text: String,
        voiceName: String,
        customApiKey: String? = null
    ): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(customApiKey) ?: return@withContext null
        synthesizeGeminiVoice(text, voiceName, apiKey)
    }

    /**
     * Synthesizes audio using real Gemini voice with automatic model failover.
     */
    private fun synthesizeGeminiVoice(
        text: String,
        voiceName: String,
        apiKey: String
    ): Pair<ByteArray, String>? {
        val ttsCandidates = ArrayList<String>().apply {
            cachedWorkingTtsModel?.let { add(it) }
            TTS_MODELS.forEach { if (!contains(it)) add(it) }
        }

        for (modelName in ttsCandidates) {
            try {
                val ttsRequestJson = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
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
                }

                val ttsRequestBody = ttsRequestJson.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

                val ttsUrl = "$BASE_URL/$modelName:generateContent?key=$apiKey"
                val ttsRequest = Request.Builder()
                    .url(ttsUrl)
                    .post(ttsRequestBody)
                    .build()

                val ttsResponse = client.newCall(ttsRequest).execute()
                val ttsBodyString = ttsResponse.body?.string()

                if (ttsResponse.isSuccessful && !ttsBodyString.isNullOrEmpty()) {
                    val rootJson = JSONObject(ttsBodyString)
                    val candidates = rootJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.has("inlineData")) {
                                    val inlineData = part.getJSONObject("inlineData")
                                    val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                                    val base64 = inlineData.optString("data")
                                    if (base64.isNotEmpty()) {
                                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                                        cachedWorkingTtsModel = modelName
                                        return Pair(bytes, mimeType)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "TTS model $modelName returned HTTP ${ttsResponse.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TTS failed on $modelName: ${e.message}")
            }
        }
        return null
    }
}
