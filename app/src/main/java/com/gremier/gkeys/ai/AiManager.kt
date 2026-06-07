package com.gremier.gkeys.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(audioFile: File, openAiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name,
                        audioFile.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $openAiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                Result.success(json.optString("text", ""))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun autoPolish(text: String, openAiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            callGpt(
                prompt = "Clean up this voice dictation. Fix grammar, punctuation, remove filler words. Preserve meaning exactly. Return ONLY the cleaned text.\n\nText: $text",
                model = "gpt-4o-mini",
                authHeader = "Bearer $openAiKey",
                url = "https://api.openai.com/v1/chat/completions"
            )
        }

    suspend fun polishAndTranslateToHebrew(text: String, openAiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            callGpt(
                prompt = "Clean up this voice dictation and translate to Hebrew. Return ONLY the final Hebrew text.\n\nText: $text",
                model = "gpt-4o-mini",
                authHeader = "Bearer $openAiKey",
                url = "https://api.openai.com/v1/chat/completions"
            )
        }

    suspend fun deepPolish(text: String, anthropicKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("model", "claude-haiku-4-5-20251001")
                    put("max_tokens", 1024)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Rewrite this to be clear, natural, and well-structured. Preserve meaning and voice. Return ONLY the rewritten text.\n\nText: $text")
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", anthropicKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "{}")
                val content = json.optJSONArray("content")?.optJSONObject(0)?.optString("text", "") ?: ""
                Result.success(content.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun callGpt(prompt: String, model: String, authHeader: String, url: String): Result<String> {
        return try {
            val bodyJson = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1024)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }

            val request = Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string() ?: "{}")
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            Result.success(text.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
