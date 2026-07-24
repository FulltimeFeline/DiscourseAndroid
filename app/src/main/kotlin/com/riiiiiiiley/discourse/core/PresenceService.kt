package com.riiiiiiiley.discourse.core

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.ui.theme.DiscourseColors
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * User presence (online/idle/offline). The Rust SDK doesn't surface presence,
 * so this polls the C-S endpoint directly with the session's access token.
 *
 * Main-thread confined like every view model: `register`/`unregister`/getters
 * are called from composition effects; network work hops to IO internally.
 * Presence can be server-disabled (a 403 on every call) — the service goes
 * quiet instead of hammering the endpoint, and consumers render nothing.
 */
class PresenceService(
    homeserverUrl: String,
    private val accessToken: String,
    /**
     * Signed-in user, so own-presence polling can be suppressed when "share
     * presence" is off.
     */
    private val ownUserId: String? = null,
    /** Source of the `sharePresence` toggle (iOS reads `Preferences.shared`). */
    private val preferences: Preferences? = null,
) {
    enum class State(val raw: String) {
        ONLINE("online"), OFFLINE("offline"), UNAVAILABLE("unavailable");

        /**
         * Dot colors: online uses the shared token; idle/offline match the iOS
         * system `.orange` / `.gray.opacity(0.6)` (iOS hard-codes those too —
         * there are no idle/offline tokens).
         */
        fun color(colors: DiscourseColors): Color = when (this) {
            ONLINE -> colors.presenceOnline
            UNAVAILABLE -> Color(0xFFFF9500)
            OFFLINE -> Color(0xFF8E8E93).copy(alpha = 0.6f)
        }

        val label: String
            get() = when (this) {
                ONLINE -> "Online"
                UNAVAILABLE -> "Idle"
                OFFLINE -> "Offline"
            }

        companion object {
            fun fromRaw(raw: String): State? = entries.firstOrNull { it.raw == raw }
        }
    }

    data class Entry(
        val state: State,
        /** Seconds since the user was last active; null when the server omits it. */
        val lastActiveAgo: Double? = null,
        val fetchedAt: Long,
        /**
         * The `status_msg` — Commet and friends store the user's custom status
         * here (not in a profile field).
         */
        val statusMessage: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Boxes are created on first read and live for the session (stable identity
     * so collectors keep observing the same flow). One flow per user, so an
     * update re-renders only that user's dots.
     */
    private val users = mutableMapOf<String, MutableStateFlow<Entry?>>()

    /**
     * Bumped on every entry update — collected by views that aggregate over
     * many users (the details sheet's Offline section) instead of one flow
     * per member. Per-user dots keep observing their own flow.
     */
    private val _changeTick = MutableStateFlow(0)
    val changeTick: StateFlow<Int> = _changeTick

    /** Refcounts of visible dots per user; keys are what the poll loop fetches. */
    private val watchers = mutableMapOf<String, Int>()
    private var pollJob: Job? = null
    private val inFlight = mutableSetOf<String>()

    /** Homeservers with presence disabled 403 every call; stop asking. */
    private var unsupported = false

    /** True while backgrounded and the poll loop is parked; watchers persist. */
    private var isPaused = false

    /**
     * Raw homeserver URL from the session; the actual request base resolves
     * through `.well-known` lazily (the session URL can be a bare server name
     * whose client API 404s — see the delegation constraint).
     */
    private val homeserverUrl = homeserverUrl.trimEnd('/')
    private var resolvedBase: String? = null
    private val baseMutex = Mutex()

    private fun user(userId: String): MutableStateFlow<Entry?> =
        users.getOrPut(userId) { MutableStateFlow(null) }

    /** The user's observable presence, for Compose collection. */
    fun entries(userId: String): StateFlow<Entry?> = user(userId)

    fun state(of: String): State? = user(of).value?.state

    /**
     * The user's custom status message (Matrix presence `status_msg`), which is
     * where Commet-family clients keep their status. null when unset/unfetched.
     */
    fun statusMessage(of: String): String? = user(of).value?.statusMessage

    /** "Online", "Idle", or how long ago they were last seen. */
    fun detailText(of: String): String? {
        val entry = user(of).value ?: return null
        return when (entry.state) {
            State.ONLINE -> "Online"
            State.UNAVAILABLE -> "Idle"
            State.OFFLINE -> {
                val ago = entry.lastActiveAgo
                if (ago != null && ago > 0) "Last active ${formatAgo(ago)} ago" else "Offline"
            }
        }
    }

    /**
     * A dot became visible: fetch if stale and keep the user in the poll loop
     * until the matching `unregister`.
     */
    fun register(userId: String) {
        watchers[userId] = (watchers[userId] ?: 0) + 1
        refresh(userId)
        startPollingIfNeeded()
    }

    fun unregister(userId: String) {
        val count = watchers[userId] ?: return
        if (count <= 1) watchers.remove(userId) else watchers[userId] = count - 1
        if (watchers.isEmpty()) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** Stops the poll while backgrounded; watchers are kept for `resume()`. */
    fun pause() {
        isPaused = true
        pollJob?.cancel()
        pollJob = null
    }

    /** Restarts the poll after `pause()` if any dots remain registered. */
    fun resume() {
        isPaused = false
        if (watchers.isEmpty()) return
        startPollingIfNeeded()
    }

    /** One timer for every visible dot, not a loop per dot. */
    private fun startPollingIfNeeded() {
        if (pollJob != null || unsupported || isPaused) return
        pollJob = scope.launch {
            while (isActive) {
                delay((POLL_INTERVAL_SECONDS * 1000).toLong())
                if (unsupported) return@launch
                for (userId in watchers.keys.toList()) {
                    // Below the poll period, so each tick fetches unless an
                    // ad-hoc refresh just did.
                    fetch(userId, maxAgeSeconds = POLL_INTERVAL_SECONDS * 0.75)
                }
            }
        }
    }

    /**
     * Fetches unless a recent result is cached; cheap to call repeatedly from
     * rows.
     */
    fun refresh(userId: String) {
        fetch(userId, maxAgeSeconds = POLL_INTERVAL_SECONDS * 1.25)
    }

    private fun fetch(userId: String, maxAgeSeconds: Double) {
        if (unsupported) return
        // With sharing off, don't poll our own status: each GET is activity the
        // server reads as "online".
        if (userId == ownUserId && preferences?.value?.sharePresence == false) return
        val box = user(userId)
        val entry = box.value
        if (entry != null && (System.currentTimeMillis() - entry.fetchedAt) < maxAgeSeconds * 1000) return
        if (userId in inFlight) return
        inFlight.add(userId)
        scope.launch {
            try {
                val base = apiBase() ?: return@launch
                val url = "$base/_matrix/client/v3/presence/" +
                    "${URLEncoder.encode(userId, "UTF-8").replace("+", "%20")}/status"
                val (code, body) = httpGet(url) ?: return@launch
                if (code == 403) {
                    // Presence disabled server-side; don't keep hammering it.
                    unsupported = true
                    pollJob?.cancel()
                    pollJob = null
                    return@launch
                }
                if (code != 200) return@launch
                val json = runCatching { JSONObject(body ?: "") }.getOrNull() ?: return@launch
                val presence = State.fromRaw(json.optString("presence")) ?: return@launch
                val ago = if (json.has("last_active_ago")) json.optDouble("last_active_ago") / 1000 else null
                val statusMsg = (json.opt("status_msg") as? String)?.takeIf { it.trim().isNotEmpty() }
                box.value = Entry(
                    state = presence,
                    lastActiveAgo = ago,
                    fetchedAt = System.currentTimeMillis(),
                    statusMessage = statusMsg,
                )
                _changeTick.value += 1
            } finally {
                inFlight.remove(userId)
            }
        }
    }

    /**
     * The client-API base, resolving `.well-known/matrix/client` once and
     * falling back to the raw session URL when there's no delegation.
     */
    private suspend fun apiBase(): String? {
        resolvedBase?.let { return it }
        return baseMutex.withLock {
            resolvedBase?.let { return@withLock it }
            if (homeserverUrl.isEmpty()) return@withLock null
            val delegated = withContext(Dispatchers.IO) {
                runCatching {
                    val (code, body) = httpGetBlocking("$homeserverUrl/.well-known/matrix/client")
                        ?: return@runCatching null
                    if (code != 200) return@runCatching null
                    JSONObject(body ?: "").optJSONObject("m.homeserver")
                        ?.optString("base_url")?.takeIf { it.isNotEmpty() }?.trimEnd('/')
                }.getOrNull()
            }
            val base = delegated ?: homeserverUrl
            resolvedBase = base
            base
        }
    }

    private suspend fun httpGet(url: String): Pair<Int, String?>? = withContext(Dispatchers.IO) {
        httpGetBlocking(url, bearer = accessToken)
    }

    private fun httpGetBlocking(url: String, bearer: String? = null): Pair<Int, String?>? =
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                code to text
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    companion object {
        private const val POLL_INTERVAL_SECONDS = 20.0

        /**
         * Single largest unit, abbreviated — the analogue of iOS
         * `Duration.formatted(.units(allowed: [.days,.hours,.minutes], …))`.
         */
        private fun formatAgo(seconds: Double): String {
            val minutes = (seconds / 60).toLong()
            val hours = minutes / 60
            val days = hours / 24
            return when {
                days > 0 -> if (days == 1L) "1 day" else "$days days"
                hours > 0 -> "$hours hr"
                else -> "$minutes min"
            }
        }
    }
}

// MARK: - Composition plumbing (the analogue of the iOS environment key)

val LocalPresenceService = staticCompositionLocalOf<PresenceService?> { null }

/**
 * Presence dot pinned to an avatar's bottom-trailing corner; registers with
 * the poll loop while visible. Renders nothing until presence is known (or
 * ever, when the server has presence disabled).
 */
@Composable
fun PresenceDot(
    userId: String,
    size: Dp = 10.dp,
    /** The color the dot is ringed against (iOS strokes `.background`). */
    ringColor: Color = LocalDiscourseColors.current.bgApp,
    modifier: Modifier = Modifier,
) {
    val presence = LocalPresenceService.current ?: return
    DisposableEffect(userId) {
        presence.register(userId)
        onDispose { presence.unregister(userId) }
    }
    val entry by presence.entries(userId).collectAsStateWithLifecycle()
    val colors = LocalDiscourseColors.current
    // Keep the last state for the exit animation frame.
    var lastState by remember { mutableStateOf<PresenceService.State?>(null) }
    entry?.state?.let { lastState = it }
    AnimatedVisibility(
        visible = entry != null,
        enter = scaleIn(tween(200)) + fadeIn(tween(200)),
        exit = scaleOut(tween(200)) + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val state = lastState ?: return@AnimatedVisibility
        val dotColor by animateColorAsState(state.color(colors), tween(200), label = "presence")
        Box(
            Modifier
                .size(size)
                .background(dotColor, CircleShape)
                .border(size * 0.18f, ringColor, CircleShape)
                .semantics { contentDescription = state.label },
        )
    }
}

/** Overlays a presence dot on an avatar (iOS `presenceIndicator`). */
@Composable
fun PresenceIndicator(
    userId: String?,
    size: Dp = 10.dp,
    ringColor: Color = LocalDiscourseColors.current.bgApp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        if (userId != null) {
            PresenceDot(userId, size, ringColor, Modifier.align(Alignment.BottomEnd))
        }
    }
}
