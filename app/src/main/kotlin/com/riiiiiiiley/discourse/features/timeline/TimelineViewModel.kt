package com.riiiiiiiley.discourse.features.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.riiiiiiiley.discourse.core.Blurhash
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.StickerStore
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.call.CallViewModel
import com.riiiiiiiley.discourse.features.stickers.StickerSender
import com.riiiiiiiley.discourse.features.timeline.media.AudioPlaybackController
import com.riiiiiiiley.discourse.core.MediaProcessing
import com.riiiiiiiley.discourse.core.PowerLevelTag
import com.riiiiiiiley.discourse.core.PowerLevelTags
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.ReactionUsage
import com.riiiiiiiley.discourse.models.MentionRef
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.RoomSummary
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.models.ffiItemId
import com.riiiiiiiley.discourse.models.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.RoomMessageEventMessageType
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.messageEventContentFromHtml
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk.RoomMemberRole
import uniffi.matrix_sdk_base.EncryptionState
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.pow

/**
 * A finished voice recording, produced by the recorder component (lands with
 * the voice-message UI phase; the send path below is complete).
 */
data class VoiceRecording(
    val data: ByteArray,
    /** Seconds. */
    val duration: Double,
    /** 0…1 normalised waveform samples. */
    val waveform: List<Float>,
)

/**
 * Owns one room's live timeline: applies SDK diffs to `entries`, recomputes
 * sender grouping, and drives back-pagination.
 *
 * iOS also hands this a MediaLoader (media phase); the remaining media send
 * helpers attach here when that store lands.
 */
