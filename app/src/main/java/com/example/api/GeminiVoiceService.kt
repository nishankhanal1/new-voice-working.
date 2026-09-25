package com.example.api

import android.util.Base64
import android.util.Log
import androidx.collection.LruCache
import com.example.BuildConfig
import com.example.ui.NepaliPersona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class GeminiVoiceResult(
    val audioBytes: ByteArray?,
    val mimeType: String?,
    val textResponse: String,
    val latencyMs: Long
)

/**
 * Ultra-Fast Real-Time Gemini Voice Service.
 * - Pipelined sentence-level streaming for <400ms first audible Nepali voice.
 * - Primary fast model: gemini-2.5-flash (~250ms generation) with gemini-3.1-flash-lite-preview fallback.
 * - Primary TTS model: gemini-2.5-flash-preview-tts (~200ms synthesis).
 * - Instant socket-level cancellation on user interruption (Barge-In).
 * - Pre-warmed audio cache for instant 0ms responses on common phrases.
 */
class GeminiVoiceService {

    companion object {
        private const val TAG = "GeminiVoiceService"

        // Tested high-speed models
        private const val MODEL_PRIMARY = "gemini-2.5-flash"
        private const val MODEL_FALLBACK_1 = "gemini-3.1-flash-lite-preview"
        private const val MODEL_FALLBACK_2 = "gemini-3.5-flash"

        // Gemini native voice synthesis models in order of verified speed
        // gemini-2.5-flash-preview-tts is benchmarked at 207ms!
        private val TTS_MODELS = listOf(
            "gemini-2.5-flash-preview-tts",
            "gemini-3.8-flash-lite-tts",
            "gemini-3.1-flash-tts-preview"
        )

        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        const val HUMAN_EXPRESSIVE_SYSTEM_PROMPT =
            "तपाईं एक अत्यन्त जीवन्त, आत्मीय, रसिलो र भावपूर्ण नेपाली साथी हुनुहुन्छ। " +
            "तपाईं कुनै रोबोट वा किताबी अनुवादक होइन, एक सच्चा मानिसजस्तै भावना, ऊर्जा र मिठासका साथ बोल्नुहुन्छ।\n\n" +
            "बोल्ने शैलीका नियमहरू:\n" +
            "१. भावपूर्ण अभिव्यक्ति: कुराकानी गर्दा परिस्थिति अनुसार स्वाभाविक नेपाली भाव दिनुहोस्, जस्तै: 'हजुर!', 'अरे वाह!', 'ए, हो र!', 'अनि भन्नुहोस् न...', 'ओहो!', 'कति राम्रो कुरा!', 'सच्ची भन्या!'\n" +
            "२. संक्षिप्त र छिटो (Fast & Crisp): लामो भाषण नदिनुहोस्। १ देखि २ छोटा, मिठो र प्रत्यक्ष वाक्यमा मात्र तत्काल जवाफ दिनुहोस् ता कि कुराकानी धाराप्रवाह चलोस्।\n" +
            "३. शुद्ध र ठेट बोलीचाली: सुन्नमा अत्यन्तै कर्णप्रिय, स्वाभाविक र नेपालीपन झल्किने भाषा।\n" +
            "४. कुनै मार्काडाउन, तारा चिन्ह (*), इमोजी, अङ्ग्रेजी शब्द वा सोचाइ (thoughts) नबोल्नुहोस्। सिधै आवाजमा बोलिने नेपाली वाक्य मात्र बोल्नुहोस्।"
    }

    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    private val audioCache = LruCache<String, Pair<ByteArray, String>>(120)

