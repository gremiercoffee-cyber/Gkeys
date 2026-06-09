package com.gremier.gkeys.ime.emoji

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Tracks every emoji the user has typed for the recently-used section. */
object EmojiUsageStore {
    private const val TAG = "EmojiUsageStore"
    private const val FILE_NAME = "emoji_usage.json"

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
        save(context, profile)
    }

    /** Most recently used first; includes every emoji ever typed. */
    fun recentlyUsed(context: Context): List<String> {
        val profile = load(context)
        return if (profile.recent.isEmpty()) defaultFrequent else profile.recent.toList()
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
