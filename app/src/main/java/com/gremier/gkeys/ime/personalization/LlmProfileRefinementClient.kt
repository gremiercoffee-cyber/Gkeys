package com.gremier.gkeys.ime.personalization

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LlmProfileRefinementClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun refine(
        openAiKey: String,
        currentProfile: PersonalLanguageProfile,
        summary: DailyTypingSummary,
    ): Result<PersonalLanguageProfile> = withContext(Dispatchers.IO) {
        if (openAiKey.isBlank()) return@withContext Result.failure(Exception("Missing OpenAI key"))
        try {
            val body = JSONObject().apply {
                put("model", "gpt-4o-mini")
                put("temperature", 0.0)
                put("max_tokens", 1800)
                put("response_format", JSONObject().put("type", "json_object"))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONObject().apply {
                            put("currentProfile", currentProfile.toJson())
                            put("dailyTypingSummary", summary.toJson())
                        }.toString())
                    })
                })
            }
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $openAiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Profile refinement failed (${response.code})"))
            }
            val content = JSONObject(response.body?.string() ?: "{}")
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            val profile = PersonalLanguageProfile.fromJson(JSONObject(content))
                ?: return@withContext Result.failure(Exception("Invalid profile JSON"))
            if (!ProfileMergeEngine.validate(profile)) {
                return@withContext Result.failure(Exception("Rejected profile JSON"))
            }
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private companion object {
        private const val SYSTEM_PROMPT = """
You are a privacy-preserving keyboard profile coach. Refine the user's PersonalLanguageProfile from summarized typing signals.
Return ONLY a JSON object matching this schema:
{
  "schemaVersion": 1,
  "profileVersion": number,
  "updatedAtMillis": number,
  "customVocabulary": [{"text": string, "weight": number -1..1, "reason": string}],
  "neverAutocorrect": [string],
  "phraseBoosts": [{"text": string, "weight": number -1..1, "reason": string}],
  "nextWordPredictions": [{"previous": string, "next": string, "weight": number -1..1, "reason": string}],
  "stylePreferences": [string],
  "punctuationHabits": [{"text": string, "weight": number -1..1, "reason": string}],
  "correctionPenalties": [{"typed": string, "correction": string, "weight": number 0..1, "reason": string}],
  "recentTopicBoosts": [{"text": string, "weight": number -1..1, "reason": string}]
}
Do not invent private facts. Prefer durable patterns, project names, brand terms, phrase frequencies, and corrections the user rejected.
Keep entries short and directly useful to local next-word prediction, suggestion reranking, and autocorrect penalties.
"""
    }
}
