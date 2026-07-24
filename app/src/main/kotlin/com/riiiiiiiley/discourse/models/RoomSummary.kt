package com.riiiiiiiley.discourse.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.Normalizer
import java.util.Locale

/**
 * Plain, immutable snapshot of a room for the sidebar, mapped from FFI
 * `Room`/`RoomInfo`. Serializable for the sidebar's cold-launch snapshot;
 * `foldedName` is left out and recomputed on decode: folding is
 * locale-sensitive, so a persisted value could go stale and break search.
 *
 * NOTE: `copy(name = …)` does NOT recompute `foldedName` (Kotlin `copy` passes
 * the existing value) — only the FFI update helpers change `name`, and they
 * set `foldedName` explicitly.
 */
@Serializable
data class RoomSummary(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val topic: String? = null,
    val isDirect: Boolean = false,
    val isSpace: Boolean = false,
    /**
     * Video room (`io.element.video` / MSC3417): a standing call rather than
     * a text timeline.
     */
    val isVideoRoom: Boolean = false,
    val isEncrypted: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageIsOwn: Boolean = false,
    val lastMessageSenderName: String? = null,
    /** Epoch milliseconds (iOS `Date`). */
    val lastActivity: Long? = null,
    val unreadMessages: ULong = 0u,
    val unreadNotifications: ULong = 0u,
    val unreadMentions: ULong = 0u,
    val isMarkedUnread: Boolean = false,
    /**
     * Notification mode set to Mute: surfaces only real mentions, no unread
     * pip, capsule, or dock contribution otherwise.
     */
    val isMuted: Boolean = false,
    val isFavourite: Boolean = false,
    val isLowPriority: Boolean = false,
    val hasActiveCall: Boolean = false,
    /**
     * User ids currently in the room's call (MatrixRTC members), for Discord-
     * style participant avatars in the list. Live-only; not persisted.
     */
    @Transient val callParticipantIds: List<String> = emptyList(),
    /** The other party in a DM (first room hero), for presence. */
    val dmUserId: String? = null,
    /** Pending invite awaiting accept/decline. */
    val isInvited: Boolean = false,
    val inviterName: String? = null,
    /** `name` folded for search; recomputed on decode (see class doc). */
    @Transient val foldedName: String = foldedForSearch(name),
) {
    /**
     * Notification-level unread (bold name + count capsule). A muted room
     * reaches this only via a real mention.
     */
    val hasUnread: Boolean
        get() {
            if (isMuted) return unreadMentions > 0u
            return unreadNotifications > 0u || unreadMentions > 0u || isMarkedUnread
        }

    /**
     * Any unread indication for aggregation, including the dim "unread
     * messages, no notification" state; never for a muted room without a mention.
     */
    val hasAnyUnread: Boolean
        get() = hasUnread || (!isMuted && unreadMessages > 0u)

    /** A real mention is waiting; shown even when the room is muted. */
    val isMentioned: Boolean get() = unreadMentions > 0u

    /**
     * Unread capsule count, summed into the app badge. Muted rooms contribute
     * only their mention count.
     */
    val badgeCount: ULong get() = if (isMuted) unreadMentions else unreadNotifications

    companion object {
        /** Fold queries with this too so comparisons against `foldedName` line up. */
        fun foldedForSearch(string: String): String =
            Normalizer.normalize(string, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
                .lowercase(Locale.getDefault())

        private val COMBINING_MARKS = Regex("\\p{Mn}+")
    }
}
