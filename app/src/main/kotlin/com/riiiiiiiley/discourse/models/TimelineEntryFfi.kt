package com.riiiiiiiley.discourse.models

import com.riiiiiiiley.discourse.features.timeline.InlineEmotes
import com.riiiiiiiley.discourse.features.timeline.MentionParser
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.InReplyToDetails
import org.matrix.rustcomponents.sdk.LazyTimelineItemProvider
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

/** Hashable wrapper for the FFI `MediaSource` class so plain models can carry it. */
class MediaSourceBox(val source: MediaSource) {
    val url: String = source.url()

    override fun equals(other: Any?): Boolean = other is MediaSourceBox && other.url == url
    override fun hashCode(): Int = url.hashCode()
}

/**
 * Hashable wrapper retaining the FFI provider so the shield can be computed
 * lazily (per row, on appear). Identity is the owning timeline item.
 */
class ShieldProviderBox(
    val provider: LazyTimelineItemProvider,
    private val itemId: String,
) {
    override fun equals(other: Any?): Boolean = other is ShieldProviderBox && other.itemId == itemId
    override fun hashCode(): Int = itemId.hashCode()

    /**
     * Runs the shield computation; the FFI provider is thread-safe, so this
     * is safe off the main thread.
     */
    fun warning(): MessageItem.ShieldWarning? =
        shieldWarning(provider.getShields(strict = false))
}

val MessageItem.ffiItemId: EventOrTransactionId?
    get() {
        eventId?.let { return EventOrTransactionId.EventId(it) }
        transactionId?.let { return EventOrTransactionId.TransactionId(it) }
        return null
    }

/**
 * Maps every SDK item to exactly one entry (never drops items) so the
 * array stays index-aligned with the diff stream.
 */
fun TimelineEntry.Companion.from(item: TimelineItem, ownUserId: String): TimelineEntry {
    val uid = item.uniqueId().id
    item.asEvent()?.let { return fromEvent(uid, it, ownUserId) }
    return when (val virtualItem = item.asVirtual()) {
        is VirtualTimelineItem.DateDivider ->
            TimelineEntry.DayDivider(id = uid, date = virtualItem.ts.toLong())
        is VirtualTimelineItem.ReadMarker -> TimelineEntry.ReadMarker(id = uid)
        is VirtualTimelineItem.TimelineStart -> TimelineEntry.TimelineStart(id = uid)
        else -> TimelineEntry.Hidden(id = uid)
    }
}

private fun fromEvent(uid: String, event: EventTimelineItem, ownUserId: String): TimelineEntry {
    var senderName: String? = null
    var senderAvatar: String? = null
    (event.senderProfile as? ProfileDetails.Ready)?.let {
        senderName = it.displayName
        senderAvatar = it.avatarUrl
    }

    return when (val content = event.content) {
        is TimelineItemContent.MsgLike -> {
            val msgLike = content.content
            var eventId: String? = null
            var transactionId: String? = null
            when (val id = event.eventOrTransactionId) {
                is EventOrTransactionId.EventId -> eventId = id.eventId
                is EventOrTransactionId.TransactionId -> transactionId = id.transactionId
            }

            val sendState: MessageItem.SendState? = when (event.localSendState) {
                is EventSendState.NotSentYet -> MessageItem.SendState.SENDING
                is EventSendState.SendingFailed -> MessageItem.SendState.FAILED
                else -> null
            }

            TimelineEntry.Message(MessageItem(
                id = uid,
                eventId = eventId,
                transactionId = transactionId,
                sender = event.sender,
                senderDisplayName = senderName,
                senderAvatarUrl = senderAvatar,
                threadInfo = msgLike.threadSummary?.let {
                    MessageItem.ThreadInfo(replyCount = it.numReplies())
                },
                replyPreview = msgLike.inReplyTo?.let { replyPreview(it) },
                isOwn = event.isOwn,
                timestamp = event.timestamp.toLong(),
                kind = kindOf(msgLike, ownUserId),
                isEdited = isEdited(msgLike),
                reactions = msgLike.reactions.map { reaction ->
                    MessageReaction(key = reaction.key,
                                    senders = reaction.senders.map { it.senderId })
                },
                inlineEmotes = inlineEmotesOf(msgLike),
                mentions = mentionsOf(msgLike),
                sendState = sendState,
                canBeRepliedTo = event.canBeRepliedTo,
                readReceiptUserIds = event.readReceipts.keys
                    .filter { it != ownUserId }
                    .sorted(),
                shieldProvider = ShieldProviderBox(provider = event.lazyProvider, itemId = uid),
            ))
        }

        is TimelineItemContent.RoomMembership -> {
            val name = content.userDisplayName ?: content.userId
            TimelineEntry.System(id = uid, text = membershipText(name, content.change))
        }

        is TimelineItemContent.ProfileChange -> {
            val displayName = content.displayName
            val prevDisplayName = content.prevDisplayName
            val text = if (displayName != null && prevDisplayName != null &&
                displayName != prevDisplayName
            ) {
                "$prevDisplayName is now known as $displayName"
            } else {
                "${displayName ?: senderName ?: "Someone"} updated their profile"
            }
            TimelineEntry.System(id = uid, text = text)
        }

        is TimelineItemContent.State ->
            TimelineEntry.System(id = uid, text = "${senderName ?: "Someone"} updated the room")

        is TimelineItemContent.CallInvite, is TimelineItemContent.RtcNotification ->
            TimelineEntry.System(id = uid, text = "${senderName ?: "Someone"} started a call")

        is TimelineItemContent.FailedToParseMessageLike,
        is TimelineItemContent.FailedToParseState,
        -> TimelineEntry.Hidden(id = uid)
    }
}

