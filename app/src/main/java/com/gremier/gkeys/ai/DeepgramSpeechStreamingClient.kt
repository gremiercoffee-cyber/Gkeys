package com.gremier.gkeys.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Deepgram live speech-to-text over WebSocket (linear16 PCM from [PcmAudioRecorder]).
 */
class DeepgramSpeechStreamingClient(
    private val apiKey: String,
    private val languageCode: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onError: (Throwable) -> Unit
) {
    companion object {
        private const val TAG = "DeepgramSpeechStream"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val pcmRecorder = PcmAudioRecorder()
    private var webSocket: WebSocket? = null
    private var captureScope: CoroutineScope? = null
    @Volatile
    private var connected = false

    fun start(scope: CoroutineScope) {
        stop()
        captureScope = scope
        val language = languageCode.substringBefore('-').ifBlank { "en" }
        val url = HttpUrl.Builder()
            .scheme("wss")
            .host("api.deepgram.com")
            .addPathSegments("v1/listen")
            .addQueryParameter("encoding", "linear16")
            .addQueryParameter("sample_rate", PcmAudioRecorder.SAMPLE_RATE.toString())
            .addQueryParameter("channels", "1")
            .addQueryParameter("language", language)
            .addQueryParameter("model", "nova-2")
            .addQueryParameter("interim_results", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("endpointing", "300")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()

        webSocket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Deepgram WebSocket open")
                    val activeScope = captureScope ?: return
                    // AudioRecord must be created on the main thread on many devices.
                    mainHandler.post {
                        if (captureScope == null) return@post
                        try {
                            pcmRecorder.start(activeScope) { chunk ->
                                try {
                                    webSocket.send(chunk.toByteString())
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to send audio chunk", e)
                                }
                            }
                            connected = true
                            onConnected()
                        } catch (e: Exception) {
                            Log.e(TAG, "Microphone capture failed", e)
                            onError(e)
                            stop()
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val httpDetail = response?.let { "HTTP ${it.code}" }.orEmpty()
                    val message = when {
                        response?.code == 401 -> "Invalid Deepgram API key"
                        response?.code == 402 -> "Deepgram account issue — check billing"
                        httpDetail.isNotBlank() -> "Deepgram connection failed ($httpDetail)"
                        else -> t.message ?: "Deepgram connection failed"
                    }
                    Log.e(TAG, "Deepgram WebSocket failed: $message", t)
                    mainHandler.post { onError(IllegalStateException(message, t)) }
                    stop()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "Deepgram WebSocket closed: $code $reason")
                    connected = false
                }
            }
        )
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (type == "Metadata") {
                Log.d(TAG, "Deepgram metadata received")
                return
            }
            if (type != "Results") return

            val transcript = json.optJSONObject("channel")
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optString("transcript")
                ?.trim()
                .orEmpty()
            if (transcript.isEmpty()) return

            val isFinal = json.optBoolean("is_final", false) ||
                json.optBoolean("speech_final", false)
            mainHandler.post {
                if (isFinal) onFinal(transcript) else onPartial(transcript)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Deepgram message", e)
        }
    }

    fun stop() {
        captureScope = null
        connected = false
        try {
            pcmRecorder.stop()
        } catch (_: Exception) {
        }
        try {
            webSocket?.send("""{"type":"CloseStream"}""")
        } catch (_: Exception) {
        }
        try {
            webSocket?.close(1000, "stop")
        } catch (_: Exception) {
        }
        webSocket = null
    }
}
