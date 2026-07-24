package com.riiiiiiiley.discourse.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import com.google.firebase.messaging.FirebaseMessaging
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.app.DiscourseApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationStatus

/**
 * Orchestrates FCM push: registers the Matrix pusher against sygnal's gcm
 * pushkin using the device's FCM token, and turns an incoming push into a
 * decrypted local notification.
 *
 * The homeserver pushes event_id-only through sygnal → FCM, so no plaintext
 * leaves the server; the event is fetched and decrypted on the device.
 */
object PushRegistrar {
    /** Matches the app entry (key) in sygnal.yaml. */
    const val APP_ID = "com.riiiiiiiley.discourse"

    /**
     * sygnal's Matrix push gateway, public HTTPS via the Cloudflare tunnel.
     * Must be public + https: the homeserver (Tuwunel) blocks pushes to
     * private-IP / plain-http targets, which silently dropped every push when
     * this pointed at the internal http://sygnal:5000.
     */
    const val GATEWAY_URL = "https://push.fulltimefeline.com/_matrix/push/v1/notify"

    private const val PREFS = "discourse.push"
    private const val KEY_TOKEN = "fcm.token"
    private const val KEY_ASKED_BATTERY = "askedBatteryExempt"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Fetches the current FCM token and registers the pusher. Safe to call
     * repeatedly; a no-op (caught) when Firebase isn't configured yet.
     */
    fun registerForPush(context: Context) {
        val app = context.applicationContext
        runCatching {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch {
                    // Register now (active account), then again once the
                    // background accounts have warmed, so every signed-in
                    // account gets a pusher. setPusher is idempotent.
                    onNewToken(app, token)
                    kotlinx.coroutines.delay(10_000)
                    onNewToken(app, token)
                }
            }
        }
    }

    /**
     * Prompts once to exempt the app from battery optimization, so OEMs (esp.
     * Samsung) don't sleep it when swiped away and drop FCM pushes. No-op if
     * already exempt or already asked.
     */
    fun requestBatteryExemptionOnce(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        if (prefs(context).getBoolean(KEY_ASKED_BATTERY, false)) return
        prefs(context).edit { putBoolean(KEY_ASKED_BATTERY, true) }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * A fresh FCM token arrived — register a pusher on EVERY signed-in account
     * (not just the active one), so background accounts also notify. Each
     * account's homeserver pushes to the same gateway with the same device
     * pushkey; incoming pushes are routed to the owning account in [onMessage].
     */
    suspend fun onNewToken(context: Context, token: String) {
        prefs(context).edit { putString(KEY_TOKEN, token) }
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }
        for (service in candidateServices(context)) {
            runCatching { service.setPushGatewayPusher(token, GATEWAY_URL, device) }
        }
    }

    /**
     * Turns an FCM push (sygnal's event_id-only data payload) into a decrypted
     * local notification. Restores a client if the app is cold.
     */
    suspend fun onMessage(context: Context, data: Map<String, String>) {
        val roomId = data["room_id"]?.ifBlank { null } ?: return
        val eventId = data["event_id"]?.ifBlank { null } ?: return

        // The event_id-only push doesn't say which account it's for, so try each
        // signed-in account until one resolves the event (that's its owner).
        var item: org.matrix.rustcomponents.sdk.NotificationItem? = null
        var accountUserId: String? = null
        for (service in candidateServices(context)) {
            val status = runCatching { service.notificationItem(roomId, eventId) }.getOrNull()
            if (status is NotificationStatus.Event) {
                item = status.item
                accountUserId = service.userId
                break
            }
        }
        if (item == null || accountUserId == null) return

        val sender = item.senderInfo.displayName
        val room = item.roomInfo.displayName
        // The decrypted event's raw JSON gives us the message body directly.
        val body = runCatching {
            JSONObject(item.rawEvent).optJSONObject("content")?.optString("body")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: if (item.event is NotificationEvent.Invite) "invited you" else "New message"

        val isDirect = sender.isNullOrBlank() || sender == room
        val title = if (isDirect) (sender ?: room ?: "Discourse") else (room ?: sender ?: "Discourse")
        val subtitle = if (!isDirect) sender else null

        NotificationManager.showPush(
            context = context.applicationContext,
            roomId = roomId,
            eventId = eventId,
            accountUserId = accountUserId,
            title = title,
            subtitle = subtitle,
            body = body,
            avatarUrl = item.roomInfo.avatarUrl,
            noisy = item.isNoisy != false,
        )
    }

    /**
     * Services for every signed-in account. Warm scopes when the app is alive;
     * otherwise every stored account is restored (cold push). Used both to
     * register pushers on all accounts and to route an incoming push to the
     * account that owns the event.
     */
    private suspend fun candidateServices(context: Context): List<MatrixService> {
        val app = context.applicationContext as? DiscourseApplication ?: return emptyList()
        // appStateOrNull, NOT appState: a cold push runs off the main thread, and
        // initializing AppState there crashes (ProcessLifecycleOwner is main-only).
        // When the UI hasn't warmed AppState in this process, restore below.
        val warm = app.appStateOrNull?.warmScopes?.map { it.service }.orEmpty()
        if (warm.isNotEmpty()) return warm
        // Cold push: restore all stored accounts (no sync, no UI change).
        val store = SessionStore(context)
        return store.loadAll().mapNotNull { token ->
            runCatching { MatrixService.restore(token, context) }.getOrNull()
        }
    }
}
