package com.riiiiiiiley.discourse.app

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.NotificationManager
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.RestorationToken
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.core.SessionStore
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Root state machine: launching → loggedOut → active(session).
 * All mutations run on the main dispatcher (the Kotlin analogue of the iOS
 * @MainActor isolation).
 */
class AppState(context: Context) {

    sealed interface Phase {
        data object Launching : Phase
        data object LoggedOut : Phase

        /** Homeserver unreachable; session kept, retrying. */
        data object Disconnected : Phase
        data class Active(val scope: SessionScope) : Phase
    }

    private val appContext = context.applicationContext
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow<Phase>(Phase.Launching)
    val phase: StateFlow<Phase> = _phase

    val isQuickSwitcherPresented = MutableStateFlow(false)
    val isAddAccountPresented = MutableStateFlow(false)

    /** Set on notification click; the main window navigates and clears it. */
    val pendingRoomNavigation = MutableStateFlow<String?>(null)

    /**
     * Room + event to jump to. The main window opens the room but leaves this
     * set — the timeline clears it once it has scrolled to the event.
     */
    val pendingEventNavigation = MutableStateFlow<EventNavigation?>(null)
    val isSignOutConfirmPresented = MutableStateFlow(false)

    data class EventNavigation(
        val roomId: String,
        val eventId: String,
        /**
         * Consumers drop requests older than ~30s, so a navigation that never
         * found its room doesn't fire hours later when the room opens.
         */
        val requestedAt: Long = System.currentTimeMillis(),
    )

    val sidebarFilterFocusRequest = MutableStateFlow(0)

    /**
     * New-chat sheet requested from the sidebar's "+" menu / menu commands;
     * presented by the main shell.
     */
    val newChatSheet =
        MutableStateFlow<com.riiiiiiiley.discourse.features.compose.NewChatSheet?>(null)

    /** A non-active account has unread activity (Settings tab dot). */
    private val _otherAccountsHaveUnread = MutableStateFlow(false)
    val otherAccountsHaveUnread: StateFlow<Boolean> = _otherAccountsHaveUnread

    /** Cross-account unread badge for an account row (Settings). */
    fun unreadCount(forUserId: String): Int =
        scopes[forUserId]?.roomList?.unreadTotal?.value ?: 0

    /**
     * Every warm scope reports unread-total changes here so the Settings-tab
     * dot and per-account badges stay current (iOS registerBadgeReporting).
     */
    private fun registerBadgeReporting(scope: SessionScope) {
        scope.roomList.onUnreadTotalChanged = { recomputeOtherAccountsUnread() }
    }

    private fun recomputeOtherAccountsUnread() {
        _otherAccountsHaveUnread.value = scopes.any { (userId, scope) ->
            userId != activeUserId && scope.roomList.unreadTotal.value > 0
        }
    }
    val ringingCall = MutableStateFlow<RingingCall?>(null)

    /** Set when a ring is accepted; the room's timeline joins the call and clears it. */
    val pendingCallJoin = MutableStateFlow<String?>(null)

    /** Rooms whose call is open in a detached window; hides the in-room join banner. */
    val activeCallRoomIds = MutableStateFlow<Set<String>>(emptySet())

    data class RingingCall(
        val roomId: String,
        val roomName: String,
        val avatarUrl: String?,
        val isDirect: Boolean,
    ) {
        val id: String get() = roomId
    }

    // MARK: Timeline scroll memory

