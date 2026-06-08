package com.gremier.gkeys.ime.touch

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists adaptive touch profile locally — no network, no typed content beyond word counts. */
object AdaptiveTouchStore {
    private const val TAG = "AdaptiveTouchStore"
    private const val FILE_NAME = "adaptive_touch_profile.json"
    private const val MAX_VOCAB_ENTRIES = 500
    private const val MAX_CONFUSION_ENTRIES = 200

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun load(context: Context): AdaptiveTouchProfile {
        val f = file(context)
        if (!f.exists()) return AdaptiveTouchProfile()
        return try {
            parseJson(f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load profile", e)
            AdaptiveTouchProfile()
        }
    }

    fun save(context: Context, profile: AdaptiveTouchProfile) {
        try {
            file(context).writeText(toJson(profile).toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profile", e)
        }
    }

    fun reset(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset profile", e)
        }
    }

    private fun toJson(p: AdaptiveTouchProfile): JSONObject = JSONObject().apply {
        put("version", p.version)
        put("totalTaps", p.totalTaps)
        put("correctionsLearned", p.correctionsLearned)
        put("posture", p.posture.name)
        put("rightSideTapRatio", p.rightSideTapRatio.toDouble())
        put("avgInterKeyMs", p.avgInterKeyMs.toDouble())
        put("keys", JSONArray().apply {
            p.keyStats.values.sortedBy { it.key }.forEach { s ->
                put(JSONObject().apply {
                    put("key", s.key.toString())
                    put("tapCount", s.tapCount)
                    put("sumOffsetX", s.sumOffsetX)
                    put("sumOffsetY", s.sumOffsetY)
                    put("sumOffsetX2", s.sumOffsetX2)
                    put("sumOffsetY2", s.sumOffsetY2)
                    put("radiusMultiplier", s.radiusMultiplier.toDouble())
                })
            }
        })
        put("confusions", JSONArray().apply {
            p.confusions.forEach { c ->
                put(JSONObject().apply {
                    put("wrong", c.wrongKey.toString())
                    put("correct", c.correctKey.toString())
                    put("relX", c.relOffsetX.toDouble())
                    put("relY", c.relOffsetY.toDouble())
                    put("count", c.count)
                })
            }
        })
        put("vocab", JSONObject().apply {
            p.vocabFrequency.entries
                .sortedByDescending { it.value }
                .take(MAX_VOCAB_ENTRIES)
                .forEach { (word, count) -> put(word, count) }
        })
    }

    private fun parseJson(raw: String): AdaptiveTouchProfile {
        val root = JSONObject(raw)
        val profile = AdaptiveTouchProfile(
            version = root.optInt("version", 1),
            totalTaps = root.optInt("totalTaps", 0),
            correctionsLearned = root.optInt("correctionsLearned", 0),
            posture = runCatching {
                TypingPosture.valueOf(root.optString("posture", TypingPosture.UNKNOWN.name))
            }.getOrDefault(TypingPosture.UNKNOWN),
            rightSideTapRatio = root.optDouble("rightSideTapRatio", 0.5).toFloat(),
            avgInterKeyMs = root.optDouble("avgInterKeyMs", 180.0).toFloat()
        )
        root.optJSONArray("keys")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("key").lowercase()[0]
                profile.keyStats[key] = KeyTouchStats(
                    key = key,
                    tapCount = o.optInt("tapCount", 0),
                    sumOffsetX = o.optDouble("sumOffsetX", 0.0),
                    sumOffsetY = o.optDouble("sumOffsetY", 0.0),
                    sumOffsetX2 = o.optDouble("sumOffsetX2", 0.0),
                    sumOffsetY2 = o.optDouble("sumOffsetY2", 0.0),
                    radiusMultiplier = o.optDouble("radiusMultiplier", 1.0).toFloat()
                )
            }
        }
        root.optJSONArray("confusions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                profile.confusions.add(
                    ConfusionSample(
                        wrongKey = o.getString("wrong").lowercase()[0],
                        correctKey = o.getString("correct").lowercase()[0],
                        relOffsetX = o.optDouble("relX", 0.0).toFloat(),
                        relOffsetY = o.optDouble("relY", 0.0).toFloat(),
                        count = o.optInt("count", 1)
                    )
                )
            }
        }
        root.optJSONObject("vocab")?.let { vocab ->
            vocab.keys().forEach { word ->
                profile.vocabFrequency[word] = vocab.optInt(word, 0)
            }
        }
        return profile
    }

    fun trimProfile(profile: AdaptiveTouchProfile) {
        if (profile.confusions.size > MAX_CONFUSION_ENTRIES) {
            profile.confusions.sortByDescending { it.count }
            while (profile.confusions.size > MAX_CONFUSION_ENTRIES) {
                profile.confusions.removeAt(profile.confusions.lastIndex)
            }
        }
        if (profile.vocabFrequency.size > MAX_VOCAB_ENTRIES) {
            val trimmed = profile.vocabFrequency.entries
                .sortedByDescending { it.value }
                .take(MAX_VOCAB_ENTRIES)
                .associate { it.key to it.value }
            profile.vocabFrequency.clear()
            profile.vocabFrequency.putAll(trimmed)
        }
    }
}
