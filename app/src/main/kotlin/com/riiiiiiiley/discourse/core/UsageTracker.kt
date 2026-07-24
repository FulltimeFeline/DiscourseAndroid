package com.riiiiiiiley.discourse.core

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** Tracks reaction usage so the quick-reaction slots adapt over time. */
object ReactionUsage {
    private const val countsKey = "reactionUsageCounts"
    private val defaults = listOf("👍", "❤️", "😂", "🎉", "😮", "😢", "🔥", "👀")

    private fun prefs(context: Context) =
        context.getSharedPreferences("discourse", Context.MODE_PRIVATE)

    fun record(context: Context, emoji: String) {
        val prefs = prefs(context)
        val counts = runCatching { JSONObject(prefs.getString(countsKey, null) ?: "{}") }
            .getOrDefault(JSONObject())
        counts.put(emoji, counts.optInt(emoji, 0) + 1)
        prefs.edit { putString(countsKey, counts.toString()) }
    }

    /**
     * Most-used reactions, padded with defaults. Filtered to emoji: Matrix
     * reaction keys can be arbitrary text ("+1", "lol") that would render as a
     * blank slot.
     */
    fun top(context: Context, count: Int): List<String> {
        val counts = runCatching { JSONObject(prefs(context).getString(countsKey, null) ?: "{}") }
            .getOrDefault(JSONObject())
        val result = counts.keys().asSequence()
            .map { it to counts.optInt(it, 0) }
            .sortedByDescending { it.second }
            .map { it.first }
            .filter(::isEmoji)
            .toMutableList()
        for (fallback in defaults) {
            if (fallback !in result) result.add(fallback)
        }
        return result.take(count)
    }

    private fun isEmoji(key: String): Boolean {
        if (key.isEmpty()) return false
        // The iOS check reads the first scalar's emoji properties; approximate
        // with the code-point range — everything below U+2100 is plain text.
        val first = key.codePointAt(0)
        return first > 0x2100
    }
}

/** Recently sent stickers, for the picker's recents tab. */
object StickerUsage {
    private const val recentsKey = "recentStickers"

    private fun prefs(context: Context) =
        context.getSharedPreferences("discourse", Context.MODE_PRIVATE)

    fun record(context: Context, shortcode: String) {
        val recents = recents(context).toMutableList()
        recents.removeAll { it == shortcode }
        recents.add(0, shortcode)
        prefs(context).edit { putString(recentsKey, org.json.JSONArray(recents.take(16)).toString()) }
    }

    fun recents(context: Context): List<String> {
        val raw = prefs(context).getString(recentsKey, null) ?: return emptyList()
        val array = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}