internal fun shieldWarning(state: ShieldState): MessageItem.ShieldWarning? {
    val level: MessageItem.ShieldWarning.Level
    val code: TimelineEventShieldStateCode
    when (state) {
        is ShieldState.Red -> {
            level = MessageItem.ShieldWarning.Level.RED
            code = state.code
        }
        is ShieldState.Grey -> {
            // Keys restored from backup or forwarded between own sessions carry
            // this harmlessly; flagging it would train users to ignore the
            // shields that matter.
            if (state.code == TimelineEventShieldStateCode.AUTHENTICITY_NOT_GUARANTEED) return null
            level = MessageItem.ShieldWarning.Level.GREY
            code = state.code
        }
        is ShieldState.None -> return null
    }
    val text = when (code) {
        TimelineEventShieldStateCode.SENT_IN_CLEAR ->
            "Not encrypted"
        TimelineEventShieldStateCode.UNVERIFIED_IDENTITY ->
            "Encrypted by an unverified user"
        TimelineEventShieldStateCode.UNSIGNED_DEVICE ->
            "Encrypted by a device not verified by its owner"
        TimelineEventShieldStateCode.UNKNOWN_DEVICE ->
            "Encrypted by an unknown or deleted device"
        TimelineEventShieldStateCode.AUTHENTICITY_NOT_GUARANTEED ->
            "The authenticity of this encrypted message can't be guaranteed on this device"
        TimelineEventShieldStateCode.VERIFICATION_VIOLATION ->
            "The sender's verified identity has changed"
        TimelineEventShieldStateCode.MISMATCHED_SENDER ->
            "The sender doesn't match the device that encrypted this message"
    }
    return MessageItem.ShieldWarning(level = level, text = text)
}

private fun replyPreview(details: InReplyToDetails): MessageItem.ReplyPreview {
    val eventId = details.eventId()
    return when (val event = details.event()) {
        is EmbeddedEventDetails.Ready -> {
            var name = event.sender
            (event.senderProfile as? ProfileDetails.Ready)?.displayName?.let { name = it }
            MessageItem.ReplyPreview(
                eventId = eventId,
                senderName = name,
                snippet = previewText(event.content) ?: "…",
            )
        }
        is EmbeddedEventDetails.Pending, is EmbeddedEventDetails.Unavailable ->
            MessageItem.ReplyPreview(eventId = eventId, senderName = "", snippet = "…",
                                     isPending = true)
        is EmbeddedEventDetails.Error ->
            MessageItem.ReplyPreview(eventId = eventId, senderName = "",
                                     snippet = "Message unavailable")
    }
}

