package com.riiiiiiiley.discourse.features.timeline

import com.riiiiiiiley.discourse.models.MentionRef
import java.net.URLDecoder

/** Parsing for MSC2545 custom emoji ("emotes"). */
object InlineEmotes {
    /**
     * `<img data-mx-emoticon …>` tags in a formatted body, as a
     * `":shortcode:" → mxc URL` map. The plain-text body carries the same
     * tokens (the img alt text), which is where rendering swaps them in.
     */
    fun parse(html: String): Map<String, String> {
        if (!html.contains("<img")) return emptyMap()
        val found = mutableMapOf<String, String>()
        for (match in imgTag.findAll(html)) {
            val tag = match.value
            val url = attribute("src", tag) ?: continue
            if (!url.startsWith("mxc://")) continue
            val name = attribute("alt", tag) ?: attribute("title", tag) ?: continue
            val trimmed = name.trim(':')
            if (trimmed.isEmpty()) continue
            // Accept the explicit MSC2545 marker, or any mxc image whose alt/
            // title is a `:shortcode:` token — some clients omit the attribute.
            val isEmote = tag.contains("data-mx-emoticon") ||
                (name.startsWith(":") && name.endsWith(":") &&
                    trimmed.all(::isShortcodeCharacter))
            if (!isEmote) continue
            found[":$trimmed:"] = url
        }
        return found
    }

    /** One rendered segment of a body: literal text or an emote image. */
    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Emote(val token: String, val url: String) : Segment()
    }

    /** Splits a plain body on known `:token:` occurrences. */
    fun segments(of: String, emotes: Map<String, String>): List<Segment> {
        val segments = mutableListOf<Segment>()
        val pendingText = StringBuilder()
        var index = 0
        while (index < of.length) {
            val colon = of.indexOf(':', index)
            if (colon < 0) break
            pendingText.append(of, index, colon)
            var end = colon + 1
            while (end < of.length && isShortcodeCharacter(of[end])) end++
            if (end < of.length && of[end] == ':' && end > colon + 1) {
                val token = of.substring(colon, end + 1)
                val url = emotes[token]
                if (url != null) {
                    if (pendingText.isNotEmpty()) {
                        segments.add(Segment.Text(pendingText.toString()))
                        pendingText.setLength(0)
                    }
                    segments.add(Segment.Emote(token = token, url = url))
                    index = end + 1
                    continue
                }
            }
            pendingText.append(':')
            index = colon + 1
        }
        if (index < of.length) pendingText.append(of, index, of.length)
        if (pendingText.isNotEmpty()) segments.add(Segment.Text(pendingText.toString()))
        return segments
    }

    /**
     * Characters accepted in a `:token:`. (Owned by CustomEmojiStore on iOS;
     * lives here until that store's port lands.)
     */
    fun isShortcodeCharacter(character: Char): Boolean =
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.'

    private val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)

    private val attributeRegexes: Map<String, Regex> = listOf("src", "alt", "title")
        .associateWith { name ->
            Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE)
        }

    private fun attribute(name: String, tag: String): String? {
        val regex = attributeRegexes[name] ?: return null
        val match = regex.find(tag) ?: return null
        // Group 1: double-quoted value; group 2: single-quoted.
        val value = match.groups[1]?.value ?: match.groups[2]?.value ?: return null
        return decodeEntities(value)
    }

    /** `&amp;` last, or "&amp;lt;" double-decodes to "<". */
    internal fun decodeEntities(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}

/** Parsing for user mentions carried as `matrix.to` anchors in a formatted body. */
object MentionParser {
    /**
     * Extracts `<a href="https://matrix.to/#/@user:server">Display</a>` anchors
     * as `MentionRef`s. The plain-text body carries the same display text (the
     * anchor's inner text), which is where rendering swaps in the pill.
     */
    fun parse(html: String): List<MentionRef> {
        if (!html.contains("matrix.to", ignoreCase = true)) return emptyList()
        val found = mutableListOf<MentionRef>()
        for (match in anchorTag.findAll(html)) {
            val href = InlineEmotes.decodeEntities(match.groupValues[1])
            val userId = userIdFromMatrixTo(href) ?: continue
            // Strip any nested tags from the anchor's inner text, then entities.
            val text = InlineEmotes.decodeEntities(stripTags(match.groupValues[2]))
            if (text.isEmpty()) continue
            found.add(MentionRef(userId = userId, text = text))
        }
        return found
    }

    /**
     * `https://matrix.to/#/@user:server` (optionally URL/HTML-escaped) → the
     * `@user:server` id. Returns null for room/event links.
     */
    fun userIdFromMatrixTo(href: String): String? {
        val hashIndex = href.indexOf("/#/")
        if (hashIndex < 0) return null
        val fragment = href.substring(hashIndex + 3)
        // Drop any trailing query/segment and percent-decode.
        val token = fragment.split('/').firstOrNull() ?: fragment
        val decoded = runCatching { URLDecoder.decode(token, "UTF-8") }.getOrDefault(token)
        return if (decoded.startsWith("@")) decoded else null
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")

    // Group 1: href value; group 2: inner text.
    private val anchorTag = Regex(
        "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
