package com.riiiiiiiley.discourse.core

import org.json.JSONObject

/**
 * A named role, mapped from a power level. Interoperates with Cinny's
 * `in.cinny.room.power_level_tags`: `name`, `color`, and a nested `icon`
 * object whose `key` is either a unicode emoji or a custom-emote `mxc://` URL.
 */
data class PowerLevelTag(
    val name: String,
    val color: String? = null,
    /** Cinny's `icon.key`: a unicode emoji, or an `mxc://` URL for a custom emote. */
    val iconKey: String? = null,
) {
    val content: JSONObject
        get() = JSONObject().apply {
            put("name", name)
            color?.let { put("color", it) }
            iconKey?.let { put("icon", JSONObject().put("key", it)) }
        }

    /** True when `iconKey` points at a custom emote rather than a unicode emoji. */
    val iconIsMxc: Boolean get() = iconKey?.startsWith("mxc://") == true

    companion object {
        fun from(content: JSONObject): PowerLevelTag? {
            val name = (content.opt("name") as? String)?.trim()
            if (name.isNullOrEmpty()) return null
            return PowerLevelTag(
                name = name,
                color = content.opt("color") as? String,
                iconKey = content.optJSONObject("icon")?.opt("key") as? String,
            )
        }
    }
}

object PowerLevelTags {
    const val eventType = "in.cinny.room.power_level_tags"

    /** Parses the state-event content: a flat map of power level → tag. */
    fun parse(content: JSONObject): Map<Int, PowerLevelTag> {
        val tags = mutableMapOf<Int, PowerLevelTag>()
        for (key in content.keys()) {
            val level = key.toIntOrNull() ?: continue
            val dict = content.optJSONObject(key) ?: continue
            val tag = PowerLevelTag.from(dict) ?: continue
            tags[level] = tag
        }
        return tags
    }

    fun content(from: Map<Int, PowerLevelTag>): JSONObject {
        val json = JSONObject()
        for ((level, tag) in from) json.put(level.toString(), tag.content)
        return json
    }

    /**
     * The label to show for a member at `level`: the exact tag if defined, else
     * the nearest defined tag at or below it (so a room creator's "infinite"
     * power still inherits the top role), else a coarse built-in default.
     */
    fun displayTag(forLevel: Int, tags: Map<Int, PowerLevelTag>): PowerLevelTag {
        tags[forLevel]?.let { return it }
        tags.keys.filter { it <= forLevel }.maxOrNull()?.let { return tags.getValue(it) }
        return defaultTag(forLevel)
    }

    /**
     * Coarse label for a level with no explicit tag (used as an editor
     * placeholder and the final display fallback).
     */
    fun defaultTag(forLevel: Int): PowerLevelTag = when {
        forLevel < 0 -> PowerLevelTag(name = "Muted")
        forLevel < 50 -> PowerLevelTag(name = "Member")
        forLevel < 100 -> PowerLevelTag(name = "Moderator")
        else -> PowerLevelTag(name = "Admin")
    }
}
