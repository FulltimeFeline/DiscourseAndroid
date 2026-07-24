package com.riiiiiiiley.discourse.models

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One row in the conversation, index-aligned 1:1 with the SDK's timeline
 * items; even "hidden" items get an entry so positional diffs stay valid.
 */
sealed class TimelineEntry {
    abstract val id: String

    data class Message(val item: MessageItem) : TimelineEntry() {
        override val id: String get() = item.id
    }

    data class System(override val id: String, val text: String) : TimelineEntry()

    /** `date` is epoch milliseconds (iOS `Date`). */
    data class DayDivider(override val id: String, val date: Long) : TimelineEntry()
    data class ReadMarker(override val id: String) : TimelineEntry()
    data class TimelineStart(override val id: String) : TimelineEntry()

    /** Items we never render (unknown virtual items, filtered event types). */
    data class Hidden(override val id: String) : TimelineEntry()

    companion object {
        /**
         * The signed-in user, for marking own poll votes. Written only from
         * the main thread, before mapping.
         */
        @Volatile
        var currentOwnUserId: String = ""

        /**
         * Strips the Matrix reply fallback — the leading `> <@user> …` quoted
         * lines and their trailing blank separator — from a reply's body. The
         * reply preview renders the quoted message instead, so keeping the
         * fallback would double it up as ugly `> …` text.
         */
        fun strippedReplyFallback(body: String, isReply: Boolean): String {
            if (!isReply || !body.startsWith(">")) return body
            val lines = body.split("\n")
            var i = 0
            while (i < lines.size && lines[i].startsWith(">")) i++
            if (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) return body
            return lines.subList(i, lines.size).joinToString("\n")
        }
    }
}

/**
 * A user mention: the `@user:server` id plus the display text as it appears
 * in the plain body (so rendering can locate and pill it).
 */
data class MentionRef(
    val userId: String,
    val text: String,
)

data class MessageItem(
    val id: String,
    val eventId: String? = null,
    val transactionId: String? = null,
    val sender: String,
    val senderDisplayName: String? = null,
    val senderAvatarUrl: String? = null,
    /** Present when this message is the root of a thread. */
    val threadInfo: ThreadInfo? = null,
    /** Present when this message replies to another. */
    val replyPreview: ReplyPreview? = null,
    val isOwn: Boolean,
    /** Epoch milliseconds (iOS `Date`). */
    val timestamp: Long,
    val kind: Kind,
    val isEdited: Boolean = false,
    val reactions: List<MessageReaction> = emptyList(),
    /**
     * MSC2545 custom emoji in this message's formatted body, as
     * `":shortcode:" → mxc URL`. Rendering swaps the plain-body tokens for images.
     */
    val inlineEmotes: Map<String, String> = emptyMap(),
    /**
     * User mentions parsed from the formatted body's `matrix.to` anchors. The
     * plain body carries the same display text, which rendering swaps for a pill.
     */
    val mentions: List<MentionRef> = emptyList(),
    val sendState: SendState? = null,
    val canBeRepliedTo: Boolean = false,
    /**
     * Other users whose latest read receipt sits on this event; their avatars
     * ride this row and move down as they read.
     */
    val readReceiptUserIds: List<String> = emptyList(),
    /**
     * Fetches the encryption shield lazily (on appear). Computing it during
     * diff mapping forced crypto work for every item on every diff.
     */
    val shieldProvider: ShieldProviderBox? = null,
    /** First message of a sender group shows avatar + name + timestamp. */
    val showsHeader: Boolean = true,
) {
    sealed class Kind {
        data class Text(val body: String) : Kind()
        data class Notice(val body: String) : Kind()
        data class Emote(val body: String) : Kind()
        data class Image(val item: ImageItem) : Kind()
        data class Video(val item: VideoItem) : Kind()
        data class Poll(val item: PollItem) : Kind()
        data class Audio(val item: AudioItem) : Kind()
        data class Location(val body: String, val geoUri: String) : Kind()

        /**
         * Non-image attachments render as a labeled chip. `systemImage` keeps
         * the iOS SF Symbol name ("doc", "photo.on.rectangle", "location.fill");
         * the row maps it to a Material icon.
         */
        data class Media(val label: String, val systemImage: String) : Kind()
        data object Redacted : Kind()
        data object UnableToDecrypt : Kind()
        data class Unsupported(val text: String) : Kind()
    }

    enum class SendState { SENDING, FAILED }

    data class ThreadInfo(val replyCount: ULong)

    data class ReplyPreview(
        val eventId: String,
        val senderName: String,
        val snippet: String,
        /**
         * The replied-to event's details aren't loaded yet (the SDK resolves
         * them lazily); the view model fetches them so the snippet fills in.
         */
        val isPending: Boolean = false,
    )

    /**
     * Per-message encryption warning (e.g. sent unencrypted in an E2EE room,
     * unverified sender).
     */
    data class ShieldWarning(val level: Level, val text: String) {
        enum class Level { RED, GREY }
    }

    val displayName: String get() = senderDisplayName ?: sender
}

