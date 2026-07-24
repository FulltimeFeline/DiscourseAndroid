package com.riiiiiiiley.discourse.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.riiiiiiiley.discourse.models.RoomSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts local notifications for incoming messages, suppressing them for the
 * focused room and own messages, and routes taps/actions back into the app —
 * the port of the iOS NotificationManager (UNUserNotificationCenter local
 * banners; remote push is a separate, still-pending phase).
 */
object NotificationManager {

    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_MESSAGES_SILENT = "messages_silent"

    const val EXTRA_ACTION = "notification.action"
    const val EXTRA_ROOM_ID = "roomId"
    const val EXTRA_EVENT_ID = "eventId"
    const val EXTRA_ACCOUNT_USER_ID = "userId"
    const val ACTION_OPEN = "OPEN"
    const val ACTION_REPLY = "REPLY"
    const val ACTION_MARK_READ = "MARK_READ"
    const val REMOTE_INPUT_KEY = "reply_text"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var preferences: Preferences? = null

    /**
     * Shows a decrypted push (from UnifiedPush / [PushRegistrar]) as a local
     * notification, reusing the same delivery path as sync-driven ones. Skips
     * the room currently on screen and accounts the user disabled.
     */
    fun showPush(
        context: Context,
        roomId: String,
        eventId: String,
        accountUserId: String,
        title: String,
        subtitle: String?,
        body: String,
        avatarUrl: String?,
        noisy: Boolean,
    ) {
        if (appContext == null) appContext = context.applicationContext
        // Cold FCM process: activate() never ran, so make sure the channels exist.
        ensureChannels(context)
        if (roomId == focusedRoomId && Platform.isAppActive) return
        if (!notificationsEnabled(accountUserId)) return
        deliver(
            tag = eventId.ifBlank { roomId },
            roomId = roomId,
            accountUserId = accountUserId,
            title = title,
            subtitle = subtitle,
            body = body,
            avatarUrl = avatarUrl,
            withActions = true,
        )
    }

    /**
     * Room open in the main window; its notifications are suppressed while
     * active and its delivered banners cleared when opened.
     */
    var focusedRoomId: String? = null
        set(value) {
            field = value
            if (value != null) clearDelivered(value)
        }

    /**
     * Opens a room on tap. Handler may need to switch accounts first, since
     * every warm account's sync notifies.
     */
    var openRoom: ((roomId: String, eventId: String?, accountUserId: String?) -> Unit)? = null
        set(value) {
            field = value
            drainPendingActions()
        }
    var sendReply: ((roomId: String, text: String, accountUserId: String?) -> Unit)? = null
        set(value) {
            field = value
            drainPendingActions()
        }
    var markRoomRead: ((roomId: String, accountUserId: String?) -> Unit)? = null
        set(value) {
            field = value
            drainPendingActions()
        }

    /** Actions that arrived before the session wired its handlers (cold launch). */
    private sealed interface PendingAction {
        data class Reply(val roomId: String, val text: String, val accountUserId: String?) : PendingAction
        data class MarkRead(val roomId: String, val accountUserId: String?) : PendingAction
        data class Open(val roomId: String, val eventId: String?, val accountUserId: String?) : PendingAction
    }

    private val pendingActions = mutableListOf<PendingAction>()

    private fun drainPendingActions() {
        if (pendingActions.isEmpty()) return
        val drained = pendingActions.toList()
        pendingActions.clear()
        for (action in drained) {
            when (action) {
                is PendingAction.Reply -> {
                    val handler = sendReply
                    if (handler != null) handler(action.roomId, action.text, action.accountUserId)
                    else pendingActions.add(action)
                }
                is PendingAction.MarkRead -> {
                    val handler = markRoomRead
                    if (handler != null) handler(action.roomId, action.accountUserId)
                    else pendingActions.add(action)
                }
                is PendingAction.Open -> {
                    val handler = openRoom
                    if (handler != null) handler(action.roomId, action.eventId, action.accountUserId)
                    else pendingActions.add(action)
                }
            }
        }
    }

