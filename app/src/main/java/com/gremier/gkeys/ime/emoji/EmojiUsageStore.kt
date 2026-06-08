package com.gremier.gkeys.ime.emoji

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Tracks emoji tap counts locally for the frequently-used row. */
object EmojiUsageStore {
    private const val TAG = "EmojiUsageStore"
    private const val FILE_NAME = "emoji_usage.json"
    private const val MAX_TRACKED = 120

    val defaultFrequent: List<String> = listOf(
        "😂", "❤️", "👍", "😊", "🙏", "🔥", "✨", "😭", "🎉"
    )

    private data class Profile(
        val counts: LinkedHashMap<String, Int> = linkedMapOf(),
        val recent: MutableList<String> = mutableListOf()
    )

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun record(context: Context, emoji: String) {
        if (!EmojiCatalog.isEmoji(emoji)) return
        val profile = load(context)
        profile.counts[emoji] = (profile.counts[emoji] ?: 0) + 1
        profile.recent.remove(emoji)
        profile.recent.add(0, emoji)
        trim(profile)
        save(context, profile)
    }

    fun mostUsed(context: Context, limit: Int): List<String> {
        val profile = load(context)
        if (profile.counts.isEmpty()) {
            return defaultFrequent.take(limit)
        }
        return profile.counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { profile.recent.indexOf(it.key).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
            )
            .map { it.key }
            .take(limit)
    }

    private fun trim(profile: Profile) {
        while (profile.counts.size > MAX_TRACKED) {
            val least = profile.counts.entries.minWithOrNull(
                compareBy<Map.Entry<String, Int>> { it.value }
                    .thenBy { profile.recent.indexOf(it.key).let { idx -> if (idx < 0) Int.MAX_VALUE else -idx } }
            )?.key ?: break
            profile.counts.remove(least)
            profile.recent.remove(least)
        }
    }

    private fun load(context: Context): Profile {
        val f = file(context)
        if (!f.exists()) return Profile()
        return try {
            parseJson(f.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load emoji usage", e)
            Profile()
        }
    }

    private fun save(context: Context, profile: Profile) {
        try {
            file(context).writeText(toJson(profile).toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save emoji usage", e)
        }
    }

    private fun toJson(profile: Profile): JSONObject = JSONObject().apply {
        put("counts", JSONObject().apply {
            profile.counts.forEach { (emoji, count) -> put(emoji, count) }
        })
        put("recent", profile.recent.joinToString("\u001F"))
    }

    private fun parseJson(raw: String): Profile {
        val root = JSONObject(raw)
        val countsObj = root.optJSONObject("counts") ?: JSONObject()
        val counts = linkedMapOf<String, Int>()
        countsObj.keys().forEach { key ->
            counts[key] = countsObj.optInt(key, 0)
        }
        val recent = root.optString("recent", "")
            .split('\u001F')
            .filter { it.isNotEmpty() }
            .toMutableList()
        return Profile(counts, recent)
    }
}