private fun kindOf(msgLike: MsgLikeContent, ownUserId: String): MessageItem.Kind = when (val kind = msgLike.kind) {
    is MsgLikeKind.Message -> when (val msgType = kind.content.msgType) {
        is MessageType.Text -> MessageItem.Kind.Text(
            TimelineEntry.strippedReplyFallback(msgType.content.body,
                                                isReply = msgLike.inReplyTo != null))
        is MessageType.Notice -> MessageItem.Kind.Notice(
            TimelineEntry.strippedReplyFallback(msgType.content.body,
                                                isReply = msgLike.inReplyTo != null))
        is MessageType.Emote -> MessageItem.Kind.Emote(msgType.content.body)
        is MessageType.Image -> {
            val content = msgType.content
            MessageItem.Kind.Image(ImageItem(
                filename = content.filename,
                caption = content.caption,
                // ULong → Double directly; never via the bit pattern.
                width = content.info?.width?.toDouble(),
                height = content.info?.height?.toDouble(),
                source = MediaSourceBox(content.source),
                blurhash = content.info?.blurhash,
            ))
        }
        is MessageType.Video -> {
            val content = msgType.content
            MessageItem.Kind.Video(VideoItem(
                filename = content.filename,
                caption = content.caption,
                width = content.info?.width?.toDouble(),
                height = content.info?.height?.toDouble(),
                duration = content.info?.duration?.let { it.toMillis() / 1000.0 },
                source = MediaSourceBox(content.source),
                thumbnailSource = content.info?.thumbnailSource?.let { MediaSourceBox(it) },
                blurhash = content.info?.blurhash,
                mimeType = content.info?.mimetype,
            ))
        }
        is MessageType.Audio -> {
            val content = msgType.content
            MessageItem.Kind.Audio(AudioItem(
                filename = content.filename,
                duration = (content.info?.duration ?: content.audio?.duration)
                    ?.let { it.toMillis() / 1000.0 },
                isVoiceMessage = content.voice != null,
                waveform = (content.audio?.waveform ?: emptyList())
                    .map { it.toFloat() / 1024f },
                source = MediaSourceBox(content.source),
            ))
        }
        is MessageType.File ->
            MessageItem.Kind.Media(label = msgType.content.filename, systemImage = "doc")
        is MessageType.Gallery ->
            MessageItem.Kind.Media(label = "Gallery", systemImage = "photo.on.rectangle")
        is MessageType.Location ->
            MessageItem.Kind.Location(body = msgType.content.body,
                                      geoUri = msgType.content.geoUri)
        is MessageType.Other -> MessageItem.Kind.Text(msgType.body)
    }
    is MsgLikeKind.Sticker -> MessageItem.Kind.Image(ImageItem(
        filename = kind.body,
        caption = null,
        width = kind.info.width?.toDouble(),
        height = kind.info.height?.toDouble(),
        source = MediaSourceBox(kind.source),
        isSticker = true,
    ))
    is MsgLikeKind.Poll -> MessageItem.Kind.Poll(PollItem(
        question = kind.question,
        answers = kind.answers.map { answer ->
            val voters = kind.votes[answer.id] ?: emptyList()
            PollItem.Answer(id = answer.id,
                            text = answer.text,
                            voteCount = voters.size,
                            votedByMe = voters.contains(ownUserId))
        },
        maxSelections = kind.maxSelections.toInt(),
        isDisclosed = kind.kind == PollKind.DISCLOSED,
        isEnded = kind.endTime != null,
    ))
    is MsgLikeKind.Redacted -> MessageItem.Kind.Redacted
    is MsgLikeKind.UnableToDecrypt -> MessageItem.Kind.UnableToDecrypt
    is MsgLikeKind.Other -> MessageItem.Kind.Unsupported("Unsupported event")
    is MsgLikeKind.LiveLocation ->
        MessageItem.Kind.Media(label = "Live location", systemImage = "location.fill")
}

/** The HTML formatted body, when one exists. Only text-ish messages carry HTML. */
private fun htmlFormattedBody(msgLike: MsgLikeContent): FormattedBody? {
    val message = (msgLike.kind as? MsgLikeKind.Message)?.content ?: return null
    val formatted = when (val msgType = message.msgType) {
        is MessageType.Text -> msgType.content.formatted
        is MessageType.Notice -> msgType.content.formatted
        is MessageType.Emote -> msgType.content.formatted
        else -> null
    } ?: return null
    return formatted.takeIf { it.format is MessageFormat.Html }
}

/**
 * Custom emoji (MSC2545 `<img data-mx-emoticon>`) as a `":shortcode:" →
 * mxc URL` map.
 */
private fun inlineEmotesOf(msgLike: MsgLikeContent): Map<String, String> {
    val formatted = htmlFormattedBody(msgLike) ?: return emptyMap()
    return InlineEmotes.parse(html = formatted.body)
}

/** User mentions (matrix.to anchors) from the HTML formatted body. */
private fun mentionsOf(msgLike: MsgLikeContent): List<MentionRef> {
    val formatted = htmlFormattedBody(msgLike) ?: return emptyList()
    return MentionParser.parse(html = formatted.body)
}

private fun isEdited(msgLike: MsgLikeContent): Boolean =
    (msgLike.kind as? MsgLikeKind.Message)?.content?.isEdited == true

private fun membershipText(name: String, change: MembershipChange?): String = when (change) {
    MembershipChange.JOINED -> "$name joined the room"
    MembershipChange.LEFT -> "$name left the room"
    MembershipChange.INVITED -> "$name was invited"
    MembershipChange.INVITATION_ACCEPTED -> "$name accepted the invitation"
    MembershipChange.INVITATION_REJECTED -> "$name declined the invitation"
    MembershipChange.BANNED, MembershipChange.KICKED_AND_BANNED -> "$name was banned"
    MembershipChange.UNBANNED -> "$name was unbanned"
    MembershipChange.KICKED -> "$name was removed"
    MembershipChange.KNOCKED -> "$name requested to join"
    else -> "$name's membership changed"
}