data class PollItem(
    val question: String,
    val answers: List<Answer>,
    val maxSelections: Int,
    /** Disclosed polls show live results; undisclosed only at the end. */
    val isDisclosed: Boolean,
    val isEnded: Boolean,
) {
    data class Answer(
        val id: String,
        val text: String,
        val voteCount: Int,
        val votedByMe: Boolean,
    )

    val totalVotes: Int get() = answers.sumOf { it.voteCount }
    val votedByMe: Boolean get() = answers.any { it.votedByMe }
}

data class MessageReaction(
    val key: String,
    val senders: List<String>,
) {
    val count: Int get() = senders.size
    fun includesOwn(userId: String): Boolean = senders.contains(userId)
}

data class AudioItem(
    val filename: String,
    /** Seconds (iOS `TimeInterval`). */
    val duration: Double? = null,
    val isVoiceMessage: Boolean = false,
    /** 0…1 normalised waveform, when the sender provided one. */
    val waveform: List<Float> = emptyList(),
    val source: MediaSourceBox,
)

data class ImageItem(
    val filename: String,
    val caption: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    val source: MediaSourceBox,
    /** Stickers render smaller and without the open-externally affordance. */
    val isSticker: Boolean = false,
    /**
     * Blurhash from the event's `ImageInfo`, decoded as a placeholder while
     * the thumbnail loads.
     */
    val blurhash: String? = null,
) {
    /** The event declared usable pixel dimensions. */
    val hasKnownSize: Boolean
        get() = width != null && height != null && width >= 1 && height >= 1

    /** Display size clamped to an inline footprint (dp ≈ iOS points). */
    val displaySize: DpSize
        get() {
            val maxWidth = if (isSticker) 160.0 else 360.0
            val maxHeight = if (isSticker) 160.0 else 280.0
            // >= 1 also guards against degenerate metadata.
            if (width == null || height == null || width < 1 || height < 1) {
                // Unknown dimensions: square placeholder for stickers; the view
                // letterboxes rather than crops.
                return if (isSticker) DpSize(160.dp, 160.dp) else DpSize(240.dp, 180.dp)
            }
            val scale = min(min(maxWidth / width, maxHeight / height), 1.0)
            return DpSize(
                max(40.0, width * scale).dp,
                max(40.0, height * scale).dp,
            )
        }
}

data class VideoItem(
    val filename: String,
    val caption: String? = null,
    val width: Double? = null,
    val height: Double? = null,
    /** Seconds (iOS `TimeInterval`). */
    val duration: Double? = null,
    /** The video file itself (played on tap). */
    val source: MediaSourceBox,
    /** Poster frame, when the sender provided one. */
    val thumbnailSource: MediaSourceBox? = null,
    val blurhash: String? = null,
    val mimeType: String? = null,
) {
    val hasKnownSize: Boolean
        get() = width != null && height != null && width >= 1 && height >= 1

    /** Inline footprint, matching images so a video reads like a playable image. */
    val displaySize: DpSize
        get() {
            val maxWidth = 360.0
            val maxHeight = 280.0
            if (width == null || height == null || width < 1 || height < 1) {
                return DpSize(240.dp, 180.dp)
            }
            val scale = min(min(maxWidth / width, maxHeight / height), 1.0)
            return DpSize(
                max(40.0, width * scale).dp,
                max(40.0, height * scale).dp,
            )
        }

    /** "1:05" style duration badge, when known. */
    val durationText: String?
        get() {
            val duration = duration ?: return null
            if (duration < 1) return null
            val total = Math.round(duration).toInt()
            return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }
}
