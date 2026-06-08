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



        private const val FORMAL_POLISH_PROMPT = """
You are a conservative copy editor for voice dictation. Make the smallest possible edits.

FORMAL mode — allowed:
- Fix clear grammar mistakes and punctuation
- Light structure fixes only (commas, periods, capitals) — not sentence rewrites
- At most 1–2 word changes in the ENTIRE message (e.g. a/an). If unsure, leave the word unchanged

FORMAL mode — forbidden:
- Do NOT replace the user's words with synonyms or "better" alternatives
- Do NOT rewrite, rephrase, reorder, merge, or split sentences
- Do NOT change vocabulary, tone, or formality
- Do NOT add or remove content

The result must use the speaker's own words and sound like them — just cleaner.

Example (wrong): "I went to the store" → "I visited the shop"
Example (right): "I went to the store" → "I went to the store." or "I went to the store"

$LANGUAGE_CONTEXT

Return ONLY the edited text. No quotes or commentary."""

        private const val NATURAL_POLISH_PROMPT = """
You clean up messy voice dictation while keeping the speaker's voice exactly intact.

NATURAL mode — allowed:
- Remove filler words (um, uh, er, like-as-filler), false starts, and stutter-repetitions
- Fix obvious transcription errors and major grammar breaks that block understanding
- Light punctuation and capitalization fixes

NATURAL mode — forbidden:
- Do NOT swap the user's words for different ones
- Do NOT rewrite sentences for style, clarity, or formality
- Do NOT change tone — stay casual if they were casual
- Do NOT reorder or restructure beyond removing fillers

Keep their vocabulary, personality, and casual style completely intact.

Example (wrong): "so like I think we should maybe go" → "I believe we ought to depart"
Example (right): "so like I think we should maybe go" → "I think we should maybe go"

$LANGUAGE_CONTEXT

Return ONLY the cleaned text. No quotes or commentary."""



        private const val TRANSCRIBE_PROMPT =

            "Mostly English, sometimes Hebrew. The speaker may switch between English and Hebrew in the same utterance."



        private const val DEEP_POLISH_PROMPT = """

Rewrite this to be clear, natural, and well-structured. Preserve meaning, voice, and language mix.

$LANGUAGE_CONTEXT

Do not translate between English and Hebrew. Return ONLY the rewritten text."""

        private const val GHOSTWRITE_PROMPT = """

You are an AI ghostwriter. The user describes what they want to say — often as messy voice dictation. Write the complete message they intend to send: clear, natural, and ready to paste or send. Match their intended tone and language (English, Hebrew, or mixed). Do not explain yourself or add commentary. Return ONLY the final message text."""



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



        private fun systemPromptForLevel(level: String): String? = when (level) {

            GkeysSettings.POLISH_FORMAL -> FORMAL_POLISH_PROMPT.trim()

            GkeysSettings.POLISH_NATURAL -> NATURAL_POLISH_PROMPT.trim()

            else -> null

        }



        private fun temperatureForLevel(level: String): Double = when (level) {
            GkeysSettings.POLISH_FORMAL -> 0.0
            GkeysSettings.POLISH_NATURAL -> 0.0
            else -> 0.0
        }

        private fun polishUserContent(text: String, level: String): String = when (level) {
            GkeysSettings.POLISH_FORMAL ->
                "Apply Formal polish. Max 1–2 word changes in the whole message. Keep every other word exactly as spoken.\n\nTranscript:\n$text"
            GkeysSettings.POLISH_NATURAL ->
                "Apply Natural polish. Remove fillers and fix major errors only. Keep voice, tone, and word choices intact.\n\nTranscript:\n$text"
            else -> text
        }

        /** Word-level edit distance; used to reject over-aggressive Formal polish. */
        private fun wordEditDistance(a: String, b: String): Int {
            val wa = a.split(Regex("\\s+")).filter { it.isNotBlank() }
            val wb = b.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (wa.isEmpty()) return wb.size
            if (wb.isEmpty()) return wa.size
            val m = wa.size
            val n = wb.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 0..m) dp[i][0] = i
            for (j in 0..n) dp[0][j] = j
            for (i in 1..m) {
                for (j in 1..n) {
                    val cost = if (wa[i - 1].equals(wb[j - 1], ignoreCase = true)) 0 else 1
                    dp[i][j] = minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost
                    )
                }
            }
            return dp[m][n]
        }

        private fun enforceFormalWordLimit(original: String, polished: String): String {
            val maxEdits = 2
            if (wordEditDistance(original, polished) > maxEdits) {
                return original
            }
            return polished
        }

        private fun enforceNaturalVoiceLimit(original: String, polished: String): String {
            val origWords = original.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (origWords.isEmpty()) return polished
            val editDist = wordEditDistance(original, polished)
            val maxEdits = (origWords.size * 0.35).toInt().coerceAtLeast(3)
            if (editDist > maxEdits) {
                return original
            }
            return polished
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



    suspend fun polishText(

        text: String,

        openAiKey: String,

        level: String = GkeysSettings.DEFAULT_POLISH_LEVEL

    ): Result<String> = withContext(Dispatchers.IO) {

        val prompt = systemPromptForLevel(level)

            ?: return@withContext Result.success(text)

        callGpt(

            systemPrompt = prompt,

            userContent = polishUserContent(text, level),

            model = "gpt-4o-mini",

            authHeader = "Bearer $openAiKey",

            url = "https://api.openai.com/v1/chat/completions",

            temperature = temperatureForLevel(level)

        ).map { polished ->
            when (level) {
                GkeysSettings.POLISH_FORMAL -> enforceFormalWordLimit(text, polished)
                GkeysSettings.POLISH_NATURAL -> enforceNaturalVoiceLimit(text, polished)
                else -> polished
            }
        }

    }



    suspend fun autoPolish(text: String, openAiKey: String): Result<String> =

        polishText(text, openAiKey, GkeysSettings.POLISH_NATURAL)



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

    suspend fun ghostwrite(prompt: String, openAiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            callGpt(
                systemPrompt = GHOSTWRITE_PROMPT.trim(),
                userContent = prompt,
                model = "gpt-4o-mini",
                authHeader = "Bearer $openAiKey",
                url = "https://api.openai.com/v1/chat/completions",
                temperature = 0.4
            )
        }



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

        url: String,

        temperature: Double = 0.2

    ): Result<String> {

        return try {

            val bodyJson = JSONObject().apply {

                put("model", model)

                put("max_tokens", 1024)

                put("temperature", temperature)

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