class TimelineViewModel(
    private val room: Room,
    val ownUserId: String,
    private val preferences: Preferences,
    context: Context,
    private val service: MatrixService? = null,
    /** `:shortcode:` → MSC2545 HTML on send (iOS customEmoji). */
    private val customEmoji: CustomEmojiStore? = null,
    val mode: Mode = Mode.Live,
) {
    sealed class Mode {
        data object Live : Mode()
        data class Thread(val rootEventId: String) : Mode()

        /** Backs the Media tab of the details column. */
        data object Media : Mode()
    }

    private val appContext = context.applicationContext

    /** All state mutates on the main dispatcher (the iOS @MainActor analogue). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val roomId: String = room.id()

    private val _roomName = MutableStateFlow(room.displayName() ?: room.id())
    val roomName: StateFlow<String> = _roomName

    private val _topic = MutableStateFlow(room.topic())
    val topic: StateFlow<String?> = _topic

    // Internal mutable mirror of `entries`; published once per diff batch.
    private val entriesList = mutableListOf<TimelineEntry>()
    private val _entries = MutableStateFlow<List<TimelineEntry>>(emptyList())
    val entries: StateFlow<List<TimelineEntry>> = _entries

    private fun publishEntries() {
        _entries.value = entriesList.toList()
    }

    private val _reachedStart = MutableStateFlow(false)
    val reachedStart: StateFlow<Boolean> = _reachedStart

    private val _isPaginating = MutableStateFlow(false)
    val isPaginating: StateFlow<Boolean> = _isPaginating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Set by the view's scroll observer; gates autoscroll and read receipts.
     * True only when the newest message is on screen, not merely prefetched
     * into the lazy list ahead.
     */
    var isAtBottom = true

    /**
     * SDK read-marker entry ("NEW" divider), if in the loaded window. Cached
     * per diff batch so jump-to-unread doesn't rescan `entries` per frame.
     */
    private val _firstUnreadMarkerId = MutableStateFlow<String?>(null)
    val firstUnreadMarkerId: StateFlow<String?> = _firstUnreadMarkerId

    /**
     * Whether the "jump to unread" pill should show. A marker auto-dismisses a
     * few seconds after it appears (you've seen it), stays dismissed across a
     * park/unpark room switch (this VM is cached), and re-arms only for a
     * genuinely different marker.
     */
    private val _unreadMarkerVisible = MutableStateFlow(false)
    val unreadMarkerVisible: StateFlow<Boolean> = _unreadMarkerVisible
    private var dismissedMarkerId: String? = null
    private var unreadDismissJob: Job? = null

    /**
     * Per-room composer draft, retained here so switching rooms (which tears
     * down the composer) doesn't lose half-typed text.
     */
    var draftText = ""

    /** Inline voice/audio playback (media slice); one active item per room. */
    val audioPlayback = AudioPlaybackController(appContext)

    val replyTarget = MutableStateFlow<MessageItem?>(null)
    val editTarget = MutableStateFlow<MessageItem?>(null)

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments

    data class PendingAttachment(
        val id: String = UUID.randomUUID().toString(),
        val filename: String,
        val data: ByteArray,
        val previewImage: Bitmap? = null,
        /** Bytes still loading off-main; excluded from sends until the read lands. */
        val isLoading: Boolean = false,
        /** Last upload failed; the chip is back in the composer for retry. */
        val uploadFailed: Boolean = false,
    )

    /** Transient composer failure line; auto-clears after a few seconds. */
    private val _composerError = MutableStateFlow<String?>(null)
    val composerError: StateFlow<String?> = _composerError
    private var composerErrorJob: Job? = null

    private fun presentComposerError(text: String) {
        _composerError.value = text
        composerErrorJob?.cancel()
        composerErrorJob = scope.launch {
            delay(6_000)
            _composerError.value = null
        }
    }

    /**
     * Messages accepted before `start()` had a timeline; the composer clears
     * optimistically, so these are flushed in order once it exists.
     */
    private sealed class QueuedOutbound {
        data class Text(val text: String, val useComposerTargets: Boolean = true) : QueuedOutbound()
        data class Attachment(val attachment: PendingAttachment, val inReplyTo: String?) :
            QueuedOutbound()
    }

    private val outboundQueue = mutableListOf<QueuedOutbound>()

    private val _typingUsers = MutableStateFlow<List<String>>(emptyList())
    val typingUsers: StateFlow<List<String>> = _typingUsers

    private val _hasActiveCall = MutableStateFlow(false)
    val hasActiveCall: StateFlow<Boolean> = _hasActiveCall

    /**
     * The room is a standing call. Set by the creating scope from
     * space-listing data — `RoomInfo` doesn't carry the `m.room.create` type.
     */
    val isVideoRoom = MutableStateFlow(false)

    /**
     * Rows in the lazy viewport, reported by the view. Lives here (not view
     * state) so the anchor can be read at room-switch time, before teardown
     * drains the row callbacks.
     */
    var visibleEntryIds: Set<String> = emptySet()

    /**
     * True while the phone keeps this timeline mounted offscreen behind the
     * room list. The bottom sentinel still counts as "visible" there, so
     * receipt-sending must be gated or parked chats silently read messages.
     * Parking also sheds memory (see `parkTimeline`).
     */
    var isParked = false
        set(value) {
            if (field == value) return
            field = value
            if (value) parkTimeline() else unparkTimeline()
        }

    /** Set when parking detached the diff listener; the unpark re-attach clears it. */
    private var parkedListenerDetached = false

    /**
     * Bottom-most visible event captured on park, so after unpark's full
     * `.reset` re-delivers the timeline the view can land back where it was
     * instead of drifting up.
     */
    private var pendingUnparkAnchor: String? = null

    /** The view scrolls here (then clears it) once unpark rebuilds the entries. */
    private val _unparkScrollTarget = MutableStateFlow<String?>(null)
    val unparkScrollTarget: StateFlow<String?> = _unparkScrollTarget
    fun clearUnparkScrollTarget() {
        _unparkScrollTarget.value = null
    }

    /**
     * Sheds a parked room's memory: entry models beyond the viewport anchor,
     * and the member list (reloaded on unpark). The listener is detached
     * first — positional diffs can't apply to a truncated array; the unpark
     * re-attach delivers a fresh reset with the full item list instead.
     */
    private fun parkTimeline() {
        if (mode != Mode.Live || timeline == null) return
        pendingUnparkAnchor = scrollAnchorEventId
        audioPlayback.stopAll()
        streamJob?.cancel()
        streamJob = null
        ephemeralSyncJob?.cancel()
        ephemeralSyncJob = null
        detachTimelineListener()
        parkedListenerDetached = true
        // Keep the scroll anchor's row plus a tail of recent context.
        val keepTail = 200
        var start = maxOf(0, entriesList.size - keepTail)
        val anchor = scrollAnchorEventId
        if (anchor != null) {
            val anchorIndex = entriesList.indexOfFirst {
                (it as? TimelineEntry.Message)?.item?.eventId == anchor
            }
            if (anchorIndex >= 0) start = minOf(start, anchorIndex)
        }
        if (start > 0) {
            repeat(start) { entriesList.removeAt(0) }
            // Pagination refills the dropped history after the unpark resync.
            _reachedStart.value = false
            publishEntries()
        }
        _members.value = emptyList()
        _membersById.value = emptyMap()
    }

    /**
     * Re-attaches the diff listener (initial reset restores the full list)
     * and reloads members. The phone keeps the parked view mounted, so the
     * view's launch effect never refires — reload here; `members.isEmpty`
     * keeps the two callers from doubling up.
     */
    private fun unparkTimeline() {
        val needsResync = parkedListenerDetached
        parkedListenerDetached = false
        val timeline = timeline
        if (!needsResync || timeline == null) return
        scope.launch {
            // A rapid unpark→park can schedule this and re-park before it
            // runs; attaching then defeats the memory shed and leaks the
            // replaced stream job on the next unpark.
            if (isParked) {
                parkedListenerDetached = true
                return@launch
            }
            attachTimelineListener(timeline)
            if (_members.value.isEmpty()) loadMembers()
        }
    }

    /** Scroll-memory anchor: the bottom-most visible event, null when at bottom. */
    val scrollAnchorEventId: String?
        get() {
            if (isAtBottom) return null
            return entriesList.lastOrNull { visibleEntryIds.contains(it.id) }
                ?.let { (it as? TimelineEntry.Message)?.item?.eventId }
        }

    private val _isEncrypted = MutableStateFlow(false)
    val isEncrypted: StateFlow<Boolean> = _isEncrypted

    private val _isDirect = MutableStateFlow(false)
    val isDirect: StateFlow<Boolean> = _isDirect

    private val _avatarUrl = MutableStateFlow<String?>(null)
    val avatarUrl: StateFlow<String?> = _avatarUrl

    private val _memberCount = MutableStateFlow(0UL)
    val memberCount: StateFlow<ULong> = _memberCount

    private val _members = MutableStateFlow<List<MemberItem>>(emptyList())
    val members: StateFlow<List<MemberItem>> = _members

    /**
     * Members keyed by user ID; rows resolve names/avatars per receipt and
     * reaction, which linear scans made O(members) each.
     */
    private val _membersById = MutableStateFlow<Map<String, MemberItem>>(emptyMap())
    val membersById: StateFlow<Map<String, MemberItem>> = _membersById

    /**
     * Per-event crypto shields, fetched lazily as rows appear — computing
     * them during diff mapping forced eager crypto work for every item.
     */
    private val _shields = MutableStateFlow<Map<String, MessageItem.ShieldWarning>>(emptyMap())
    val shields: StateFlow<Map<String, MessageItem.ShieldWarning>> = _shields
    private val shieldsRequested = mutableSetOf<String>()

    /**
     * Fetches a row's shield once per event; the row reads the result back
     * from `shields`. `.set` diffs re-arm the fetch (see `apply`).
     */
    fun loadShieldIfNeeded(message: MessageItem) {
        val eventId = message.eventId ?: return
        val provider = message.shieldProvider ?: return
        if (!shieldsRequested.add(eventId)) return
        scope.launch(Dispatchers.Default) {
            val warning = provider.warning()
            withContext(Dispatchers.Main.immediate) { storeShield(warning, eventId) }
        }
    }

    private fun storeShield(warning: MessageItem.ShieldWarning?, eventId: String) {
        val current = _shields.value
        if (current[eventId] == warning) return
        _shields.value = if (warning == null) current - eventId else current + (eventId to warning)
    }

    /** The other participant in a 1:1 chat, for presence. */
    val dmPeerId: String?
        get() {
            if (!_isDirect.value) return null
            return _members.value.firstOrNull { it.id != ownUserId }?.id
        }

    /**
     * Newest own message, but only while nobody has read past it: a receipt
     * on any later row means this one was read too, so the "sent" tick would
     * contradict it. Recomputed per diff batch so per-row reads don't rescan.
     */
    private val _lastOwnMessageId = MutableStateFlow<String?>(null)
    val lastOwnMessageId: StateFlow<String?> = _lastOwnMessageId

    private fun updateLastOwnMessageId() {
        var newValue: String? = null
        loop@ for (entry in entriesList.asReversed()) {
            val message = (entry as? TimelineEntry.Message)?.item ?: continue
            if (message.isOwn) {
                newValue = message.id
                break@loop
            }
            if (message.readReceiptUserIds.isNotEmpty()) break@loop
        }
        _lastOwnMessageId.value = newValue
    }

    /**
     * Newest own editable message (own, real event ID, plain text). Backs the
     * ↑-in-empty-composer shortcut. Computed on demand, not per diff, since
     * `lastOwnMessageId` can point at non-text messages.
     */
    fun lastOwnEditableMessage(): MessageItem? {
        for (entry in entriesList.asReversed()) {
            val message = (entry as? TimelineEntry.Message)?.item ?: continue
            if (message.isOwn && message.eventId != null && message.kind is MessageItem.Kind.Text) {
                return message
            }
        }
        return null
    }

    data class MemberItem(
        val id: String,
        val displayName: String? = null,
        val avatarUrl: String? = null,
        val role: Role = Role.MEMBER,
        val powerLevel: Int = 0,
        /** `name` case/diacritic-folded for mention-autocomplete matching. */
        val foldedName: String = RoomSummary.foldedForSearch(displayName ?: id),
    ) {
        /** Ordinal order doubles as rank (creator highest). */
        enum class Role { CREATOR, ADMINISTRATOR, MODERATOR, MEMBER }

        val name: String get() = displayName ?: id
    }

    private var timeline: Timeline? = null
    private var retained = mutableListOf<Any>()

    /**
     * The diff listener's bridge + handle, kept apart from `retained` so
     * parking can detach and re-attach just this listener.
     */
    private var timelineListenerBridge: DiffBridge? = null
    private var timelineListenerHandle: TaskHandle? = null
    private var streamJob: Job? = null
    private var streamJob2: Job? = null
    private var typingStreamJob: Job? = null
    private var typingStopJob: Job? = null

    /**
     * Clears a stale typing indicator if no refresh arrives — the "stopped
     * typing" update can get lost, leaving the banner stuck.
     */
    private var typingExpiryJob: Job? = null
    private var lastTypingNotice: Long? = null

    /**
     * Debounce state for `markAsRead`; the bottom sentinel fires it on every
     * appear/disappear flip.
     */
    private var lastMarkedReadEventId: String? = null
    private var lastMarkedReadAt: Long? = null

    /** A view model for the thread rooted at the given event. */
    fun threadViewModel(rootEventId: String): TimelineViewModel =
        TimelineViewModel(room = room, ownUserId = ownUserId, preferences = preferences,
                          context = appContext, service = service, customEmoji = customEmoji,
                          mode = Mode.Thread(rootEventId = rootEventId))

    private var mediaVM: TimelineViewModel? = null

    /** The Media tab's attachment-only timeline, cached so reopening is instant. */
    fun mediaViewModel(): TimelineViewModel {
        mediaVM?.let { return it }
        val vm = TimelineViewModel(room = room, ownUserId = ownUserId, preferences = preferences,
                                   context = appContext, service = service,
                                   customEmoji = customEmoji, mode = Mode.Media)
        mediaVM = vm
        return vm
    }

    /**
     * Own moderation powers, from the room's power levels; gate the kick/ban
     * menu items on these instead of failing after the fact.
     */
    private val _canKick = MutableStateFlow(false)
    val canKick: StateFlow<Boolean> = _canKick
    private val _canBan = MutableStateFlow(false)
    val canBan: StateFlow<Boolean> = _canBan
    private val _canInvite = MutableStateFlow(false)
    val canInvite: StateFlow<Boolean> = _canInvite

    /**
     * Redact permissions, checked against the own user id. `canRedactOwn`
     * gates deleting your own messages; `canRedactOther` lets a moderator
     * delete anyone's. Read by the message row per-message.
     */
    private val _canRedactOwn = MutableStateFlow(false)
    val canRedactOwn: StateFlow<Boolean> = _canRedactOwn
    private val _canRedactOther = MutableStateFlow(false)
    val canRedactOther: StateFlow<Boolean> = _canRedactOther

    /** Whether the own user may change members' power levels (promote/demote). */
    private val _canChangePowerLevels = MutableStateFlow(false)
    val canChangePowerLevels: StateFlow<Boolean> = _canChangePowerLevels

    /** The own user's power level — the ceiling on what we can grant. */
    private val _ownPowerLevel = MutableStateFlow(0)
    val ownPowerLevel: StateFlow<Int> = _ownPowerLevel

    /** Reports a message to the homeserver admins. Returns an error, or null on success. */
    suspend fun report(eventId: String, reason: String?): String? = try {
        room.reportContent(eventId, reason)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: "Couldn't report the message"
    }

    /**
     * Recomputes the own user's action powers from the room's current power
     * levels. Fails closed if they can't be fetched.
     */
    private suspend fun refreshPermissions() {
        // getPowerLevels() is a suspend FFI that polls the Rust runtime; it
        // fires per room-info tick, so keep it off the input thread.
        val levels = withContext(Dispatchers.IO) {
            runCatching { room.getPowerLevels() }.getOrNull()
        } ?: return
        // StateFlow drops same-value writes — this runs per room-info tick.
        _canInvite.value = levels.canOwnUserInvite()
        _canKick.value = levels.canOwnUserKick()
        _canBan.value = levels.canOwnUserBan()
        _canRedactOwn.value = levels.canOwnUserRedactOwn()
        _canRedactOther.value = levels.canOwnUserRedactOther()
        _canChangePowerLevels.value =
            levels.canOwnUserSendState(StateEventType.RoomPowerLevels)
        _ownPowerLevel.value = levels.userPowerLevels()[ownUserId]?.toInt()
            ?: levels.values().usersDefault.toInt()
    }

    /**
     * Sets a member's power level (promote/demote). Returns an error message on
     * failure. Refreshes members so the role label updates.
     */
    suspend fun setPowerLevel(userId: String, level: Int): String? = try {
        withContext(Dispatchers.IO) {
            room.updatePowerLevelsForUsers(
                listOf(UserPowerLevelUpdate(userId = userId, powerLevel = level.toLong())))
        }
        loadMembers(force = true)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: "Couldn't change the role"
    }

    /** Removes a member (kick). Returns an error message on failure. */
    suspend fun kick(userId: String): String? = try {
        room.kickUser(userId, null)
        loadMembers(force = true)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: "Couldn't remove the member"
    }

    /** Bans a member. Returns an error message on failure. */
    suspend fun ban(userId: String): String? = try {
        room.banUser(userId, null)
        loadMembers(force = true)
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: "Couldn't ban the member"
    }

    /**
     * Paginates back until the given event is in `entries` (search-hit jumps),
     * bounded so a miss doesn't walk the whole room.
     */
    suspend fun ensureLoaded(eventId: String): Boolean {
        fun isLoaded(): Boolean = entriesList.any {
            (it as? TimelineEntry.Message)?.item?.eventId == eventId
        }
        // A fresh mount races start(): pagination no-ops until the timeline
        // exists, burning the attempt budget. Wait for it first.
        var readiness = 0
        while (timeline == null && readiness < 50) {
            readiness++
            delay(100)
        }
        var attempts = 0
        while (!isLoaded() && !_reachedStart.value && attempts < 30) {
            attempts++
            paginateBackwards()
            // Let the listener stream's diffs apply.
            delay(80)
        }
        return isLoaded()
    }

    /**
     * A call session for this room (live timelines only); joins the running
     * call if one exists, otherwise starts one (iOS `callViewModel()`).
     */
    fun callViewModel(): CallViewModel? {
        val service = service ?: return null
        return CallViewModel(room, service.client, ownUserId, joinExisting = hasActiveCall.value)
    }

    private var startJob: Job? = null

    suspend fun start() {
        if (timeline != null) return
        // Two callers can overlap (view's launch effect and a notification
        // reply); both awaiting FFI setup would build two timelines and leak
        // the first listener job.
        startJob?.let {
            it.join()
            return
        }
        val job = scope.launch { performStart() }
        startJob = job
        job.join()
        startJob = null
    }

    private suspend fun performStart() {
        if (timeline != null) return
        // Sliding sync only streams a room's ephemeral events (receipts,
        // typing) promptly while subscribed; without this they trickle in on
        // unrelated list refreshes instead of live.
        if (mode == Mode.Live) {
            runCatching { service?.roomListService?.subscribeToRooms(listOf(roomId)) }
            // (Custom-emoji phase: customEmoji.ensureRoomPack(roomId, roomName)
            // — this room's own emote packs, one state fetch per room per session.)
        }
        try {
            val focus: TimelineFocus = when (mode) {
                is Mode.Live, is Mode.Media ->
                    TimelineFocus.Live(hideThreadedEvents = mode == Mode.Live)
                is Mode.Thread -> TimelineFocus.Thread(rootEventId = mode.rootEventId)
            }
            val filter: TimelineFilter = if (mode == Mode.Media) {
                TimelineFilter.OnlyMessage(types = listOf(
                    RoomMessageEventMessageType.IMAGE,
                    RoomMessageEventMessageType.VIDEO,
                    RoomMessageEventMessageType.FILE,
                    RoomMessageEventMessageType.AUDIO,
                    RoomMessageEventMessageType.GALLERY,
                ))
            } else {
                TimelineFilter.All
            }
            val prefix: String? = when (mode) {
                is Mode.Live -> null
                is Mode.Thread -> "thread"
                is Mode.Media -> "media"
            }
            val timeline = room.timelineWithConfiguration(TimelineConfiguration(
                focus = focus,
                filter = filter,
                internalIdPrefix = prefix,
                dateDividerMode = org.matrix.rustcomponents.sdk.DateDividerMode.DAILY,
                trackReadReceipts = if (mode == Mode.Live) {
                    TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS
                } else {
                    TimelineReadReceiptTracking.DISABLED
                },
                reportUtds = false,
            ))
            this.timeline = timeline

            retained = mutableListOf()
            attachTimelineListener(timeline)
            parkedListenerDetached = false
            if (mode == Mode.Live) {
                val typingBridge = TypingBridge()
                retained.add(typingBridge)
                retained.add(room.subscribeToTypingNotifications(typingBridge))
                typingStreamJob = scope.launch {
                    for (userIds in typingBridge.channel) {
                        Log.d("discourse.typing", "typing: [${userIds.joinToString(",")}]")
                        val filtered = userIds.filter { it != ownUserId }
                        // Refresh notices repeat the same list; StateFlow drops
                        // the same-value write.
                        _typingUsers.value = filtered
                        typingExpiryJob?.cancel()
                        if (filtered.isEmpty()) continue
                        // Active typers refresh every few seconds (observed
                        // ≤6s), but a stopped typer stays listed server-side
                        // until their client's ~30s timeout. Expire shortly
                        // after the last refresh to clear them without cutting
                        // off anyone still typing.
                        typingExpiryJob = scope.launch {
                            delay(10_000)
                            _typingUsers.value = emptyList()
                        }
                    }
                }

                val infoBridge = RoomInfoBridge()
                retained.add(infoBridge)
                retained.add(room.subscribeToRoomInfoUpdates(infoBridge))
                streamJob2 = scope.launch {
                    for (info in infoBridge.channel) {
                        // Room-info updates arrive on essentially every sync
                        // tick; StateFlow drops the same-value writes.
                        _hasActiveCall.value = info.hasRoomCall
                        _isEncrypted.value = info.encryptionState == EncryptionState.ENCRYPTED
                        _roomName.value = info.displayName ?: info.id
                        _topic.value = info.topic
                        _avatarUrl.value = info.avatarUrl
                        _memberCount.value = info.joinedMembersCount
                        // Power levels can change under us; re-gate actions.
                        scope.launch { refreshPermissions() }
                    }
                }
                runCatching { room.roomInfo() }.getOrNull()?.let { info ->
                    _hasActiveCall.value = info.hasRoomCall
                    _isEncrypted.value = info.encryptionState == EncryptionState.ENCRYPTED
                    _isDirect.value = info.isDm || info.isDirect
                    _avatarUrl.value = info.avatarUrl
                    _memberCount.value = info.joinedMembersCount
                }
                markAsRead()
            }
            // Redact/invite gating applies in every mode (threads render the
            // same message rows), so compute it outside the live-only block.
            refreshPermissions()

            flushOutboundQueue()

            // Kick the first page ourselves: the view's pagination sentinel
            // appears before the timeline exists, so its trigger is lost.
            paginateBackwards()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _error.value = "Couldn't open timeline: ${error.message}"
        }
    }

    /**
     * Cancels the Rust-side diff subscription. Dropping the handle alone
     * leaves Rust invoking `onUpdate` into an undrained UNLIMITED channel, so
     * FFI item handles pile up until a GC lets the uniffi cleaner abort it.
     */
    private fun detachTimelineListener() {
        val bridge = timelineListenerBridge
        val handle = timelineListenerHandle
        timelineListenerBridge = null
        timelineListenerHandle = null
        // Close (not cancel): a batch already queued stays deliverable to a
        // drain job that hasn't stopped yet.
        bridge?.channel?.close()
        // cancel()/destroy() are blocking UniFFI calls. This runs from the
        // isParked setter and from SessionScope's evict-all loop, both on Main,
        // so doing them inline stalled every chat open. The handle is already
        // detached above, so a concurrent re-attach builds its own.
        handle?.let { h ->
            teardownScope.launch {
                runCatching { h.cancel() }
                runCatching { h.destroy() }
            }
        }
    }

    /**
     * Subscribes the diff bridge (the SDK replays the item list as an initial
     * reset). Shared by `start()` and the unpark resync.
     */
    private suspend fun attachTimelineListener(timeline: Timeline) {
        // Replacing the listener must also stop the old drain job, or it
        // leaks suspended on a dropped bridge's channel.
        streamJob?.cancel()
        detachTimelineListener()
        val bridge = DiffBridge()
        val handle = timeline.addListener(bridge)
        timelineListenerBridge = bridge
        timelineListenerHandle = handle
        streamJob = scope.launch {
            for (diffs in bridge.channel) {
                // Cancellation-only: a batch racing parkTimeline's cancel must
                // not touch the truncated array. isParked is NOT an exit — the
                // phone mounts chats parked behind the list, and breaking here
                // killed the first open's stream for good.
                if (!isActive) break
                // TimelineEntry.from walks ~15+ Rust FFI getters per item; a
                // Reset/large Append is N×15 JNI polls of the Rust runtime.
                // Materialize the mapping off-main so it never blocks input,
                // then splice on Main. Diffs stay in order (the channel is
                // ordered) — we map each diff's items, then apply them in order.
                val mapped = withContext(Dispatchers.Default) {
                    diffs.map { diff -> materializeDiff(diff) }
                }
                if (!isActive) break
                apply(diffs, mapped)
            }
        }
        startEphemeralSync()
    }

    private suspend fun flushOutboundQueue() {
        while (timeline != null && outboundQueue.isNotEmpty()) {
            when (val queued = outboundQueue.removeAt(0)) {
                is QueuedOutbound.Text ->
                    sendText(queued.text, useComposerTargets = queued.useComposerTargets)
                is QueuedOutbound.Attachment ->
                    sendAttachmentData(queued.attachment, inReplyTo = queued.inReplyTo)
            }
        }
    }

    fun stop() {
        // A dismissal racing start() must kill the in-flight performStart, or it
        // resumes after this and attaches a listener + streamJob to a dead VM.
        startJob?.cancel()
        startJob = null
        audioPlayback.stopAll()
        mediaVM?.stop()
        mediaVM = null
        streamJob?.cancel()
        streamJob = null
        streamJob2?.cancel()
        streamJob2 = null
        typingStreamJob?.cancel()
        typingStreamJob = null
        typingStopJob?.cancel()
        typingStopJob = null
        typingExpiryJob?.cancel()
        typingExpiryJob = null
        ephemeralSyncJob?.cancel()
        ephemeralSyncJob = null
        retained = mutableListOf()
        detachTimelineListener()
        parkedListenerDetached = false
        timeline = null
    }

    // MARK: Sending

    fun hasPendingAttachments(): Boolean = _pendingAttachments.value.isNotEmpty()

    /**
     * `useComposerTargets = false` sends the text as a plain new message even
     * with an edit/reply pending — a retry must not be retargeted at whatever
     * the composer happens to be pointing at.
     */
    suspend fun sendText(
        text: String,
        mentions: List<MentionRef> = emptyList(),
        useComposerTargets: Boolean = true,
    ) {
        val timeline = this.timeline
        if (timeline == null) {
            // Composer already cleared its field; flush in order once start() has a timeline.
            outboundQueue.add(QueuedOutbound.Text(text, useComposerTargets))
            return
        }
        // Mentions and custom emoji both need an HTML formatted body; plain
        // markdown otherwise. The plain body stays human-readable (the raw
        // `@user:server` text), and the HTML carries the matrix.to anchors.
        val emojiHtml = customEmoji?.htmlBody(text)
        var content: RoomMessageEventContentWithoutRelation
        if (mentions.isNotEmpty()) {
            val html = mentionHtml(base = emojiHtml ?: htmlEscape(text), mentions = mentions)
            content = messageEventContentFromHtml(body = text, htmlBody = html)
            // Flag intentional mentions (MSC3952) so the mentioned users are notified.
            content = content.withMentions(Mentions(
                userIds = mentions.map { it.userId }.distinct(), room = false))
        } else if (emojiHtml != null) {
            content = messageEventContentFromHtml(body = text, htmlBody = emojiHtml)
        } else {
            content = messageEventContentFromMarkdown(md = text)
        }
        sendTypingNotice(false)
        val edit = if (useComposerTargets) editTarget.value else null
        val reply = if (useComposerTargets) replyTarget.value else null
        if (edit?.eventId != null) {
            editTarget.value = null
            try {
                timeline.edit(EventOrTransactionId.EventId(edit.eventId),
                              EditedContent.RoomMessage(content = content))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                presentComposerError("Couldn't edit that message")
            }
        } else if (reply?.eventId != null) {
            replyTarget.value = null
            try {
                timeline.sendReply(content, reply.eventId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                presentComposerError("Couldn't send your reply")
            }
        } else {
            try {
                timeline.send(content)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                presentComposerError("Couldn't send your message")
            }
        }
    }

    // MARK: Attachments (staged, then sent)

    /**
     * Stages a file for sending as a composer preview chip. The chip appears
     * immediately; the (possibly multi-MB) read happens off-main.
     */
    fun stageAttachment(uri: Uri) {
        val placeholder = PendingAttachment(filename = displayName(uri), data = ByteArray(0),
                                            isLoading = true)
        _pendingAttachments.value = _pendingAttachments.value + placeholder
        val id = placeholder.id
        scope.launch(Dispatchers.IO) {
            val data = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) { finishStaging(id, data) }
        }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                             null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "file"
    }

    private fun finishStaging(id: String, data: ByteArray?) {
        val list = _pendingAttachments.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        if (data == null || data.isEmpty()) {
            val filename = list[index].filename
            list.removeAt(index)
            _pendingAttachments.value = list
            presentComposerError("Couldn't read $filename")
            return
        }
        list[index] = list[index].copy(data = data, isLoading = false)
        _pendingAttachments.value = list
        scope.launch(Dispatchers.Default) {
            val thumb = previewThumbnail(data) ?: return@launch
            withContext(Dispatchers.Main.immediate) { attachPreview(thumb, id) }
        }
    }

    fun stageAttachment(data: ByteArray, filename: String) {
        var name = filename
        // Raw image data from drags/pastes comes nameless; derive one.
        if (name.isEmpty() || name == "image") {
            val ext = imageMimeType(data)?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: "png"
            name = "image.$ext"
        }
        val attachment = PendingAttachment(filename = name, data = data)
        _pendingAttachments.value = _pendingAttachments.value + attachment
        // Chip renders small; decode a small thumbnail off-main.
        val id = attachment.id
        scope.launch(Dispatchers.Default) {
            val thumb = previewThumbnail(data) ?: return@launch
            withContext(Dispatchers.Main.immediate) { attachPreview(thumb, id) }
        }
    }

    private fun previewThumbnail(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(data, 0, data.size, opts)
    }

    private fun attachPreview(bitmap: Bitmap, id: String) {
        val list = _pendingAttachments.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(previewImage = bitmap)
        _pendingAttachments.value = list
    }

    fun removeAttachment(id: String) {
        _pendingAttachments.value = _pendingAttachments.value.filter { it.id != id }
    }

    /** Sends staged attachments, then the text (if any). */
    suspend fun sendComposed(text: String, mentions: List<MentionRef> = emptyList()) {
        // Chips still loading stay staged for the next send.
        val staged = _pendingAttachments.value.filter { !it.isLoading }
        _pendingAttachments.value = _pendingAttachments.value.filter { it.isLoading }
        val message = text.trim()
        if (timeline == null) {
            // Not started — queue in composition order. With no text the reply
            // relation rides the first attachment (sendText is skipped).
            var replyEventId: String? = null
            val target = replyTarget.value
            if (message.isEmpty() && target?.eventId != null) {
                replyTarget.value = null
                replyEventId = target.eventId
            }
            var first = true
            for (attachment in staged) {
                outboundQueue.add(QueuedOutbound.Attachment(
                    attachment, inReplyTo = if (first) replyEventId else null))
                first = false
            }
            if (message.isNotEmpty()) outboundQueue.add(QueuedOutbound.Text(message))
            return
        }
        // With no text, attach the reply relation to the first attachment.
        var replyEventId: String? = null
        val target = replyTarget.value
        if (message.isEmpty() && target?.eventId != null) {
            replyTarget.value = null
            replyEventId = target.eventId
        }
        var first = true
        for (attachment in staged) {
            sendAttachmentData(attachment, inReplyTo = if (first) replyEventId else null)
            first = false
        }
        if (message.isNotEmpty()) {
            sendText(message, mentions = mentions)
        }
    }

    /** Kept for external callers (timeline drops); stages for preview. */
    fun sendAttachment(uri: Uri) {
        stageAttachment(uri)
    }

    /** The image mimetype when the bytes decode as an image, else null. */
    private fun imageMimeType(data: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outMimeType
    }

    /** Whether the filename describes a video (routes to `sendVideo`). */
    private fun isVideo(filename: String): Boolean {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: return false
        return mime.startsWith("video/")
    }

    private fun mimeTypeForFilename(filename: String): String? {
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private suspend fun sendAttachmentData(attachment: PendingAttachment,
                                           inReplyTo: String? = null) {
        val timeline = this.timeline
        if (timeline == null) {
            outboundQueue.add(QueuedOutbound.Attachment(attachment, inReplyTo))
            return
        }
        val data = attachment.data

        // Reject over the homeserver's cap up front, rather than uploading
        // megabytes only to fail.
        val maxSize = service?.maxUploadSize()
        if (maxSize != null && data.size.toULong() > maxSize) {
            val limitMb = maxSize.toDouble() / (1024 * 1024)
            presentComposerError(
                "${attachment.filename} is too large to send " +
                    "(limit ${String.format(java.util.Locale.US, "%.0f", limitMb)} MB).")
            return
        }

        val imageMime = imageMimeType(data)
        val mimetype = imageMime ?: mimeTypeForFilename(attachment.filename)
        val params = UploadParameters(
            source = UploadSource.Data(bytes = data, filename = attachment.filename),
            caption = null,
            formattedCaption = null,
            mentions = null,
            inReplyTo = inReplyTo,
        )

        // Videos: send as a playable video with a poster frame, falling back
        // to a file send if the asset can't be read.
        if (imageMime == null && isVideo(attachment.filename)) {
            val attrs = MediaProcessing.videoAttributes(appContext, data, attachment.filename)
            val width = attrs.width
            val height = attrs.height
            if (width != null && height != null && width > 0u && height > 0u) {
                val thumbSource: UploadSource? = attrs.thumbnail?.let {
                    UploadSource.Data(bytes = it.data, filename = "thumbnail.jpg")
                }
                val info = VideoInfo(
                    duration = attrs.duration?.let { Duration.ofMillis((it * 1000).toLong()) },
                    height = height,
                    width = width,
                    mimetype = mimetype ?: "video/mp4",
                    size = data.size.toULong(),
                    thumbnailInfo = attrs.thumbnail?.let {
                        ThumbnailInfo(height = it.height, width = it.width,
                                      mimetype = it.mimetype, size = it.data.size.toULong())
                    },
                    thumbnailSource = null,
                    blurhash = null,
                )
                Log.d("SENDDBG", "sending video ${attachment.filename} ${width}x$height ${data.size}B")
                try {
                    val handle = timeline.sendVideo(params, thumbSource, info)
                    handle.join()
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.d("SENDDBG", "video upload failed: $error")
                    restageFailedUpload(attachment)
                    return
                }
            }
            // Couldn't read the asset — drop through to a file send.
        }

        // Images: (optionally) strip location metadata, then send with a
        // thumbnail so encrypted-room recipients preview without the full
        // download. When the strip is off, original bytes go out as-is.
        val stripLocation = preferences.value.stripLocationMetadata
        if (imageMime != null) {
            data class Processed(
                val data: ByteArray,
                val mimetype: String,
                val width: ULong,
                val height: ULong,
                val blurhash: String?,
                val thumbnail: MediaProcessing.Thumbnail?,
            )

            val processed = withContext(Dispatchers.Default) {
                val image = if (stripLocation) {
                    MediaProcessing.sanitizedImage(data)
                } else {
                    MediaProcessing.imageAttributes(data)
                } ?: return@withContext null
                val blurhash = Blurhash.encode(imageData = image.data)
                val thumbnail = MediaProcessing.thumbnail(image.data)
                Processed(image.data, image.mimetype, image.width, image.height,
                          blurhash, thumbnail)
            }

            // The SDK requires width+height+size+mimetype AND a blurhash;
            // anything missing throws InvalidAttachmentData. Fall back to a
            // file send if we can't produce them.
            if (processed != null && processed.width > 0u && processed.height > 0u &&
                processed.blurhash != null
            ) {
                val imageParams = UploadParameters(
                    source = UploadSource.Data(bytes = processed.data,
                                               filename = attachment.filename),
                    caption = null, formattedCaption = null, mentions = null,
                    inReplyTo = inReplyTo)
                val thumbSource: UploadSource? = processed.thumbnail?.let {
                    UploadSource.Data(bytes = it.data, filename = "thumbnail.jpg")
                }
                val info = ImageInfo(
                    height = processed.height,
                    width = processed.width,
                    mimetype = processed.mimetype,
                    size = processed.data.size.toULong(),
                    thumbnailInfo = processed.thumbnail?.let {
                        ThumbnailInfo(height = it.height, width = it.width,
                                      mimetype = it.mimetype, size = it.data.size.toULong())
                    },
                    thumbnailSource = null,
                    blurhash = processed.blurhash,
                    isAnimated = null,
                )
                Log.d("SENDDBG", "sending image ${attachment.filename} " +
                    "${processed.width}x${processed.height} ${processed.mimetype} " +
                    "${processed.data.size}B")
                try {
                    val handle = timeline.sendImage(imageParams, thumbSource, info)
                    handle.join()
                    return
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.d("SENDDBG", "image upload failed: $error")
                    restageFailedUpload(attachment)
                    return
                }
            }
        }

        // Everything else: a plain file send.
        val info = FileInfo(
            mimetype = mimetype ?: "application/octet-stream",
            size = data.size.toULong(),
            thumbnailInfo = null,
            thumbnailSource = null,
        )
        Log.d("SENDDBG", "sending file ${attachment.filename} " +
            "${mimetype ?: "octet-stream"} ${data.size}B")
        try {
            val handle = timeline.sendFile(params, info)
            handle.join()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.d("SENDDBG", "file upload failed: $error")
            restageFailedUpload(attachment)
        }
    }

    /**
     * Puts a failed upload back in the composer as an errored chip and
     * surfaces the failure. A join() that threw because the user cancelled
     * is not a failure — drop those bytes quietly.
     */
    private fun restageFailedUpload(attachment: PendingAttachment) {
        val cancelledAt = lastUploadCancelAt
        if (cancelledAt != null && System.currentTimeMillis() - cancelledAt < 3_000) {
            lastUploadCancelAt = null
            presentComposerError("Upload cancelled")
            return
        }
        _pendingAttachments.value =
            _pendingAttachments.value + attachment.copy(uploadFailed = true)
        presentComposerError("Couldn't upload ${attachment.filename}")
    }

    /**
     * Stamped by `cancelSend` so the aborted upload's join() throw reads as
     * a deliberate cancel, not a re-stageable failure.
     */
    private var lastUploadCancelAt: Long? = null

    // MARK: Retry / cancel sends

    /**
     * Retries a failed local echo via `SendHandle.tryResend()`; when no
     * handle survives (queue rebuilt, e.g. after relaunch), redacts the
     * failed echo and resends the captured text body.
     */
    fun retrySend(message: MessageItem) {
        if (message.sendState != MessageItem.SendState.FAILED) return
        val provider = message.shieldProvider?.provider
        scope.launch {
            // The failure that marked this echo also disabled the room's send
            // queue; re-enable or the retry sits queued forever.
            service?.enableAllSendQueues()
            // If a .set diff has since flipped this echo to sent, retrying
            // would redact a real message and duplicate it.
            if (currentSendState(message) != MessageItem.SendState.FAILED) return@launch
            val handle = runCatching { provider?.getSendHandle() }.getOrNull()
            if (handle != null) {
                try {
                    handle.tryResend()
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.d("SENDDBG", "tryResend failed: $error")
                }
            }
            val body = (message.kind as? MessageItem.Kind.Text)?.body ?: return@launch
            message.ffiItemId?.let { itemId ->
                runCatching { timeline?.redactEvent(itemId, null) }
            }
            sendText(body, useComposerTargets = false)
        }
    }

    /**
     * The live send state of the entry backing `message` — row-captured
     * values go stale while dialogs are open.
     */
    private fun currentSendState(message: MessageItem): MessageItem.SendState? {
        for (entry in entriesList.asReversed()) {
            val m = (entry as? TimelineEntry.Message)?.item ?: continue
            if (m.id == message.id) return m.sendState
        }
        return null
    }

    /**
     * Whether an in-flight send still has an abortable handle; gates the
     * row's "Cancel Upload" menu item.
     */
    fun canCancelSend(message: MessageItem): Boolean =
        message.sendState == MessageItem.SendState.SENDING &&
            runCatching { message.shieldProvider?.provider?.getSendHandle() }.getOrNull() != null

    /**
     * Aborts an in-flight send. `SendHandle.abort()` covers text and media
     * echoes; if the event already left the queue it no-ops.
     */
    fun cancelSend(message: MessageItem) {
        if (message.sendState != MessageItem.SendState.SENDING) return
        val handle = runCatching { message.shieldProvider?.provider?.getSendHandle() }
            .getOrNull() ?: return
        lastUploadCancelAt = System.currentTimeMillis()
        scope.launch { runCatching { handle.abort() } }
    }

    /**
     * Set when the member fetch fails (offline, federation error), so the
     * member list can show a retry instead of an eternal spinner.
     */
    private val _membersLoadFailed = MutableStateFlow(false)
    val membersLoadFailed: StateFlow<Boolean> = _membersLoadFailed

    /** Loads the joined-member list (once per room visit). */
    suspend fun loadMembers(force: Boolean = false) {
        if (!force && _members.value.isNotEmpty()) return
        _membersLoadFailed.value = false
        val iterator = runCatching { room.members() }.getOrNull()
        if (iterator == null) {
            _membersLoadFailed.value = true
            return
        }
        // Drain, map, and sort off-main: nextChunk is a synchronous FFI call
        // and the sort is O(n log n) over potentially thousands of members.
        val sorted = withContext(Dispatchers.Default) {
            data class RawMember(
                val id: String,
                val displayName: String?,
                val avatarUrl: String?,
                val role: RoomMemberRole,
                val powerLevel: Int,
            )

            val all = mutableListOf<RawMember>()
            while (true) {
                val chunk = iterator.nextChunk(500u) ?: break
                all.addAll(chunk
                    .filter { it.membership is MembershipState.Join && !it.isServiceMember }
                    .map { member ->
                        val level = when (val power = member.powerLevel) {
                            is PowerLevel.Infinite -> Int.MAX_VALUE
                            is PowerLevel.Value -> power.value.toInt()
                        }
                        RawMember(id = member.userId, displayName = member.displayName,
                                  avatarUrl = member.avatarUrl,
                                  role = member.suggestedRoleForPowerLevel,
                                  powerLevel = level)
                    })
            }
            all.sortWith(compareByDescending<RawMember> { it.powerLevel }
                .thenComparator { a, b ->
                    (a.displayName ?: a.id).compareTo(b.displayName ?: b.id, ignoreCase = true)
                })
            all.map {
                MemberItem(
                    id = it.id,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl,
                    role = when (it.role) {
                        RoomMemberRole.CREATOR -> MemberItem.Role.CREATOR
                        RoomMemberRole.ADMINISTRATOR -> MemberItem.Role.ADMINISTRATOR
                        RoomMemberRole.MODERATOR -> MemberItem.Role.MODERATOR
                        RoomMemberRole.USER -> MemberItem.Role.MEMBER
                    },
                    powerLevel = it.powerLevel,
                )
            }
        }
        _members.value = sorted
        _membersById.value = sorted.associateBy { it.id }
        loadPowerLevelTags()
    }

    // MARK: Named roles (in.cinny.room.power_level_tags)

    private val _powerLevelTags = MutableStateFlow<Map<Int, PowerLevelTag>>(emptyMap())
    val powerLevelTags: StateFlow<Map<Int, PowerLevelTag>> = _powerLevelTags

    private suspend fun loadPowerLevelTags() {
        val content = service?.stateEventContent(
            roomId = roomId, type = PowerLevelTags.eventType) ?: return
        _powerLevelTags.value = PowerLevelTags.parse(content)
    }

    /** The named role for a power level — the room's tag, or a default label. */
    fun roleTag(forLevel: Int): PowerLevelTag =
        PowerLevelTags.displayTag(forLevel, _powerLevelTags.value)

    /** Writes the whole tag map. Returns an error message on failure. */
    suspend fun savePowerLevelTags(tags: Map<Int, PowerLevelTag>): String? = try {
        val json = PowerLevelTags.content(tags).toString()
        room.sendStateEventRaw(PowerLevelTags.eventType, "", json)
        _powerLevelTags.value = tags
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        error.message ?: "Couldn't save roles"
    }

    /** Opens (or creates) a DM with a member; returns the room ID to select. */
    suspend fun startDm(userId: String): String? =
        runCatching { service?.startDm(userId) }.getOrNull()

    fun toggleReaction(key: String, message: MessageItem) {
        val timeline = this.timeline ?: return
        val itemId = message.ffiItemId ?: return
        // Usage feeds the quick-reaction palette, which only rasterises
        // unicode emoji — keep custom-emote keys out.
        if (!key.startsWith("mxc://")) {
            ReactionUsage.record(appContext, key)
        }
        scope.launch { runCatching { timeline.toggleReaction(itemId, key) } }
    }

    fun redact(message: MessageItem) {
        val timeline = this.timeline ?: return
        val itemId = message.ffiItemId ?: return
        scope.launch { runCatching { timeline.redactEvent(itemId, null) } }
    }

    // MARK: Polls

    suspend fun createPoll(question: String, answers: List<String>, disclosed: Boolean) {
        val timeline = this.timeline ?: return
        runCatching {
            timeline.createPoll(question, answers, 1u.toUByte(),
                                if (disclosed) PollKind.DISCLOSED else PollKind.UNDISCLOSED)
        }
    }

    fun votePoll(message: MessageItem, answerId: String) {
        val timeline = this.timeline ?: return
        val eventId = message.eventId ?: return
        scope.launch {
            runCatching { timeline.sendPollResponse(eventId, listOf(answerId)) }
        }
    }

    fun endPoll(message: MessageItem) {
        val timeline = this.timeline ?: return
        val eventId = message.eventId ?: return
        scope.launch {
            runCatching { timeline.endPoll(eventId, "The poll has ended.") }
        }
    }

    // MARK: Voice messages

    suspend fun sendVoiceMessage(recording: VoiceRecording) {
        val timeline = this.timeline ?: return
        val params = UploadParameters(
            source = UploadSource.Data(bytes = recording.data, filename = "voice-message.m4a"),
            caption = null,
            formattedCaption = null,
            mentions = null,
            inReplyTo = null,
        )
        val info = AudioInfo(duration = Duration.ofMillis((recording.duration * 1000).toLong()),
                             size = recording.data.size.toULong(),
                             mimetype = "audio/mp4")
        try {
            val handle = timeline.sendVoiceMessage(params, info, recording.waveform)
            handle.join()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.d("SENDDBG", "voice upload failed: $error")
            presentComposerError("Couldn't send voice message")
        }
    }

    // MARK: Location

    suspend fun shareCurrentLocation() {
        val timeline = this.timeline ?: return
        try {
            val location = currentLocation() ?: throw IllegalStateException("no location")
            timeline.sendLocation(
                "Shared location",
                "geo:${location.latitude},${location.longitude}",
                null,
                15u.toUByte(),
                AssetType.SENDER,
                null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.d("SENDDBG", "location share failed: $error")
            presentComposerError("Couldn't share your location")
        }
    }

    /**
     * One-shot current location (the CLLocationUpdate.liveUpdates first-fix
     * analogue). Requires the location permission, requested by the UI first;
     * a SecurityException surfaces as the same composer error.
     */
    private suspend fun currentLocation(): Location? {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = when {
            Build.VERSION.SDK_INT >= 31 &&
                manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        return suspendCancellableCoroutine { continuation ->
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.getCurrentLocation(provider, null, appContext.mainExecutor) {
                        if (continuation.isActive) continuation.resume(it)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }, appContext.mainLooper)
                }
            } catch (error: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    // MARK: Stickers
    // Thin wrappers over StickerSender (kept standalone because `room` is
    // private here); both are raw `m.sticker` sends via room.sendRaw.

    suspend fun sendSticker(sticker: StickerStore.Sticker) =
        StickerSender.send(room, sticker, appContext)

    suspend fun sendSticker(emote: CustomEmojiStore.Emote, mediaLoader: MediaLoader) =
        StickerSender.send(room, emote, mediaLoader)

    // MARK: Typing notices (outgoing)

    /**
     * Called on every keystroke; throttled to one notice per 4s, with an
     * automatic "stopped typing" after 6s idle.
     */
    fun composerIsTyping() {
        if (!preferences.value.sendTypingNotifications) return
        typingStopJob?.cancel()
        val last = lastTypingNotice
        if (last == null || System.currentTimeMillis() - last > 4_000) {
            lastTypingNotice = System.currentTimeMillis()
            scope.launch { runCatching { room.typingNotice(true) } }
        }
        typingStopJob = scope.launch {
            delay(6_000)
            sendTypingNotice(false)
        }
    }

    fun sendTypingNotice(isTyping: Boolean) {
        if (!preferences.value.sendTypingNotifications) return
        typingStopJob?.cancel()
        lastTypingNotice = if (isTyping) System.currentTimeMillis() else null
        scope.launch { runCatching { room.typingNotice(isTyping) } }
    }

    /** Consecutive pagination failures, driving the retry backoff below. */
    private var paginateFailureCount = 0
    private var lastPaginateFailure: Long? = null

    suspend fun paginateBackwards() {
        val timeline = this.timeline ?: return
        if (_isPaginating.value || _reachedStart.value || isParked) return
        // The view's sentinel polls every second, hammering the network while
        // offline. Exponential 1,2,4,…30s gate, reset on the first success.
        val last = lastPaginateFailure
        if (paginateFailureCount > 0 && last != null) {
            val delaySeconds = min(30.0, 2.0.pow(paginateFailureCount - 1))
            if (System.currentTimeMillis() - last < delaySeconds * 1000) return
        }
        _isPaginating.value = true
        try {
            _reachedStart.value = timeline.paginateBackwards(50u.toUShort())
            paginateFailureCount = 0
            lastPaginateFailure = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Transient (e.g. offline); the sentinel retries after the backoff.
            paginateFailureCount++
            lastPaginateFailure = System.currentTimeMillis()
        } finally {
            _isPaginating.value = false
        }
    }

    /**
     * Event ids whose reply details we've already asked the SDK to load, so a
     * message that stays pending isn't re-fetched on every diff.
     */
    private val fetchedReplyDetails = mutableSetOf<String>()

    /**
     * Loads the replied-to event for any message whose reply preview is still
     * unresolved. On completion the SDK emits a timeline update with the
     * details ready, so the snippet fills in instead of showing just "…".
     */
    private fun fetchPendingReplyDetails() {
        val timeline = this.timeline ?: return
        for (entry in entriesList) {
            val message = (entry as? TimelineEntry.Message)?.item ?: continue
            val eventId = message.eventId ?: continue
            if (message.replyPreview?.isPending != true) continue
            if (!fetchedReplyDetails.add(eventId)) continue
            scope.launch { runCatching { timeline.fetchDetailsForEvent(eventId) } }
        }
    }

    /**
     * True `userId -> eventId` read positions, polled from the parallel /sync
     * long-poll (the SDK's timeline mis-places receipts on the newest event).
     * Empty until the first poll lands.
     */
    private val explicitReceipts = mutableMapOf<String, String>()
    private var ephemeralSyncJob: Job? = null

    /**
     * Overrides each message's receipt list with the true positions, so a
     * reader shows on the exact event they read — including the newest one,
     * which the SDK otherwise leaves a message behind. No-op until polled.
     */
    private fun applyExplicitReceipts() {
        if (explicitReceipts.isEmpty()) return
        // Invert once per call — a per-entry dictionary filter was
        // O(entries × receipts) on every diff batch.
        val readersByEvent = mutableMapOf<String, MutableList<String>>()
        for ((userId, eventId) in explicitReceipts) {
            if (userId == ownUserId) continue
            readersByEvent.getOrPut(eventId) { mutableListOf() }.add(userId)
        }
        for (readers in readersByEvent.values) readers.sort()
        var changed = false
        for (i in entriesList.indices) {
            val message = (entriesList[i] as? TimelineEntry.Message)?.item ?: continue
            val eventId = message.eventId ?: continue
            val readers = readersByEvent[eventId] ?: emptyList()
            if (message.readReceiptUserIds != readers) {
                entriesList[i] = TimelineEntry.Message(message.copy(readReceiptUserIds = readers))
                changed = true
            }
        }
        if (changed) publishEntries()
    }

    /**
     * Streams the open room's ephemerals (receipts + typing) via a parallel
     * long-poll `/sync`, because the SDK's sliding-sync path mis-places
     * receipts on the newest event and its ephemeral updates don't surface
     * live. Initial call snapshots the full state; each subsequent long-poll
     * blocks until something changes, so updates are effectively instant.
     */
    private fun startEphemeralSync() {
        ephemeralSyncJob?.cancel()
        if (mode != Mode.Live) return
        ephemeralSyncJob = scope.launch {
            var since: String? = null
            while (isActive) {
                if (isParked) break
                val service = service ?: break
                val result = service.fetchRoomEphemerals(roomId = roomId, since = since)
                if (result == null) {
                    delay(3_000)
                    continue
                }
                since = result.nextBatch
                var receiptsChanged = false
                for ((userId, eventId) in result.receipts) {
                    if (explicitReceipts[userId] != eventId) {
                        explicitReceipts[userId] = eventId
                        receiptsChanged = true
                    }
                }
                if (receiptsChanged) applyExplicitReceipts()

                val typing = result.typing
                if (typing != null) {
                    val others = typing.filter { it != ownUserId }
                    _typingUsers.value = others
                    typingExpiryJob?.cancel()
                    if (others.isNotEmpty()) {
                        typingExpiryJob = scope.launch {
                            delay(12_000)
                            _typingUsers.value = emptyList()
                        }
                    }
                }
            }
        }
    }

    /**
     * Pauses the per-room ephemeral long-poll (app backgrounded / window
     * closed); `resumeEphemeralSync` restarts it with a fresh snapshot.
     */
    fun pauseEphemeralSync() {
        ephemeralSyncJob?.cancel()
        ephemeralSyncJob = null
    }

    fun resumeEphemeralSync() {
        if (mode != Mode.Live || timeline == null || isParked || ephemeralSyncJob != null) return
        startEphemeralSync()
    }

    /**
     * Whether the "NEW" divider row is currently on screen. Observable (unlike
     * `visibleEntryIds`) so the jump-to-unread pill re-evaluates as the marker
     * scrolls in and out of view.
     */
    private val _unreadMarkerOnScreen = MutableStateFlow(false)
    val unreadMarkerOnScreen: StateFlow<Boolean> = _unreadMarkerOnScreen

    fun setUnreadMarkerOnScreen(onScreen: Boolean) {
        _unreadMarkerOnScreen.value = onScreen
    }

    /**
     * Updates the read-marker and (re)arms the auto-dismissing pill. Only a
     * marker we haven't already dismissed shows, and only for a few seconds.
     */
    private fun setUnreadMarker(marker: String?) {
        if (marker == _firstUnreadMarkerId.value) return
        _firstUnreadMarkerId.value = marker
        // A different (or cleared) marker hasn't been seen on screen yet; its
        // row's appear callback re-sets this if it's already visible.
        setUnreadMarkerOnScreen(false)
        if (marker == null) {
            unreadDismissJob?.cancel()
            _unreadMarkerVisible.value = false
            return
        }
        // Already seen this one (e.g. returning to the room): stay hidden.
        if (marker == dismissedMarkerId) {
            _unreadMarkerVisible.value = false
            return
        }
        _unreadMarkerVisible.value = true
        unreadDismissJob?.cancel()
        unreadDismissJob = scope.launch {
            delay(5_000)
            dismissedMarkerId = marker
            _unreadMarkerVisible.value = false
        }
    }

    /**
     * Hides the pill immediately (you've caught up), and remembers it as seen
     * so it won't reappear when you return to the room.
     */
    private fun dismissUnreadMarker() {
        unreadDismissJob?.cancel()
        dismissedMarkerId = _firstUnreadMarkerId.value
        _unreadMarkerVisible.value = false
    }

    fun markAsRead() {
        dismissUnreadMarker()
        if (isParked || mode != Mode.Live) return
        val timeline = this.timeline ?: return
        // The bottom sentinel calls this on every scroll flip; re-send only
        // for a newer event than last acknowledged, with a short cool-down
        // when no event ID is available yet.
        var latestEventId: String? = null
        for (entry in entriesList.asReversed()) {
            val id = (entry as? TimelineEntry.Message)?.item?.eventId
            if (id != null) {
                latestEventId = id
                break
            }
        }
        val now = System.currentTimeMillis()
        if (latestEventId != null && latestEventId == lastMarkedReadEventId) return
        val lastAt = lastMarkedReadAt
        if (latestEventId == null && lastAt != null && now - lastAt < 2_000) return
        lastMarkedReadEventId = latestEventId
        lastMarkedReadAt = now
        val sendReceipt = preferences.value.sendReadReceipts
        scope.launch {
            try {
                // With receipts off, don't tell the server — but still clear
                // the local unread flag so the sidebar pip drops.
                if (sendReceipt) {
                    timeline.markAsRead(ReceiptType.READ)
                }
                // Also drop the manual "mark unread" flag; the receipt alone
                // doesn't, leaving the sidebar pip lit after reading.
                runCatching { room.setUnreadFlag(false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Un-commit the debounce so the next appearance retries;
                // otherwise an offline failure sticks until a newer event.
                resetMarkAsReadDebounce(ifStill = latestEventId)
            }
        }
    }

    private fun resetMarkAsReadDebounce(ifStill: String?) {
        if (lastMarkedReadEventId == ifStill) {
            lastMarkedReadEventId = null
            lastMarkedReadAt = null
        }
    }

    // MARK: Diff application

    /**
     * Pre-materialized entries for one diff, built off-main so the per-item
     * `TimelineEntry.from` FFI walk (~15+ JNI getters each) never runs on the
     * input thread. Positional diffs carry a single entry; bulk diffs a list;
     * removals carry nothing. `apply` consumes this in the same order.
     */
    private class MappedDiff(
        val values: List<TimelineEntry>? = null,
        val value: TimelineEntry? = null,
    )

    /** Runs the FFI-heavy `TimelineEntry.from` map off-main for one diff. */
    private fun materializeDiff(diff: TimelineDiff): MappedDiff = when (diff) {
        is TimelineDiff.Append ->
            MappedDiff(values = diff.values.map { TimelineEntry.from(it, ownUserId) })
        is TimelineDiff.PushFront -> MappedDiff(value = TimelineEntry.from(diff.value, ownUserId))
        is TimelineDiff.PushBack -> MappedDiff(value = TimelineEntry.from(diff.value, ownUserId))
        is TimelineDiff.Insert -> MappedDiff(value = TimelineEntry.from(diff.value, ownUserId))
        is TimelineDiff.Set -> MappedDiff(value = TimelineEntry.from(diff.value, ownUserId))
        is TimelineDiff.Reset ->
            MappedDiff(values = diff.values.map { TimelineEntry.from(it, ownUserId) })
        else -> MappedDiff()
    }

    private fun apply(diffs: List<TimelineDiff>, mapped: List<MappedDiff>) {
        var appendedAtBottom = false
        // Grouping depends only on the entry above, so regroup the smallest
        // neighborhood a batch touched:
        //  • `.set` batches (reactions, receipts, edits) → the set rows.
        //  • pure-append batches (the hot path) → the new tail.
        //  • any other positional diff (indices shift) → full regroup.
        var needsFullRegroup = false
        var appendStart: Int? = null
        val setIndices = mutableListOf<Int>()
        for ((diffIndex, diff) in diffs.withIndex()) {
            val mappedDiff = mapped[diffIndex]
            when (diff) {
                is TimelineDiff.Append -> {
                    val start = entriesList.size
                    entriesList.addAll(mappedDiff.values.orEmpty())
                    appendedAtBottom = true
                    if (appendStart == null) appendStart = start
                }
                is TimelineDiff.Clear -> {
                    entriesList.clear()
                    // The SDK sometimes rebuilds down to the sync window; reopen
                    // pagination so the sentinel refills the dropped history.
                    _reachedStart.value = false
                    // Crypto state may have moved; let rows refetch their shields.
                    _shields.value = emptyMap()
                    shieldsRequested.clear()
                    needsFullRegroup = true
                }
                is TimelineDiff.PushFront -> {
                    mappedDiff.value?.let { entriesList.add(0, it) }
                    needsFullRegroup = true
                }
                is TimelineDiff.PushBack -> {
                    val start = entriesList.size
                    mappedDiff.value?.let { entriesList.add(it) }
                    appendedAtBottom = true
                    if (appendStart == null) appendStart = start
                }
                is TimelineDiff.PopFront -> {
                    if (entriesList.isNotEmpty()) entriesList.removeAt(0)
                    needsFullRegroup = true
                }
                is TimelineDiff.PopBack -> {
                    if (entriesList.isNotEmpty()) entriesList.removeAt(entriesList.size - 1)
                    needsFullRegroup = true
                }
                is TimelineDiff.Insert -> {
                    val i = diff.index.toInt().coerceIn(0, entriesList.size)
                    mappedDiff.value?.let { entriesList.add(i, it) }
                    needsFullRegroup = true
                }
                is TimelineDiff.Set -> {
                    val i = diff.index.toInt()
                    if (i !in entriesList.indices) continue
                    val entry = mappedDiff.value ?: continue
                    entriesList[i] = entry
                    // Re-arm the shield fetch: a .set can follow a verification
                    // change. Offscreen rows refetch via their appear effect;
                    // visible rows won't (same event id), so kick those directly.
                    (entriesList[i] as? TimelineEntry.Message)?.item?.let { m ->
                        m.eventId?.let { eid ->
                            shieldsRequested.remove(eid)
                            if (visibleEntryIds.contains(entriesList[i].id)) {
                                loadShieldIfNeeded(m)
                            }
                        }
                    }
                    setIndices.add(i)
                }
                is TimelineDiff.Remove -> {
                    val i = diff.index.toInt()
                    if (i !in entriesList.indices) continue
                    entriesList.removeAt(i)
                    needsFullRegroup = true
                }
                is TimelineDiff.Truncate -> {
                    val length = diff.length.toInt()
                    while (entriesList.size > length) entriesList.removeAt(entriesList.size - 1)
                    needsFullRegroup = true
                }
                is TimelineDiff.Reset -> {
                    val hadMore = entriesList.size
                    entriesList.clear()
                    entriesList.addAll(mappedDiff.values.orEmpty())
                    // Same window-rebuild case as .clear: don't strand the user
                    // with less history than they had loaded.
                    if (entriesList.size < hadMore) {
                        _reachedStart.value = false
                    }
                    // Unpark: land back on the pre-park scroll anchor if it's here.
                    val anchor = pendingUnparkAnchor
                    if (anchor != null) {
                        pendingUnparkAnchor = null
                        if (entriesList.any {
                                (it as? TimelineEntry.Message)?.item?.eventId == anchor
                            }
                        ) {
                            _unparkScrollTarget.value = anchor
                        }
                    }
                    _shields.value = emptyMap()
                    shieldsRequested.clear()
                    needsFullRegroup = true
                }
            }
        }
        if (needsFullRegroup) {
            regroup()
        } else {
            // Ascending order so each appended row's predecessor is already
            // finalized; each `.set` row also re-checks the row below it
            // (whose predecessor changed).
            val dirty = sortedSetOf<Int>()
            appendStart?.let { start -> for (i in start until entriesList.size) dirty.add(i) }
            for (i in setIndices) {
                dirty.add(i)
                dirty.add(i + 1)
            }
            for (i in dirty) regroupAt(i)
        }
        updateLastOwnMessageId()
        val marker = entriesList.firstOrNull { it is TimelineEntry.ReadMarker }?.id
        setUnreadMarker(marker)
        applyExplicitReceipts()
        fetchPendingReplyDetails()
        publishEntries()
        if (appendedAtBottom && isAtBottom) {
            markAsRead()
        }
    }

    /** Grouping window in milliseconds, from the preference in minutes. */
    private val groupingWindowMillis: Long
        get() = preferences.value.groupingWindowMinutes * 60_000L

    /**
     * A message shows its header (avatar + name + time) unless it directly
     * follows another from the same sender within the grouping window.
     */
    private fun regroup() {
        val window = groupingWindowMillis
        var previous: MessageItem? = null
        for (index in entriesList.indices) {
            val message = (entriesList[index] as? TimelineEntry.Message)?.item
            if (message == null) {
                previous = null
                continue
            }
            val grouped = previous?.let {
                it.sender == message.sender && message.timestamp - it.timestamp < window
            } ?: false
            if (message.showsHeader != !grouped) {
                entriesList[index] = TimelineEntry.Message(message.copy(showsHeader = !grouped))
            }
            previous = message
        }
    }

    /**
     * Single-index `regroup` for in-place `.set` diffs: a row's header flag
     * depends only on the entry directly above it.
     */
    private fun regroupAt(index: Int) {
        if (index !in entriesList.indices) return
        val message = (entriesList[index] as? TimelineEntry.Message)?.item ?: return
        val previous = if (index > 0) {
            (entriesList[index - 1] as? TimelineEntry.Message)?.item
        } else {
            null
        }
        val grouped = previous?.let {
            it.sender == message.sender && message.timestamp - it.timestamp < groupingWindowMillis
        } ?: false
        if (message.showsHeader != !grouped) {
            entriesList[index] = TimelineEntry.Message(message.copy(showsHeader = !grouped))
        }
    }

    // MARK: Bridges

    private class DiffBridge : TimelineListener {
        val channel = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
        override fun onUpdate(diff: List<TimelineDiff>) {
            channel.trySend(diff)
        }
    }

    private class TypingBridge : TypingNotificationsListener {
        val channel = Channel<List<String>>(Channel.UNLIMITED)
        override fun call(typingUserIds: List<String>) {
            channel.trySend(typingUserIds)
        }
    }

    private class RoomInfoBridge : RoomInfoListener {
        val channel = Channel<RoomInfo>(Channel.UNLIMITED)
        override fun call(roomInfo: RoomInfo) {
            channel.trySend(roomInfo)
        }
    }

    companion object {
        /**
         * Listener teardown outlives the view model that owned the handle: an
         * evicted view model still has to release its Rust subscription, and
         * `scope` is Main.immediate. Process-lifetime and never cancelled, so a
         * detach can never be dropped on the floor.
         */
        private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        /**
         * Wraps each mention's `@user:server` token in `base` with a matrix.to
         * anchor. Longest tokens first so a shorter id can't match inside a longer
         * one. `base` is already HTML-escaped.
         */
        private fun mentionHtml(base: String, mentions: List<MentionRef>): String {
            var html = base
            for (mention in mentions.sortedByDescending { it.text.length }) {
                val escaped = htmlEscape(mention.text)
                val anchor = "<a href=\"https://matrix.to/#/${mention.userId}\">$escaped</a>"
                html = html.replace(escaped, anchor)
            }
            return html
        }
    }
}