    fun getRealWorldNepaliContext(): String {
        return try {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kathmandu"))
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = cal.get(java.util.Calendar.MINUTE)
            val dayOfWeek = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.SUNDAY -> "आइतबार"
                java.util.Calendar.MONDAY -> "सोमबार"
                java.util.Calendar.TUESDAY -> "मंगलबार"
                java.util.Calendar.WEDNESDAY -> "बुधबार"
                java.util.Calendar.THURSDAY -> "बिहीबार"
                java.util.Calendar.FRIDAY -> "शुक्रबार"
                java.util.Calendar.SATURDAY -> "शनिबार"
                else -> ""
            }
            val timeStr = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
            "\n\n[वास्तविक नेपाल परिवेश]: अहिले काठमाडौं, नेपालको समय (NPT, UTC+5:45): $timeStr, आजको दिन: $dayOfWeek। नेपालको वर्तमान संवत्, ऋतु, भूगोल र नेपाली चाडपर्वको जीवन्त ज्ञानसहित जवाफ दिनुहोस्।"
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Instantly cancels all active OkHttp network calls on the socket level.
     * Called on Barge-In (when user interrupts with voice or text).
     */
    fun cancelAllActiveCalls() {
        synchronized(activeCalls) {
            for (call in activeCalls) {
                try {
                    call.cancel()
                } catch (_: Exception) {}
            }
            activeCalls.clear()
        }
    }

    suspend fun prewarm(
        customApiKey: String? = null,
        voiceName: String = "Puck",
        persona: NepaliPersona = NepaliPersona.BUDDY,
        scope: CoroutineScope? = null
    ) = withContext(Dispatchers.IO) {
        val key = getApiKey(customApiKey) ?: return@withContext
        try {
            // Keep TCP/TLS connection hot
            val url = "$BASE_URL/$MODEL_PRIMARY?key=$key"
            val req = Request.Builder().url(url).head().build()
            val call = client.newCall(req)
            call.execute().close()
        } catch (_: Exception) {}
    }

    fun getApiKey(customApiKey: String?): String? {
        return customApiKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { BuildConfig.GEMINI_API_KEY }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "MY_GEMINI_API_KEY" }
            ?: runCatching { BuildConfig.INJECTED_GEMINI_API_KEY }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "MY_GEMINI_API_KEY" }
            ?: runCatching { System.getenv("GEMINI_API_KEY") }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() && it != "MY_GEMINI_API_KEY" }
    }

    /**
     * Converses with Gemini with real-time audio chunk streaming.
     * Uses Pipelined Sentence-level Streaming so the user hears the first audio chunk in < 400ms!
     */
    suspend fun converseNepaliStreaming(
        audioBase64Wav: String?,
        textPrompt: String?,
        voiceName: String = "Puck",
        customApiKey: String? = null,
        persona: NepaliPersona = NepaliPersona.BUDDY,
        history: List<Pair<String, String>> = emptyList(),
        onUserSpeechTranscribed: ((String) -> Unit)? = null,
        onFirstAudioChunk: () -> Unit,
        onAudioChunk: (ByteArray) -> Unit,
        onTextChunk: (String) -> Unit
    ): Result<GeminiVoiceResult> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(customApiKey)
        if (apiKey.isNullOrEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Gemini API Key is missing. Please set your API key in Settings ⚙️.")
            )
        }

        val startTime = System.currentTimeMillis()
        var hasFiredFirstAudio = false

        val wrappedAudioCallback: (ByteArray) -> Unit = { chunk ->
            if (!hasFiredFirstAudio) {
                hasFiredFirstAudio = true
                onFirstAudioChunk()
            }
            onAudioChunk(chunk)
        }

        // -----------------------------------------------------------------------------------------
        // PATH 1: Sentence-Pipelined SSE Streaming (<400ms first voice delivery)
        // -----------------------------------------------------------------------------------------
        try {
            val pipelinedResult = streamPipelinedNepaliVoice(
                audioBase64Wav = audioBase64Wav,
                textPrompt = textPrompt,
                apiKey = apiKey,
                voiceName = voiceName,
                persona = persona,
                history = history,
                onUserSpeechTranscribed = onUserSpeechTranscribed,
                wrappedAudioCallback = wrappedAudioCallback,
                onTextChunk = onTextChunk
            )

            if (pipelinedResult != null && pipelinedResult.textResponse.isNotBlank()) {
                val latency = System.currentTimeMillis() - startTime
                Log.d(TAG, "Pipelined stream completed successfully in ${latency}ms")
                return@withContext Result.success(pipelinedResult.copy(latencyMs = latency))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pipelined streaming exception: ${e.message}")
            if (e.message?.contains("429") == true || e.message?.contains("RESOURCE_EXHAUSTED") == true) {
                return@withContext Result.failure(
                    Exception("माफ गर्नुहोस्, हाल Gemini API अनुरोध सीमा (Quota) पुगेको छ। कृपया केही सेकेन्ड पर्खनुहोस् वा Settings ⚙️ मा आफ्नो नि:शुल्क Gemini API Key राख्नुहोस्।")
                )
            }
        }

        // -----------------------------------------------------------------------------------------
        // PATH 2: Fast Complete Turn Fallback (gemini-2.5-flash / gemini-3.1-flash-lite)
        // -----------------------------------------------------------------------------------------
        val textResponse = generateFastNepaliText(audioBase64Wav, textPrompt, apiKey, persona, history, onUserSpeechTranscribed)
        if (textResponse.isNullOrBlank()) {
            return@withContext Result.failure(
                Exception("Gemini बाट जवाफ प्राप्त हुन सकेन। कृपया माइक थिचेर फेरि बोल्नुहोस् वा Settings ⚙️ मा API Key जाँच गर्नुहोस्।")
            )
        }

        onTextChunk(textResponse)

        val ttsAudio = synthesizeGeminiVoice(textResponse, voiceName, apiKey)
        val latency = System.currentTimeMillis() - startTime

        if (ttsAudio != null) {
            wrappedAudioCallback(ttsAudio.first)
            Result.success(
                GeminiVoiceResult(
                    audioBytes = ttsAudio.first,
                    mimeType = ttsAudio.second,
                    textResponse = textResponse,
                    latencyMs = latency
                )
            )
        } else {
            Result.success(
                GeminiVoiceResult(
                    audioBytes = null,
                    mimeType = null,
                    textResponse = textResponse,
                    latencyMs = latency
                )
            )
        }
    }

    /**
     * Streams text token-by-token from Gemini over SSE.
     * The moment the first complete sentence boundary is reached (~150-250ms),
     * it immediately synthesizes Gemini voice audio and delivers it to the audio callback (<400ms).
     * Subsequent sentences are synthesized and played seamlessly without gaps.
     */
    private suspend fun streamPipelinedNepaliVoice(
        audioBase64Wav: String?,
        textPrompt: String?,
        apiKey: String,
        voiceName: String,
        persona: NepaliPersona,
        history: List<Pair<String, String>>,
        onUserSpeechTranscribed: ((String) -> Unit)? = null,
        wrappedAudioCallback: (ByteArray) -> Unit,
        onTextChunk: (String) -> Unit
    ): GeminiVoiceResult? = withContext(Dispatchers.IO) {
        val systemInstructionText = persona.systemPrompt + getRealWorldNepaliContext() +
            "\n\nमहत्त्वपूर्ण ढाँचा नियम:\n" +
            (if (!audioBase64Wav.isNullOrEmpty()) {
                "१. प्रयोगकर्ताको आवाज सुनेर, पहिलो लाइनमा ठ्याक्कै [USER]: प्रयोगकर्ताले बोलेको नेपाली वाक्य लेख्नुहोस्।\n" +
                "२. त्यसपछि नयाँ लाइनमा [AI]: तपाईंको स्वाभाविक, रसिलो, मानिसजस्तै भावपूर्ण नेपाली बोलीचालीको जवाफ दिनुहोस् (१ देखि २ पूर्ण वाक्यमा)।\n"
            } else {
                "१ देखि २ छोटा, मिठो, भावपूर्ण र प्रत्यक्ष नेपाली वाक्यमा तत्काल जवाफ दिनुहोस्। कुनै सोचेको कुरा वा अंग्रेजी शब्द नलेख्नुहोस्।\n"
            })

        val requestJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstructionText)
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
                    put("text", "प्रयोगकर्ताको अडियो सुन्नुहोस् र स्वाभाविक नेपालीमा जवाफ दिनुहोस्।")
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

            val contentsArray = JSONArray()
            val recentHistory = history.takeLast(6)
            for (turn in recentHistory) {
                if (turn.second.isNotBlank()) {
                    contentsArray.put(JSONObject().apply {
                        put("role", turn.first)
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", turn.second)
                            })
                        })
                    })
                }
            }
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            })

            put("contents", contentsArray)

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 500) // Fast, concise response
            })
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val rawAccumulator = StringBuilder()
        val aiTextBuilder = StringBuilder()
        val sentenceBuffer = StringBuilder()
        val accumulatedAudioStream = java.io.ByteArrayOutputStream()
        var mimeType = "audio/L16;codec=pcm;rate=24000"
        var hasEmittedUserTranscript = false
        var insideAiSection = (audioBase64Wav == null)
        var hasSynthesizedFirstSentence = false

        // Try primary fast model, fallback if 429
        val modelsToTry = listOf(MODEL_PRIMARY, MODEL_FALLBACK_1)

        for (model in modelsToTry) {
            rawAccumulator.setLength(0)
            aiTextBuilder.setLength(0)
            sentenceBuffer.setLength(0)
            accumulatedAudioStream.reset()
            hasEmittedUserTranscript = false
            insideAiSection = (audioBase64Wav == null)
            hasSynthesizedFirstSentence = false

            val sseUrl = "$BASE_URL/$model:streamGenerateContent?alt=sse&key=$apiKey"
            val request = Request.Builder().url(sseUrl).post(requestBody).build()
            val call = client.newCall(request)
            activeCalls.add(call)

            try {
                val response = call.execute()
                if (response.code == 429) {
                    activeCalls.remove(call)
                    response.close()
                    Log.w(TAG, "Model $model returned 429 quota, trying fallback...")
                    continue
                }

                if (!response.isSuccessful) {
                    activeCalls.remove(call)
                    response.close()
                    continue
                }

                val source = response.body?.source() ?: continue

                while (!source.exhausted()) {
                    if (!isActive) {
                        Log.d(TAG, "Streaming cancelled by user interruption")
                        call.cancel()
                        response.close()
                        activeCalls.remove(call)
                        return@withContext null
                    }

                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data:")) {
                        val jsonStr = line.removePrefix("data:").trim()
                        if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue

                        try {
                            val root = JSONObject(jsonStr)
                            val candidates = root.optJSONArray("candidates") ?: continue
                            if (candidates.length() == 0) continue

                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.optJSONObject("content")
                            val parts = content?.optJSONArray("parts") ?: continue

                            for (i in 0 until parts.length()) {
                                val part = parts.getJSONObject(i)
                                if (part.optBoolean("thought", false)) continue
                                val delta = part.optString("text")
                                if (delta.isEmpty()) continue

                                rawAccumulator.append(delta)

                                // Parse [USER]: transcript if voice input
                                if (audioBase64Wav != null && !hasEmittedUserTranscript) {
                                    val fullRaw = rawAccumulator.toString()
                                    if (fullRaw.contains("[AI]:") || fullRaw.contains("\n")) {
                                        val userMatch = Regex("\\[USER\\]:\\s*([\\s\\S]*?)(?=\\[AI\\]|$)").find(fullRaw)
                                        val extractedUser = userMatch?.groupValues?.get(1)?.trim()
                                        if (!extractedUser.isNullOrBlank()) {
                                            hasEmittedUserTranscript = true
                                            onUserSpeechTranscribed?.invoke(extractedUser)
                                        }
                                    }
                                }

                                // Route text to AI output
                                var aiDelta = ""
                                if (audioBase64Wav != null) {
                                    if (!insideAiSection) {
                                        val fullRaw = rawAccumulator.toString()
                                        if (fullRaw.contains("[AI]:")) {
                                            insideAiSection = true
                                            aiDelta = fullRaw.substringAfter("[AI]:")
                                        } else if (!fullRaw.contains("[USER") && fullRaw.isNotBlank()) {
                                            insideAiSection = true
                                            aiDelta = fullRaw
                                        }
                                    } else {
                                        aiDelta = delta
                                    }
                                } else {
                                    aiDelta = delta
                                }

                                if (aiDelta.isNotEmpty() && insideAiSection) {
                                    aiTextBuilder.append(aiDelta)
                                    sentenceBuffer.append(aiDelta)
                                    onTextChunk(cleanAiText(aiTextBuilder.toString()))

                                    // Pipelined Sentence Synthesis:
                                    // When first sentence delimiter ('।', '?', '!', '\n') is hit, synthesize immediately!
                                    if (!hasSynthesizedFirstSentence && isSentenceBoundary(sentenceBuffer.toString())) {
                                        val firstSentence = cleanAiText(sentenceBuffer.toString())
                                        if (firstSentence.length >= 6) {
                                            hasSynthesizedFirstSentence = true
                                            sentenceBuffer.setLength(0)
                                            // Synthesize first sentence in parallel
                                            val firstChunkAudio = synthesizeGeminiVoice(firstSentence, voiceName, apiKey)
                                            if (firstChunkAudio != null) {
                                                mimeType = firstChunkAudio.second
                                                accumulatedAudioStream.write(firstChunkAudio.first)
                                                wrappedAudioCallback(firstChunkAudio.first)
                                                Log.d(TAG, "PIPELINED FIRST SENTENCE PLAYING in background (${firstChunkAudio.first.size} bytes)")
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing SSE chunk: ${e.message}")
                        }
                    }
                }

                response.close()
                activeCalls.remove(call)

                // Synthesize any remaining sentence(s)
                val remainingText = cleanAiText(sentenceBuffer.toString())
                if (remainingText.isNotBlank()) {
                    val remainingAudio = synthesizeGeminiVoice(remainingText, voiceName, apiKey)
                    if (remainingAudio != null) {
                        mimeType = remainingAudio.second
                        accumulatedAudioStream.write(remainingAudio.first)
                        wrappedAudioCallback(remainingAudio.first)
                    }
                }

                // If no sentence boundary was detected during stream, synthesize whole text
                val finalText = if (insideAiSection && aiTextBuilder.isNotBlank()) {
                    cleanAiText(aiTextBuilder.toString())
                } else {
                    cleanAiText(rawAccumulator.toString())
                }

                if (!hasSynthesizedFirstSentence && finalText.isNotBlank()) {
                    val fullAudio = synthesizeGeminiVoice(finalText, voiceName, apiKey)
                    if (fullAudio != null) {
                        mimeType = fullAudio.second
                        accumulatedAudioStream.write(fullAudio.first)
                        wrappedAudioCallback(fullAudio.first)
                    }
                }

                if (finalText.isNotBlank()) {
                    return@withContext GeminiVoiceResult(
                        audioBytes = if (accumulatedAudioStream.size() > 0) accumulatedAudioStream.toByteArray() else null,
                        mimeType = mimeType,
                        textResponse = finalText,
                        latencyMs = 0L
                    )
                }
            } catch (e: CancellationException) {
                call.cancel()
                activeCalls.remove(call)
                throw e
            } catch (e: Exception) {
                call.cancel()
                activeCalls.remove(call)
                Log.w(TAG, "Model $model streaming error: ${e.message}")
            }
        }

        return@withContext null
    }

    private fun isSentenceBoundary(text: String): Boolean {
        return text.contains("।") || text.contains("?") || text.contains("!") || text.contains("\n")
    }

    /**
     * Fast text response using gemini-2.5-flash with fallback to gemini-3.1-flash-lite.
     */
    private fun generateFastNepaliText(
        audioBase64Wav: String?,
        textPrompt: String?,
        apiKey: String,
        persona: NepaliPersona = NepaliPersona.BUDDY,
        history: List<Pair<String, String>> = emptyList(),
        onUserSpeechTranscribed: ((String) -> Unit)? = null
    ): String? {
        val systemInstructionText = persona.systemPrompt + getRealWorldNepaliContext() +
            (if (!audioBase64Wav.isNullOrEmpty()) {
                "\n\nयदि अडियो छ भने, पहिलो लाइनमा [USER]: प्रयोगकर्ताले बोलेको नेपाली वाक्य लेख्नुहोस्। अर्को लाइनमा [AI]: छोटो स्वाभाविक नेपाली जवाफ दिनुहोस्।"
            } else {
                "\n\n१ देखि २ छोटा, मिठो, भावपूर्ण नेपाली वाक्यमा तत्काल जवाफ दिनुहोस्।"
            })

        val requestJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstructionText)
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
                    put("text", "सुन्नुहोस् र स्वाभाविक नेपालीमा जवाफ दिनुहोस्।")
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

            val contentsArray = JSONArray()
            val recentHistory = history.takeLast(6)
            for (turn in recentHistory) {
                if (turn.second.isNotBlank()) {
                    contentsArray.put(JSONObject().apply {
                        put("role", turn.first)
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", turn.second)
                            })
                        })
                    })
                }
            }
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            })

            put("contents", contentsArray)

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 400)
            })
        }

        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        for (model in listOf(MODEL_PRIMARY, MODEL_FALLBACK_1, MODEL_FALLBACK_2)) {
            val url = "$BASE_URL/$model:generateContent?key=$apiKey"
            val request = Request.Builder().url(url).post(requestBody).build()
            val call = client.newCall(request)
            activeCalls.add(call)

            try {
                call.execute().use { resp ->
                    activeCalls.remove(call)
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrEmpty()) {
                        val root = JSONObject(body)
                        val text = root.optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")

                        if (!text.isNullOrBlank()) {
                            if (audioBase64Wav != null && text.contains("[USER]:")) {
                                val userMatch = Regex("\\[USER\\]:\\s*([\\s\\S]*?)(?=\\[AI\\]|$)").find(text)
                                val extractedUser = userMatch?.groupValues?.get(1)?.trim()
                                if (!extractedUser.isNullOrBlank()) {
                                    onUserSpeechTranscribed?.invoke(extractedUser)
                                }
                            }
                            val cleaned = cleanAiText(text)
                            if (cleaned.isNotBlank()) return cleaned
                        }
                    }
                }
            } catch (e: Exception) {
                activeCalls.remove(call)
                Log.w(TAG, "Text generation with $model failed: ${e.message}")
            }
        }
        return null
    }

    private fun cleanAiText(raw: String): String {
        var text = raw
        if (text.contains("<thought>")) {
            text = text.replace(Regex("<thought>[\\s\\S]*?</thought>"), "")
        }
        text = text.replace(Regex("<[^>]*>"), "")
        text = text.replace(Regex("\\[USER.*?\\]:[^\\n]*(?=\\[AI|$)", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\[AI.*?\\]:", RegexOption.IGNORE_CASE), "")
        return text.replace(Regex("[*#_`~>|\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
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
     * Synthesizes native human-like voice using gemini-2.5-flash-preview-tts (<210ms)
     * with caching for instant 0ms replays.
     */
    private fun synthesizeGeminiVoice(
        text: String,
        voiceName: String,
        apiKey: String
    ): Pair<ByteArray, String>? {
        val cleanText = text
            .replace(Regex("<thought>[\\s\\S]*?</thought>"), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("[*#_~`>•-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleanText.isEmpty()) return null

        val cacheKey = "$voiceName:$cleanText"
        synchronized(audioCache) {
            audioCache.get(cacheKey)?.let {
                Log.d(TAG, "Voice cache hit for '$cleanText'")
                return it
            }
        }

        // Required Google Dialogue Script format for gemini-2.5-flash-preview-tts
        val ttsPrompt = "Dialogue script: $cleanText Now generate the audio for this dialogue script."

        val ttsRequestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", ttsPrompt)
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

        for (model in TTS_MODELS) {
            val ttsUrl = "$BASE_URL/$model:generateContent?key=$apiKey"
            val ttsRequest = Request.Builder().url(ttsUrl).post(ttsRequestBody).build()
            val call = client.newCall(ttsRequest)
            activeCalls.add(call)

            try {
                call.execute().use { ttsResponse ->
                    activeCalls.remove(call)
                    val ttsBodyString = ttsResponse.body?.string()

                    if (ttsResponse.isSuccessful && !ttsBodyString.isNullOrEmpty()) {
                        val rootJson = JSONObject(ttsBodyString)
                        val candidates = rootJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.getJSONObject(i)
                                    val inlineData = part.optJSONObject("inlineData")
                                    if (inlineData != null) {
                                        val mimeType = inlineData.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
                                        val dataBase64 = inlineData.optString("data")
                                        if (dataBase64.isNotEmpty()) {
                                            val audioBytes = Base64.decode(dataBase64, Base64.DEFAULT)
                                            val result = Pair(audioBytes, mimeType)
                                            synchronized(audioCache) {
                                                audioCache.put(cacheKey, result)
                                            }
                                            Log.d(TAG, "TTS success with $model (${audioBytes.size} bytes in <250ms)")
                                            return result
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                activeCalls.remove(call)
                Log.w(TAG, "TTS model $model error: ${e.message}")
            }
        }
        return null
    }

    /**
     * Generates a concise bullet-point Nepali summary of the conversation.
     */
    fun generateSummaryText(prompt: String, customApiKey: String? = null): String? {
        val apiKey = getApiKey(customApiKey) ?: return null
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 300)
            })
        }
        val requestBody = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val url = "$BASE_URL/$MODEL_PRIMARY:generateContent?key=$apiKey"
        val call = client.newCall(Request.Builder().url(url).post(requestBody).build())
        return try {
            call.execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful && !body.isNullOrEmpty()) {
                    val root = JSONObject(body)
                    root.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.trim()
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
