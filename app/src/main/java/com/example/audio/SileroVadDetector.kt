package com.example.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time Voice Activity Detector.
 * Supports Silero VAD ONNX model with seamless automatic fallback to
 * top-tier multi-feature [AdaptiveVoiceActivityDetector].
 */
class SileroVadDetector(private val context: Context) {

    companion object {
        private const val TAG = "SileroVadDetector"
        const val SAMPLE_RATE = 16000L
        const val WINDOW_SIZE_SAMPLES = 512 // 32ms at 16kHz
    }

    private val vadLock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isUsingOnnx = false

    // Recurrent state tensor for ONNX: shape [2, 1, 128]
    private var state = Array(2) { Array(1) { FloatArray(128) } }

    // Sample buffer for collecting continuous 512-sample windows for ONNX
    private val sampleBuffer = FloatArray(WINDOW_SIZE_SAMPLES)
    private var sampleBufferIndex = 0

    // Top-tier fallback VAD when ONNX asset file is not present
    private val adaptiveVad = AdaptiveVoiceActivityDetector(sampleRate = 16000, frameSizeSamples = 480)

    init {
        initializeModel()
    }

    private fun initializeModel() {
        synchronized(vadLock) {
            try {
                val modelBytes = try {
                    context.assets.open("silero_vad.onnx").use { input ->
                        val buffer = ByteArrayOutputStream()
                        val data = ByteArray(4096)
                        var nRead: Int
                        while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                            buffer.write(data, 0, nRead)
                        }
                        buffer.toByteArray()
                    }
                } catch (fnf: Exception) {
                    null
                }

                if (modelBytes != null && modelBytes.isNotEmpty()) {
                    env = OrtEnvironment.getEnvironment()
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(1)
                        setInterOpNumThreads(1)
                    }
                    session = env?.createSession(modelBytes, sessionOptions)
                    isUsingOnnx = true
                    Log.i(TAG, "Silero VAD ONNX model loaded successfully (${modelBytes.size} bytes)")
                } else {
                    isUsingOnnx = false
                    Log.i(TAG, "Using high-precision AdaptiveVoiceActivityDetector (zero asset dependency)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falling back to AdaptiveVoiceActivityDetector: ${e.message}")
                isUsingOnnx = false
            }
        }
    }

    /**
     * Resets internal states for a fresh turn.
     */
    fun reset() {
        synchronized(vadLock) {
            state = Array(2) { Array(1) { FloatArray(128) } }
            sampleBufferIndex = 0
            adaptiveVad.reset()
        }
    }

    /**
     * Feeds raw 16kHz PCM audio bytes into the detector.
     * Evaluates continuous speech probability score (0.0f - 1.0f).
     */
    fun processPcmBytes(
        pcmBytes: ByteArray,
        offset: Int = 0,
        length: Int = pcmBytes.size,
        onProbability: (Float) -> Unit
    ) {
        if (length < 2) return

        if (!isUsingOnnx) {
            adaptiveVad.processPcmBytes(pcmBytes, offset, length, onProbability)
            return
        }

        synchronized(vadLock) {
            val numShorts = length / 2
            val byteBuffer = ByteBuffer.wrap(pcmBytes, offset, length).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until numShorts) {
                val shortVal = byteBuffer.short
                val floatVal = shortVal.toFloat() / 32768.0f

                sampleBuffer[sampleBufferIndex++] = floatVal

                if (sampleBufferIndex == WINDOW_SIZE_SAMPLES) {
                    sampleBufferIndex = 0
                    val prob = runOnnxInference(sampleBuffer)
                    onProbability(prob)
                }
            }
        }
    }

    private fun runOnnxInference(windowSamples: FloatArray): Float {
        val activeEnv = env ?: return 0f
        val activeSession = session ?: return 0f

        return try {
            val audioTensor = OnnxTensor.createTensor(activeEnv, arrayOf(windowSamples))
            val stateTensor = OnnxTensor.createTensor(activeEnv, state)
            val srTensor = OnnxTensor.createTensor(activeEnv, SAMPLE_RATE)

            val inputs = mapOf(
                "input" to audioTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )

            val results = activeSession.run(inputs)

            @Suppress("UNCHECKED_CAST")
            val outputTensor = results[0].value as Array<FloatArray>
            val speechProbability = outputTensor[0][0].coerceIn(0f, 1f)

            @Suppress("UNCHECKED_CAST")
            val nextState = results[1].value as Array<Array<FloatArray>>
            state = nextState

            results.close()
            audioTensor.close()
            stateTensor.close()
            srTensor.close()

            speechProbability
        } catch (e: Exception) {
            Log.w(TAG, "ONNX inference error: ${e.message}")
            0f
        }
    }

    fun release() {
        synchronized(vadLock) {
            try {
                session?.close()
                session = null
                env?.close()
                env = null
                isUsingOnnx = false
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing session: ${e.message}")
            }
        }
    }
}
