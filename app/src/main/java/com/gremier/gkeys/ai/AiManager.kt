package com.gremier.gkeys.ai

import android.content.Context
import com.gremier.gkeys.settings.GkeysSettings
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

    companion object {
        /** Shared language expectations for voice dictation and polish flows. */
        private const val LANGUAGE_CONTEXT = """
Language expectations: The user mostly speaks or types English, sometimes Hebrew, and may switch between English and Hebrew within the same message or even mid-sentence. Preserve each language as given unless the task explicitly says to translate."""

        const val POLISH_SYSTEM_PROMPT = """
You are a text polishing assistant. The user has typed or dictated the following text on a mobile keyboard.
$LANGUAGE_CONTEXT
Rewrite it to be clear, natural, and well-structured while preserving the original meaning, tone, intent, and language mix. Do not translate between English and Hebrew. Do not add extra information. Do not make it formal unless it already is. Keep it concise. Return only the rewritten text, nothing else."""

        private const val TRANSCRIBE_PROMPT =
            "Mostly English, sometimes Hebrew. The speaker may switch between English and Hebrew in the same utterance."

        private const val DEEP_POLISH_PROMPT = """
Rewrite this to be clear, natural, and well-structured. Preserve meaning, voice, and language mix.
$LANGUAGE_CONTEXT
Do not translate between English and Hebrew. Return ONLY the rewritten text."""

        private const val MIN_AUDIO_BYTES = 4096L
        private const val MIN_RECORDING_MS = 700L

        private val HALLUCINATION_PHRASES = listOf(
            "thank you for watching",
            "thanks for watching",
            "please subscribe",
            "like and subscribe",
            "see you next time",
            "wishing you lots of happiness",
            "until the end",
            "subtitles by",
            "amara.org",
            "mbc",
            "copyright"
        )

        fun isLikelyHallucination(text: String): Boolean {
            val lower = text.lowercase().trim()
            if (lower.length < 3) return true
            return HALLUCINATION_PHRASES.any { lower.contains(it) }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        audioFile: File,
        openAiKey: String,
        recordingDurationMs: Long = 0L,
        language: String? = null
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                if (recordingDurationMs in 1 until MIN_RECORDING_MS || audioFile.length() < MIN_AUDIO_BYTES) {
                    return@withContext Result.failure(Exception("Recording too short"))
                }
                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioFile.name,
                        audioFile.asRequestBody("audio/m4a".toMediaType()))
                    .addFormDataPart("model", "gpt-4o-mini-transcribe")
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("prompt", TRANSCRIBE_PROMPT)
                if (!language.isNullOrBlank()) {
                    bodyBuilder.addFormDataPart("language", language)
                }
                val body = bodyBuilder.build()

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $openAiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Transcription failed (${response.code})"))
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val text = json.optString("text", "").trim()
                if (text.isBlank() || isLikelyHallucination(text)) {
                    return@withContext Result.failure(Exception("Nothing heard"))
                }
                Result.success(text)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Typless-style polish with a dedicated system prompt. */
    suspend fun polishText(text: String, openAiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            callGpt(
                systemPrompt = POLISH_SYSTEM_PROMPT.trim(),
                userContent = text,
                model = "gpt-4o-mini",
                authHeader = "Bearer $openAiKey",
                url = "https://api.openai.com/v1/chat/completions"
            )
        }

    suspend fun autoPolish(text: String, openAiKey: String): Result<String> =
        polishText(text, openAiKey)

    suspend fun translateText(
        text: String,
        fromLanguage: String,
        toLanguage: String,
        openAiKey: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val fromName = languageDisplayName(fromLanguage)
        val toName = languageDisplayName(toLanguage)
        callGpt(
            systemPrompt = """
You translate text from $fromName to $toName. The input may be messy voice dictation and may mix languages. Return ONLY the final text in $toName.""".trim(),
            userContent = "Clean up and translate from $fromName to $toName.\n\nText: $text",
            model = "gpt-4o-mini",
            authHeader = "Bearer $openAiKey",
            url = "https://api.openai.com/v1/chat/completions"
        )
    }

    suspend fun polishAndTranslateToHebrew(text: String, openAiKey: String): Result<String> =
        translateText(text, GkeysSettings.LANG_EN, GkeysSettings.LANG_HE, openAiKey)

    private fun languageDisplayName(code: String): String = when (code) {
        GkeysSettings.LANG_HE -> "Hebrew"
        GkeysSettings.LANG_EN -> "English"
        else -> code
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
                            put("content", "$DEEP_POLISH_PROMPT\n\nText: $text")
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
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Deep polish failed (${response.code})"))
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val content = json.optJSONArray("content")?.optJSONObject(0)?.optString("text", "") ?: ""
                Result.success(content.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun callGpt(
        systemPrompt: String,
        userContent: String,
        model: String,
        authHeader: String,
        url: String
    ): Result<String> {
        return try {
            val bodyJson = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1024)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
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
            if (!response.isSuccessful) {
                return Result.failure(Exception("Polish failed (${response.code})"))
            }
            val json = JSONObject(response.body?.string() ?: "{}")
            val text = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
            if (text.isBlank()) {
                return Result.failure(Exception("Empty response from API"))
            }
            Result.success(text.trim())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