    var onIncomingCall: ((RoomSummary) -> Unit)? = null
    var onCallEnded: ((String) -> Unit)? = null

    /**
     * Resolves an avatar (mxc URL) to a bitmap for the given account, so a
     * notification can show the room/sender pfp. Set by the app.
     */
    var loadAvatar: (suspend (mxcUrl: String, accountUserId: String) -> Bitmap?)? = null

    /**
     * The label to show for which account a notification is for, or null to
     * omit it (e.g. only one account signed in). Set by the app.
     */
    var accountLabel: ((accountUserId: String) -> String?)? = null

    private val lastNotified = mutableMapOf<String, Long>()
    private val lastCallActive = mutableMapOf<String, Boolean>()
    private val invitesNotified = mutableSetOf<String>()

    private val isAuthorized: Boolean
        get() {
            val context = appContext ?: return false
            if (android.os.Build.VERSION.SDK_INT < 33) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** Creates the channels; the POST_NOTIFICATIONS prompt is the activity's. */
    fun activate(context: Context, preferences: Preferences) {
        appContext = context.applicationContext
        this.preferences = preferences
        ensureChannels(context)
    }

    /**
     * Creates the notification channels (idempotent). Called from [activate] and
     * from the push path, so a cold FCM process — where activate never ran —
     * still has channels to post to. Channels persist once created.
     */
    fun ensureChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context.applicationContext)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply { setShowBadge(true) },
        )
        // Channel sound is immutable after creation; the notificationSound
        // preference routes through a silent sibling channel instead.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES_SILENT, "Messages (silent)",
                android.app.NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    setShowBadge(true)
                    setSound(null, null)
                    enableVibration(false)
                },
        )
    }

    /** The per-account banner gate (iOS Preferences.shared.notificationsEnabled). */
    fun notificationsEnabled(forUserId: String): Boolean =
        preferences?.notificationsEnabled(forUserId) ?: true

    /** `SenderName (@account:server)` suffix so multi-account banners disambiguate. */
    private fun accountSuffix(accountUserId: String): String {
        val label = accountLabel?.invoke(accountUserId)?.takeIf { it.isNotEmpty() } ?: return ""
        return " ($label)"
    }

    fun maybeNotify(
        room: RoomSummary,
        spaceName: String? = null,
        avatarUrl: String? = null,
        accountUserId: String,
    ) {
        if (!isAuthorized) return
        if (room.lastMessageIsOwn) return
        if (room.unreadNotifications == 0uL) return
        val timestamp = room.lastActivity ?: return
        val preview = room.lastMessagePreview ?: return
        // Don't re-notify for the same message, and skip the room on screen.
        if (timestamp <= (lastNotified[room.id] ?: Long.MIN_VALUE)) return
        if (Platform.isAppActive) {
            // The user is in the app, so don't post a sync banner — this also
            // stops the catch-up sync on open from re-notifying messages that
            // already arrived as push while the app was closed. Mark handled so
            // a later room-list refresh can't notify for it either.
            lastNotified[room.id] = timestamp
            return
        }
        // Ignore stale events delivered during initial sync/backfill.
        if (System.currentTimeMillis() - timestamp > 120_000) return
        lastNotified[room.id] = timestamp

        // How much of who/what to reveal on the lock screen.
        val previewLevel = preferences?.value?.notificationPreview ?: NotificationPreview.FULL
        var title: String
        var subtitle: String? = null
        var body: String
        when (previewLevel) {
            NotificationPreview.FULL, NotificationPreview.NAME_ONLY -> {
                if (room.isDirect) {
                    title = room.lastMessageSenderName ?: room.name
                } else {
                    title = spaceName?.let { "$it › ${room.name}" } ?: room.name
                    subtitle = room.lastMessageSenderName
                }
                body = if (previewLevel == NotificationPreview.FULL) preview else "New message"
            }
            NotificationPreview.HIDDEN -> {
                title = "Discourse"
                body = "New notification"
            }
        }
        if (subtitle != null) subtitle += accountSuffix(accountUserId)
        else title += accountSuffix(accountUserId)

        deliver(
            tag = "${room.id}-$timestamp",
            roomId = room.id,
            accountUserId = accountUserId,
            title = title,
            subtitle = subtitle,
            body = body,
            avatarUrl = avatarUrl,
            withActions = true,
        )
    }

    fun maybeNotifyCall(room: RoomSummary, avatarUrl: String? = null, accountUserId: String) {
        val wasActive = lastCallActive[room.id] ?: false
        lastCallActive[room.id] = room.hasActiveCall
        if (wasActive && !room.hasActiveCall) {
            onCallEnded?.invoke(room.id)
            // Otherwise the stale "Call started" banner lingers and, tapped
            // later, lands in a call-less room.
            appContext?.let { NotificationManagerCompat.from(it).cancel("call-${room.id}", 1) }
        }
        if (!room.hasActiveCall || wasActive) return
        // Ring in-app only for 1:1 calls (and not one we started ourselves);
        // group calls are announced by a banner, not a ringtone. Rings even
        // with notifications unauthorized — only banners are gated.
        if (room.isDirect &&
            !com.riiiiiiiley.discourse.features.call.CallRegistry.localRooms.contains(room.id)
        ) {
            onIncomingCall?.invoke(room)
        }
        if (!isAuthorized) return
        if (Platform.isAppActive && focusedRoomId == room.id) return

        // A call reveals the room but no message contents; only HIDDEN hides it.
        val hidden = preferences?.value?.notificationPreview == NotificationPreview.HIDDEN
        val title = (if (hidden) "Discourse" else room.name) + accountSuffix(accountUserId)
        val body = if (hidden) "Incoming call" else "Call started — tap to join"
        deliver(
            tag = "call-${room.id}",
            roomId = room.id,
            accountUserId = accountUserId,
            title = title,
            subtitle = null,
            body = body,
            avatarUrl = avatarUrl,
            withActions = false,
        )
    }

    /** One-shot notification when an invite arrives. */
    fun maybeNotifyInvite(room: RoomSummary, avatarUrl: String? = null, accountUserId: String) {
        if (!isAuthorized || !room.isInvited || invitesNotified.contains(room.id)) return
        invitesNotified.add(room.id)

        // Inviter and room, but nothing message-like; only HIDDEN hides it.
        val hidden = preferences?.value?.notificationPreview == NotificationPreview.HIDDEN
        val title = (if (hidden) "Discourse" else room.name) + accountSuffix(accountUserId)
        val body = when {
            hidden -> "You've been invited"
            room.inviterName != null -> "${room.inviterName} invited you"
            else -> "You've been invited"
        }
        deliver(
            tag = "invite-${room.id}",
            roomId = room.id,
            accountUserId = accountUserId,
            title = title,
            subtitle = null,
            body = body,
            avatarUrl = avatarUrl,
            withActions = false,
        )
    }

    private fun deliver(
        tag: String,
        roomId: String,
        accountUserId: String,
        title: String,
        subtitle: String?,
        body: String,
        avatarUrl: String?,
        withActions: Boolean,
    ) {
        val context = appContext ?: return
        scope.launch {
            // Attach the room/sender/space pfp as the notification's icon.
            val avatar = if (avatarUrl != null) {
                runCatching { loadAvatar?.invoke(avatarUrl, accountUserId) }.getOrNull()
            } else null

            val channel = if (preferences?.value?.notificationSound != false) CHANNEL_MESSAGES
                else CHANNEL_MESSAGES_SILENT
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // Group = room id, the threadIdentifier analogue clearDelivered
                // filters on.
                .setGroup(roomId)
            subtitle?.let { builder.setSubText(it) }
            avatar?.let { builder.setLargeIcon(it) }

            val openIntent = Intent(context, com.riiiiiiiley.discourse.app.MainActivity::class.java).apply {
                action = "com.riiiiiiiley.discourse.NOTIFICATION_OPEN"
                putExtra(EXTRA_ACTION, ACTION_OPEN)
                putExtra(EXTRA_ROOM_ID, roomId)
                putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Distinct data so PendingIntents for different rooms don't collapse.
                data = android.net.Uri.parse("discourse://notification/$roomId")
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            if (withActions) {
                val remoteInput = RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel("Message")
                    .build()
                val replyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "com.riiiiiiiley.discourse.NOTIFICATION_REPLY"
                    putExtra(EXTRA_ACTION, ACTION_REPLY)
                    putExtra(EXTRA_ROOM_ID, roomId)
                    putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId)
                    data = android.net.Uri.parse("discourse://notification-reply/$roomId")
                }
                // MUTABLE: RemoteInput needs to write the typed text into it.
                val replyPending = PendingIntent.getBroadcast(
                    context, 0, replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(0, "Reply", replyPending)
                        .addRemoteInput(remoteInput)
                        .setAllowGeneratedReplies(false)
                        .build(),
                )
                val markReadIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                    action = "com.riiiiiiiley.discourse.NOTIFICATION_MARK_READ"
                    putExtra(EXTRA_ACTION, ACTION_MARK_READ)
                    putExtra(EXTRA_ROOM_ID, roomId)
                    putExtra(EXTRA_ACCOUNT_USER_ID, accountUserId)
                    data = android.net.Uri.parse("discourse://notification-read/$roomId")
                }
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0, "Mark as Read",
                        PendingIntent.getBroadcast(
                            context, 0, markReadIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    ).build(),
                )
            }

            runCatching { NotificationManagerCompat.from(context).notify(tag, 1, builder.build()) }
        }
    }

    /** Removes a room's delivered banners once it's been read. */
    fun clearDelivered(roomId: String) = clearDelivered(setOf(roomId))

    /** Batch variant: one active-notifications scan covers every room. */
    fun clearDelivered(roomIds: Set<String>) {
        if (roomIds.isEmpty()) return
        val context = appContext ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager ?: return
        runCatching {
            for (sbn in manager.activeNotifications) {
                if (sbn.notification.group in roomIds) {
                    manager.cancel(sbn.tag, sbn.id)
                }
            }
        }
    }

    /** All delivered banners (logout / notification-handler teardown). */
    fun clearAll() {
        appContext?.let { NotificationManagerCompat.from(it).cancelAll() }
        lastNotified.clear()
        lastCallActive.clear()
        invitesNotified.clear()
    }

    /** Handler teardown on logout/auth-error (iOS clears its closures too). */
    fun detachHandlers() {
        openRoom = null
        sendReply = null
        markRoomRead = null
        onIncomingCall = null
        onCallEnded = null
        loadAvatar = null
        accountLabel = null
    }

    /**
     * Routes a notification intent (activity tap or receiver action) into the
     * wired handlers; queues it if the session hasn't wired them yet.
     */
    fun handleIntent(intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val accountUserId = intent.getStringExtra(EXTRA_ACCOUNT_USER_ID)
        when (action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_KEY)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return
                val handler = sendReply
                if (handler != null) handler(roomId, text, accountUserId)
                else pendingActions.add(PendingAction.Reply(roomId, text, accountUserId))
                // The banner was actioned; retire it.
                clearDelivered(roomId)
            }
            ACTION_MARK_READ -> {
                val handler = markRoomRead
                if (handler != null) handler(roomId, accountUserId)
                else pendingActions.add(PendingAction.MarkRead(roomId, accountUserId))
                clearDelivered(roomId)
            }
            ACTION_OPEN -> {
                val handler = openRoom
                if (handler != null) handler(roomId, eventId, accountUserId)
                else pendingActions.add(PendingAction.Open(roomId, eventId, accountUserId))
            }
        }
    }
}

/** Reply / mark-as-read notification actions land here (manifest receiver). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationManager.handleIntent(intent)
    }
}