    private val anchorPrefs = appContext.getSharedPreferences("discourse", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Last visible event per room (null/absent = bottom), persisted so reopening
     * lands where you left off. Keyed by event ID: the SDK's timeline item IDs
     * are per-instance and don't survive a relaunch.
     */
    private val timelineAnchors: MutableMap<String, String> by lazy {
        val raw = anchorPrefs.getString("timelineScrollAnchors", null) ?: "{}"
        runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
            .toMutableMap()
    }

    fun timelineAnchor(forRoom: String): String? = timelineAnchors[forRoom]

    fun setTimelineAnchor(eventId: String?, forRoom: String) {
        if (eventId == null) timelineAnchors.remove(forRoom) else timelineAnchors[forRoom] = eventId
        anchorPrefs.edit {
            putString("timelineScrollAnchors", json.encodeToString(timelineAnchors.toMap()))
        }
    }

    /** All signed-in accounts, in sign-in order. */
    private val _accountTokens = MutableStateFlow<List<RestorationToken>>(emptyList())
    val accountTokens: StateFlow<List<RestorationToken>> = _accountTokens

    private val sessionStore = SessionStore(appContext)
    val preferences = Preferences(appContext)

    init {
        // Channels + preference plumbing for local notifications (iOS
        // NotificationManager.shared.activate() at app init).
        NotificationManager.activate(appContext, preferences)

        // App-lifecycle sync management (the iOS scenePhase handler): on
        // background, park presence, every cached room's ephemeral long-poll,
        // and the sliding-sync loop; on foreground, resume them all. EXCEPT
        // during a call: MatrixRTC keeps you in the call with a server-side
        // "delayed leave" dead-man's switch the SDK must keep refreshing over
        // sync — pausing it would make every other participant see you drop.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> onAppBackgrounded()
                    Lifecycle.Event.ON_START -> onAppForegrounded()
                    else -> Unit
                }
            },
        )
    }

    private fun onAppBackgrounded() {
        val scope = (_phase.value as? Phase.Active)?.scope ?: return
        scope.presence.pause()
        scope.setEphemeralSyncPaused(true)
        if (activeCallRoomIds.value.isEmpty()) {
            mainScope.launch { runCatching { scope.service.pauseSync() } }
        }
    }

    private fun onAppForegrounded() {
        val scope = (_phase.value as? Phase.Active)?.scope ?: return
        scope.presence.resume()
        scope.setEphemeralSyncPaused(false)
        mainScope.launch { runCatching { scope.service.resumeSync() } }
    }

    /**
     * Avatar bitmap for a notification's large icon, resolved via the owning
     * account's media loader (iOS notificationAvatarData).
     */
    suspend fun notificationAvatarBitmap(mxcUrl: String, accountUserId: String) =
        scopes[accountUserId]?.mediaLoader?.avatar(mxcUrl, pixelSize = 128f)

    /**
     * Detached launcher for notification actions — they must survive the
     * shell recomposing away mid-action (iOS unstructured Tasks).
     */
    fun launchDetached(block: suspend () -> Unit) {
        mainScope.launch { block() }
    }

    /** Warm sessions, kept across account switches. Keyed by user ID. */
    private val scopes = mutableMapOf<String, SessionScope>()

    /** Every warm session (active + background), for multi-account push. */
    val warmScopes: List<SessionScope> get() = scopes.values.toList()
    private var reconnectJob: Job? = null

    /**
     * Account awaiting reconnection while `.disconnected`, so a manual retry
     * knows who to activate.
     */
    private var reconnectUserId: String? = null

    val activeUserId: String?
        get() = (_phase.value as? Phase.Active)?.scope?.userId

    /**
     * Called once at launch: restore the last active account, if any.
     * Runs on IO — the encrypted store's first read (Keystore) and the
     * client restore are blocking; on Main they ANR'd the cold launch.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (_phase.value !is Phase.Launching) return@withContext
        _accountTokens.value = sessionStore.loadAll()
        if (_accountTokens.value.isEmpty()) {
            _phase.value = Phase.LoggedOut
            return@withContext
        }
        val target = sessionStore.activeUserId ?: _accountTokens.value[0].session.userId
        activate(target)
        // Bring up the other accounts in the background so they, too, notify and
        // feed unread badges. Detached so it never delays the active account's UI.
        mainScope.launch(Dispatchers.IO) { warmBackgroundAccounts() }
    }

    suspend fun switchAccount(to: String) {
        if (to == activeUserId) return
        activate(to)
    }

    /**
     * The session a notification action (reply / mark read) runs against.
     * Background accounts go straight to their warm scope (only a syncing
     * scope can have notified). Cold launch has only the active scope warm, so
     * switch accounts first, then act on the now-active session.
     */
    suspend fun sessionForNotificationAction(accountUserId: String?): SessionScope? {
        if (accountUserId != null && accountUserId != activeUserId) {
            scopes[accountUserId]?.let { return it }
            switchAccount(to = accountUserId)
            val scope = (_phase.value as? Phase.Active)?.scope
            return if (scope?.userId == accountUserId) scope else null
        }
        return (_phase.value as? Phase.Active)?.scope
    }

    // MARK: Multi-account notifications & badges

    /**
     * Restore + keep warm every other signed-in account, so background accounts
     * sync (driving notifications and cross-account unread badges). Local
     * notification display and pusher registration respect the per-account
     * toggle; warming itself is unconditional so badges still work. (Room-list
     * priming and pusher registration attach here as those ports land.)
     */
    suspend fun warmBackgroundAccounts() {
        for (token in _accountTokens.value) {
            val userId = token.session.userId
            if (userId == activeUserId || scopes[userId] != null) continue
            val service = runCatching { MatrixService.restore(token, appContext) }.getOrNull()
                ?: continue
            val scope = SessionScope(service = service, token = token,
                                     context = appContext, preferences = preferences)
            scopes[userId] = scope
            registerAuthErrorReporting(scope)
            registerBadgeReporting(scope)
            // Background room list: feeds notifications and the cross-account
            // unread badges (iOS warms these the same way).
            mainScope.launch(Dispatchers.IO) {
                runCatching { scope.roomList.primeSnapshotForLaunch() }
                runCatching { scope.roomList.start() }
            }
        }
    }

    /**
     * Display name (falling back to the localpart) for an account, for
     * notification labels and the account list.
     */
    fun accountDisplayName(forUserId: String): String {
        scopes[forUserId]?.ownDisplayName?.value?.takeIf { it.isNotEmpty() }?.let { return it }
        return localpart(forUserId)
    }

    /**
     * Toggles an account's notifications: persists the choice. (Pusher
     * registration/removal attaches here with the push port.)
     */
    fun setNotificationsEnabled(enabled: Boolean, forUserId: String) {
        preferences.setNotificationsEnabled(enabled, forUserId)
    }

    // MARK: Auth errors

    /**
     * Wires a scope's auth-error signal so a revoked account (foreground or
     * background) gets signed out. (Unread-badge reporting joins this once the
     * room list lands, mirroring iOS registerBadgeReporting.)
     */
    private fun registerAuthErrorReporting(scope: SessionScope) {
        scope.onAuthError = { userId ->
            mainScope.launch { handleAuthError(userId) }
        }
        scope.startAuthErrorMonitor()
        // Clear the once-per-account guard, so a re-signed-in account can be
        // signed out again if its new token also dies.
        authErrorHandledUserIds.remove(scope.userId)
    }

    /**
     * Accounts already torn down for a dead token; the delegate can fire
     * repeatedly for the same session.
     */
    private val authErrorHandledUserIds = mutableSetOf<String>()

    /**
     * Confirmed unknown-token / soft-logout: the token is dead, so retrying
     * restore (`.disconnected`) is futile. Remove the account so relaunch
     * doesn't loop on it, then fall back to the next account or login. Like
     * `logOut` but skips the network logout (token already invalid).
     */
    suspend fun handleAuthError(userId: String) {
        if (authErrorHandledUserIds.contains(userId)) return
        if (_accountTokens.value.none { it.session.userId == userId }) return
        authErrorHandledUserIds.add(userId)
        // A dead account must not keep stale banners around.
        NotificationManager.clearAll()

        val isActive = activeUserId == userId
        scopes[userId]?.let { scope ->
            scope.tearDown()
            sessionStore.removeSessionDirectories(scope.token)
            // Per-account persistence: sidebar snapshot + disk thumbnails
            // (which include encrypted-room avatars). Off-main; file I/O.
            mainScope.launch(Dispatchers.IO) {
                RoomListViewModel.removeSnapshot(appContext, userId)
                MediaLoader.removeDiskCache(appContext, userId)
            }
            scopes.remove(userId)
        }
        recomputeOtherAccountsUnread()
        _accountTokens.value = _accountTokens.value.filter { it.session.userId != userId }
        runCatching { sessionStore.saveAll(_accountTokens.value) }

        if (!isActive) return
        reconnectJob?.cancel()
        reconnectJob = null
        val next = _accountTokens.value.firstOrNull()
        if (next != null) {
            _phase.value = Phase.Launching
            activate(next.session.userId)
        } else {
            sessionStore.clearAll()
            _phase.value = Phase.LoggedOut
        }
    }

    suspend fun logIn(homeserver: String, username: String, password: String) {
        val result = MatrixService.logIn(homeserver, username, password, appContext)
        completeLogin(service = result.service, token = result.token)
    }

    /** Finalizes any auth result: persist the token, enter the session. */
    fun completeLogin(service: MatrixService, token: RestorationToken) {
        _accountTokens.value =
            _accountTokens.value.filter { it.session.userId != token.session.userId } + token
        sessionStore.saveAll(_accountTokens.value)
        sessionStore.activeUserId = token.session.userId
        val scope = SessionScope(service = service, token = token,
                                 context = appContext, preferences = preferences)
        scopes[token.session.userId] = scope
        registerAuthErrorReporting(scope)
        registerBadgeReporting(scope)
        isAddAccountPresented.value = false
        _phase.value = Phase.Active(scope)
        mainScope.launch { runCatching { service.startSync() } }
    }

    /** Signs out the active account; falls back to the next account if any. */
    suspend fun logOut() {
        reconnectJob?.cancel()
        reconnectJob = null
        // Retire this account's banners and rings before the scope dies.
        NotificationManager.clearAll()
        val scope = (_phase.value as? Phase.Active)?.scope
        if (scope == null) {
            _phase.value = Phase.LoggedOut
            return
        }
        scope.tearDown()
        scope.service.logOut()
        sessionStore.removeSessionDirectories(scope.token)
        // Per-account cold-launch persistence: sidebar snapshot + disk
        // thumbnails (which include encrypted-room avatars).
        val removedUserId = scope.userId
        mainScope.launch(Dispatchers.IO) {
            RoomListViewModel.removeSnapshot(appContext, removedUserId)
            MediaLoader.removeDiskCache(appContext, removedUserId)
        }
        scopes.remove(scope.userId)
        _accountTokens.value = _accountTokens.value.filter { it.session.userId != scope.userId }
        runCatching { sessionStore.saveAll(_accountTokens.value) }
        val next = _accountTokens.value.firstOrNull()
        if (next != null) {
            _phase.value = Phase.Launching
            activate(next.session.userId)
        } else {
            sessionStore.clearAll()
            _phase.value = Phase.LoggedOut
        }
    }

    private suspend fun activate(userId: String) = withContext(Dispatchers.IO) {
        val token = _accountTokens.value.firstOrNull { it.session.userId == userId }
        if (token == null) {
            if (_accountTokens.value.isEmpty()) _phase.value = Phase.LoggedOut
            return@withContext
        }
        // Clear the outgoing account's "room on screen" marker so its warm
        // background sync doesn't keep auto-clearing that room's unread.
        (_phase.value as? Phase.Active)?.scope?.roomList?.setActiveRoom(null)
        scopes[userId]?.let { warm ->
            reconnectJob?.cancel()
            reconnectJob = null
            sessionStore.activeUserId = userId
            _phase.value = Phase.Active(warm)
            recomputeOtherAccountsUnread()
            return@withContext
        }
        try {
            val service = MatrixService.restore(token, appContext)
            val scope = SessionScope(service = service, token = token,
                                     context = appContext, preferences = preferences)
            scopes[userId] = scope
            registerAuthErrorReporting(scope)
            registerBadgeReporting(scope)
            sessionStore.activeUserId = userId
            reconnectJob?.cancel()
            reconnectJob = null
            // Prime the cached sidebar snapshot before flipping to Active so
            // the first frame shows chats instead of an empty list.
            runCatching { scope.roomList.primeSnapshotForLaunch() }
            _phase.value = Phase.Active(scope)
            recomputeOtherAccountsUnread()
            // Start sync here, not from the main shell's LaunchedEffect: the view
            // tree takes time to build before that fires, and until the first
            // diff lands restored rooms have no FFI backing and can't open.
            // Idempotent — the shell's own start() re-call is a no-op.
            mainScope.launch { runCatching { service.startSync() } }
        } catch (error: Exception) {
            // Restore fails only when the server is unreachable or the client
            // can't build; a revoked token surfaces later, during sync. So keep
            // retrying rather than logging out on a transient network blip.
            _phase.value = Phase.Disconnected
            reconnectUserId = userId
            scheduleReconnect(userId)
        }
    }

    /**
     * Retries restore every 30s until the server is reachable. No-op if a
     * retry loop is already running.
     */
    private fun scheduleReconnect(userId: String) {
        if (reconnectJob != null) return
        reconnectJob = mainScope.launch {
            while (isActive) {
                delay(30_000)
                if (_phase.value !is Phase.Disconnected) return@launch
                activate(userId)
            }
        }
    }

    /**
     * Manual retry from the disconnected screen — the user just fixed their
     * network and shouldn't wait out the 30s timer.
     */
    fun retryConnectionNow() {
        if (_phase.value !is Phase.Disconnected) return
        val id = reconnectUserId ?: return
        reconnectJob?.cancel()
        reconnectJob = null
        mainScope.launch { activate(id) }
    }

    companion object {
        fun localpart(of: String): String {
            if (!of.startsWith("@")) return of
            return of.drop(1).takeWhile { it != ':' }
        }
    }
}
