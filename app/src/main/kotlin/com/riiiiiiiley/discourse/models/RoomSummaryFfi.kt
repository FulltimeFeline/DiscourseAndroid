package com.riiiiiiiley.discourse.models

import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.TimelineItemContent
import uniffi.matrix_sdk_base.EncryptionState

// Maps FFI types to plain models; the only place outside Core (and the
// timeline mapping) importing the Rust SDK.

/**
 * Cheap synchronous snapshot from a `Room`'s cached accessors; unreads and
 * last-message preview are filled in asynchronously afterwards.
 */
fun RoomSummary.Companion.basicsOf(room: Room): RoomSummary {
    val id = room.id()
    val name = room.displayName() ?: id
    return RoomSummary(
        id = id,
        name = name,
        avatarUrl = room.avatarUrl(),
        topic = room.topic(),
        foldedName = RoomSummary.foldedForSearch(name),
    )
}

fun RoomSummary.updated(info: RoomInfo): RoomSummary {
    val name = info.displayName ?: info.canonicalAlias ?: id
    val isDirect = info.isDm || info.isDirect
    // DMs rarely set a room avatar; fall back to the other member's (hero)
    // avatar so the room list and notifications show their pfp.
    var avatarUrl = info.avatarUrl
    if (isDirect && avatarUrl == null) {
        avatarUrl = info.heroes.firstOrNull()?.avatarUrl
    }
    return copy(
        name = name,
        foldedName = RoomSummary.foldedForSearch(name),
        avatarUrl = avatarUrl,
        topic = info.topic,
        isDirect = isDirect,
        isSpace = info.isSpace,
        isEncrypted = info.encryptionState == EncryptionState.ENCRYPTED,
        unreadMessages = info.numUnreadMessages,
        unreadNotifications = info.numUnreadNotifications,
        unreadMentions = info.numUnreadMentions,
        isMarkedUnread = info.isMarkedUnread,
        isMuted = info.cachedUserDefinedNotificationMode == RoomNotificationMode.MUTE,
        isFavourite = info.isFavourite,
        isLowPriority = info.isLowPriority,
        hasActiveCall = info.hasRoomCall,
        callParticipantIds = info.activeRoomCallParticipants,
        dmUserId = if (isDirect) info.heroes.firstOrNull()?.userId else null,
        isInvited = info.membership == Membership.INVITED,
    )
}

fun RoomSummary.updated(latest: LatestEventValue): RoomSummary = when (latest) {
    is LatestEventValue.Remote -> copy(
        lastActivity = latest.timestamp.toLong(),
        lastMessagePreview = previewText(latest.content),
        lastMessageIsOwn = latest.isOwn,
        lastMessageSenderName = profileDisplayName(latest.profile) ?: localpart(latest.sender),
    )
    is LatestEventValue.Local -> copy(
        lastActivity = latest.timestamp.toLong(),
        lastMessagePreview = previewText(latest.content),
        lastMessageIsOwn = true,
    )
    is LatestEventValue.RemoteInvite -> copy(
        lastActivity = latest.timestamp.toLong(),
        lastMessagePreview = "Invitation",
        lastMessageIsOwn = false,
        lastMessageSenderName = null,
    )
    is LatestEventValue.None -> this
}

/** Video and call rooms, by `m.room.create` type. */
fun isVideoRoomType(type: RoomType): Boolean =
    type is RoomType.Custom &&
        (type.value == "io.element.video" || type.value == "org.matrix.msc3417.call")

private fun profileDisplayName(profile: ProfileDetails): String? =
    (profile as? ProfileDetails.Ready)?.displayName

private fun localpart(userId: String): String {
    if (!userId.startsWith("@")) return userId
    return userId.drop(1).takeWhile { it != ':' }
}

/**
 * Mirrors `MediaLoader.avatar`'s conversion so synchronous cache lookups
 * hit the same key.
 */
fun avatarSource(mxcUrl: String): MediaSourceBox? =
    runCatching { MediaSourceBox(MediaSource.fromUrl(mxcUrl)) }.getOrNull()

fun previewText(content: TimelineItemContent): String? = when (content) {
    is TimelineItemContent.MsgLike -> when (val kind = content.content.kind) {
        is MsgLikeKind.Message -> {
            // Replies: drop the "> <@user> …" fallback and mark with ↩, so
            // the sidebar preview shows the reply text, not the quoted one.
            val isReply = content.content.inReplyTo != null
            val stripped = TimelineEntry
                .strippedReplyFallback(kind.content.body, isReply = isReply)
                .replace("\n", " ")
            if (isReply) "↩ $stripped" else stripped
        }
        is MsgLikeKind.Sticker -> "Sticker: ${kind.body}"
        is MsgLikeKind.Poll -> "Poll: ${kind.question}"
        is MsgLikeKind.Redacted -> "Message deleted"
        else -> "Encrypted message"
    }
    is TimelineItemContent.RoomMembership,
    is TimelineItemContent.ProfileChange,
    is TimelineItemContent.State,
    -> null
    else -> null
}
