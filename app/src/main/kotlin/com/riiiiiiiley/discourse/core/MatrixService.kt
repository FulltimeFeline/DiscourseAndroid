package com.riiiiiiiley.discourse.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.OAuthConfiguration
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.SendQueueRoomErrorListener
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SsoHandler
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk.OAuthAuthorizationData
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/**
 * Runs [operation] with a wall-clock timeout; returns true if it completed,
 * false if it timed out (the operation is then cancelled).
 */
suspend fun runWithTimeout(seconds: Double, operation: suspend () -> Unit): Boolean =
    withTimeoutOrNull((seconds * 1000).toLong()) {
        operation()
        true
    } ?: false

sealed class MatrixServiceException(message: String) : Exception(message) {
    class PasswordLoginUnsupported : MatrixServiceException(
        "This homeserver doesn't support password login. OAuth sign-in is coming soon.")

    class SessionNotFound : MatrixServiceException("No stored session for this account.")

    class RegistrationUnsupported : MatrixServiceException(
        "This homeserver doesn't allow creating an account from the app.")

    class RegistrationFailed(message: String) : MatrixServiceException(message)
}

/**
 * Persists OAuth token refreshes the SDK performs mid-session. Without this
 * the store keeps the login-time pair forever; on OAuth homeservers (MAS)
 * the refresh token rotates, so on next launch `restoreSession` is fed an
 * already-consumed token, restore fails, and the account is stuck offline.
 * Callbacks arrive on Rust threads — the store read-modify-write is
 * self-contained and never touches app state.
 */
class SessionDelegate(private val sessionStore: SessionStore) : ClientSessionDelegate {

    override fun retrieveSessionFromKeychain(userId: String): Session {
        val token = sessionStore.loadAll().firstOrNull { it.session.userId == userId }
            // Expected during fresh login: the account isn't stored yet.
            ?: throw MatrixServiceException.SessionNotFound()
        return token.session.ffiSession
    }

    override fun saveSessionInKeychain(session: Session) {
        // Atomic read-modify-write: concurrent refreshes on other accounts'
        // Rust threads must not clobber this account's rotated token.
        runCatching {
            sessionStore.mutate { tokens ->
                val index = tokens.indexOfFirst { it.session.userId == session.userId }
                if (index < 0) {
                    // Unknown user (fresh login before completeLogin persists it):
                    // the token is saved by AppState; leave the list untouched
                    // rather than inventing one.
                    return@mutate tokens
                }
                // Preserve storePassphrase/dataPath/cachePath; only the pair moved.
                tokens.toMutableList().also {
                    it[index] = it[index].copy(session = RestorationToken.SessionData(session))
                }
            }
        }
    }
}

/** A login's finished (service, restoration-token) pair. */
data class LoginResult(val service: MatrixService, val token: RestorationToken)

/**
 * Owns the FFI [Client] (and, from M2, the sync + room list services).
 * The only type in the app that drives the Matrix Rust SDK control flow.
 */
class MatrixService private constructor(
    val client: Client,
    /** Retained so the token-refresh delegate outlives client construction. */
    @Suppress("unused") private val sessionDelegate: SessionDelegate?,
) {
    val userId: String = client.userId()

    /** Our own server name (the `domain` in `@user:domain`). */
    val ownServerName: String
        get() = userId.substringAfter(':', "")

    var syncService: SyncService? = null
        private set
    var roomListService: RoomListService? = null
        private set
    private var syncStateHandle: TaskHandle? = null

    /**
     * Sync state, feeding the room list's banner + reconnection UI AND the
     * internal monitor (error-restart + send-queue gate). Unlike the iOS
     * AsyncStream bridges (single-consumer, hence two SDK listeners there), a
     * StateFlow fans out to any number of collectors, so one listener serves
     * both.
     */
    private val _syncState = MutableStateFlow(SyncServiceState.IDLE)
    val syncStateFlow: StateFlow<SyncServiceState> = _syncState

    /**
     * The SDK's unknown-token / soft-logout signal. `AppState` drops the
     * affected account into a re-auth state instead of retrying restore.
     */
    private val _authErrors = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val authErrorFlow: SharedFlow<Boolean> = _authErrors
    private var clientDelegateHandle: TaskHandle? = null

    /** Send-queue self-disable signal; drives the reachability-style re-enable. */
    private val _sendQueueErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private var sendQueueHandle: TaskHandle? = null
    private var sendQueueJob: Job? = null
    private var syncMonitorJob: Job? = null

    /**
     * Main-thread scope for the sync monitor + send-queue re-enable, so
     * `latestSyncState`/`isPaused` are only ever touched on main — the same
     * confinement `pauseSync`/`resumeSync` run under (iOS pins these to the
     * main actor for the same reason).
     */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Latest sync state, tracked so the send-queue re-enable only fires when
     * the connection is actually up.
     */
    private var latestSyncState: SyncServiceState = SyncServiceState.IDLE

    /** Debounce so a burst of send errors schedules one re-enable. */
    private var queueReenableScheduled = false

    /**
     * Client-API base URL for manual REST calls (extended profile), resolved
     * once via `.well-known` so we hit the delegated homeserver
     * (e.g. matrix.example.com) rather than the bare server name, which may
     * 404 the client API.
     */
    private var resolvedApiBase: String? = null

    init {
        // Covers both login and restore: a revoked token surfaces here.
        clientDelegateHandle = runCatching {
            client.setDelegate(object : ClientDelegate {
                override fun didReceiveAuthError(isSoftLogout: Boolean) {
                    _authErrors.tryEmit(isSoftLogout)
                }

                override fun onBackgroundTaskErrorReport(
                    taskName: String,
                    error: uniffi.matrix_sdk_common.BackgroundTaskFailureReason,
                ) = Unit
            })
        }.getOrNull()
    }

    /**
     * Builds and starts the sync service (idempotent). Main-safe: the
     * blocking FFI (builder + start) hops to IO — on the main thread it held
     * the input queue long enough to ANR right after login/restore.
     */
    suspend fun startSync() = withContext(Dispatchers.IO) {
        if (syncService != null) return@withContext
        // Offline mode: the SDK serves cached data and reports OFFLINE
        // (which the room list already renders) instead of churning ERROR
        // while the network is down.
        // Room-list timeline limit: makes the room-list sync return each room's
        // latest event, so sidebar previews populate WITHOUT subscribing to every
        // room. Blanket-subscribing (the old approach) produced a ~12k request
        // per sync and, more importantly, the receipts/typing extensions
        // (scoped to subscribed rooms) don't stream live under that load — this
        // is why read receipts and typing were frozen.
        val sync = client.syncService()
            .withOfflineMode()
            .withRoomListTimelineLimit(limit = 1u)
            .finish()
        syncService = sync
        roomListService = sync.roomListService()
        syncStateHandle = sync.state(object : SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                _syncState.value = state
            }
        })

        // Watch sync state: restart on a hard ERROR (Element X does the
        // same 250ms bounce), and remember the state for the send-queue
        // re-enable gate below. A deliberate pause (backgrounding) isn't an
        // error to recover from — `isPaused` suppresses the bounce.
        syncMonitorJob?.cancel()
        syncMonitorJob = mainScope.launch {
            _syncState.collect { state ->
                latestSyncState = state
                if (state == SyncServiceState.ERROR && !isPaused) {
                    delay(250)
                    if (isPaused) return@collect
                    syncService?.start()
                }
            }
        }

        // The SDK disables a room's send queue after any send error and never
        // re-enables it. Watch for that and re-enable shortly after, once sync
        // reports it's running again — a reachability-lite version of Element
        // X's NWPathMonitor gate.
        sendQueueHandle = client.subscribeToSendQueueStatus(object : SendQueueRoomErrorListener {
            override fun onError(roomId: String, error: ClientException) {
                _sendQueueErrors.tryEmit(Unit)
            }
        })
        sendQueueJob?.cancel()
        sendQueueJob = mainScope.launch {
            _sendQueueErrors.collect { scheduleSendQueueReenable() }
        }

        sync.start()
    }

    /**
     * Debounced re-enable: after a send-queue error, wait a beat and — if
     * sync is running (i.e. the connection is back) — re-enable all queues.
     * If it's still not running, try again on the next error.
     */
    private fun scheduleSendQueueReenable() {
        if (queueReenableScheduled) return
        queueReenableScheduled = true
        mainScope.launch {
            delay(2_000)
            queueReenableScheduled = false
            if (latestSyncState != SyncServiceState.RUNNING) return@launch
            enableAllSendQueues()
        }
    }

    /**
     * The SDK disables a room's send queue after any send error; nothing
     * re-enables it by itself. Called when connectivity recovers and before
     * a manual retry, mirroring Element X's reachability handling.
     */
    suspend fun enableAllSendQueues() {
        // Called from Main-scoped callers (reconnect, retry); the FFI polls the
        // Rust runtime, so keep it off the input thread.
        withContext(Dispatchers.IO) { runCatching { client.enableAllSendQueues(enable = true) } }
    }

    /**
     * Registers (or updates) the HTTP pusher pointing at the UnifiedPush
     * endpoint through the Matrix push gateway. `event_id_only` so the gateway
     * carries no plaintext — the event is fetched and decrypted on the device.
     */
    suspend fun setPushGatewayPusher(
        pushkey: String,
        gatewayUrl: String,
        deviceDisplayName: String,
    ) = withContext(Dispatchers.IO) {
        client.setPusher(
            identifiers = PusherIdentifiers(pushkey = pushkey, appId = PushRegistrar.APP_ID),
            kind = PusherKind.Http(
                HttpPusherData(
                    url = gatewayUrl,
                    format = PushFormat.EVENT_ID_ONLY,
                    defaultPayload = "{}",
                ),
            ),
            appDisplayName = "Discourse",
            deviceDisplayName = deviceDisplayName,
            profileTag = null,
            lang = "en",
            append = false,
        )
    }

    suspend fun deleteUnifiedPushPusher(endpoint: String) = withContext(Dispatchers.IO) {
        runCatching {
            client.deletePusher(PusherIdentifiers(pushkey = endpoint, appId = PushRegistrar.APP_ID))
        }
    }

    /** Builds a notification client and fetches + decrypts one event for display. */
    suspend fun notificationItem(roomId: String, eventId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val nc = client.notificationClient(NotificationProcessSetup.MultipleProcesses)
            nc.getNotification(roomId, eventId)
        }.getOrNull()
    }

    /**
     * The homeserver's max upload size in bytes, fetched once and cached, so
     * the composer can reject an oversize file before uploading it.
     */
    /**
     * The session (access token + homeserver URL) is fixed for the client's
     * lifetime, but `client.session()` is a JNI hop the SDK re-runs each call.
     * Network helpers fetch it per call — and some (fetchProfile) fan out per
     * visible row during scroll — so cache it and read the FFI off-main on the
     * first miss. @Volatile: the fan-out is concurrent.
     */
    @Volatile private var cachedSession: Session? = null
    private suspend fun session(): Session? {
        cachedSession?.let { return it }
        val s = withContext(Dispatchers.IO) { runCatching { client.session() }.getOrNull() }
        if (s != null) cachedSession = s
        return s
    }

    private var cachedMaxUploadSize: ULong? = null
    suspend fun maxUploadSize(): ULong? {
        cachedMaxUploadSize?.let { return it }
        val size = withContext(Dispatchers.IO) { runCatching { client.getMaxMediaUploadSize() }.getOrNull() }
        cachedMaxUploadSize = size
        return size
    }

    /** True while sync is intentionally paused (app backgrounding). */
    private var isPaused = false

    /**
     * Stops the sync loop for backgrounding WITHOUT tearing down the service,
     * so the process suspends cleanly mid-nothing. The room list and timeline
     * subscriptions stay attached to the same (stopped) service; `resumeSync`
     * just restarts it. Never fully rebuilds — that would orphan those
     * subscriptions. Call from the main thread.
     */
    suspend fun pauseSync() {
        val sync = syncService
        if (sync == null || isPaused) return
        isPaused = true
        sync.stop()
    }

    /**
     * Restarts a paused sync loop on foreground (or starts it if it never
     * ran). Safe to call unconditionally from the lifecycle handler. Never
     * checks for cancellation part-way: a rapid background→foreground bounce
     * cancelling the caller must not leave the app silently offline.
     */
    suspend fun resumeSync() {
        val sync = syncService
        if (sync != null) {
            isPaused = false
            sync.start()
        } else {
            runCatching { startSync() }
        }
    }

    // MARK: Creating and joining rooms

    data class UserHit(
        val id: String,
        val displayName: String?,
        val avatarUrl: String?,
    ) {
        val name: String get() = displayName ?: id
    }

    suspend fun searchUsers(query: String): List<UserHit> {
        val results = withContext(Dispatchers.IO) {
            runCatching { client.searchUsers(query, 10u) }.getOrNull()
        } ?: return emptyList()
        return results.results.map {
            UserHit(id = it.userId, displayName = it.displayName, avatarUrl = it.avatarUrl)
        }
    }

    /** Opens the existing DM with this user, or creates an encrypted one. */
    suspend fun startDm(userId: String): String = withContext(Dispatchers.IO) {
        runCatching { client.getDmRoom(userId) }.getOrNull()?.let { return@withContext it.id() }
        client.createRoom(CreateRoomParameters(
            name = null,
            isEncrypted = true,
            isDirect = true,
            visibility = RoomVisibility.Private,
            preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
            invite = listOf(userId),
        ))
    }

    sealed class NewRoomVisibility {
        data object PrivateRoom : NewRoomVisibility()
        data object PublicRoom : NewRoomVisibility()

        /** Restricted join rule: members of the space can join freely. */
        data class SpaceMembers(val spaceId: String) : NewRoomVisibility()
    }

    suspend fun createRoom(
        name: String,
        topic: String?,
        visibility: NewRoomVisibility,
        isEncrypted: Boolean,
        isSpace: Boolean,
    ): String {
        val isPublic = visibility is NewRoomVisibility.PublicRoom
        val joinRule = (visibility as? NewRoomVisibility.SpaceMembers)?.let {
            JoinRule.Restricted(rules = listOf(AllowRule.RoomMembership(roomId = it.spaceId)))
        }
        return client.createRoom(CreateRoomParameters(
            name = name,
            topic = if (!topic.isNullOrEmpty()) topic else null,
            isEncrypted = isEncrypted,
            visibility = if (isPublic) RoomVisibility.Public else RoomVisibility.Private,
            preset = if (isPublic) RoomPreset.PUBLIC_CHAT else RoomPreset.PRIVATE_CHAT,
            joinRuleOverride = joinRule,
            isSpace = isSpace,
        ))
    }

    /**
     * Creates an Element-style video room. The FFI's `createRoom` can't set
     * a custom `m.room.create` type, so this calls the client-server API
     * directly.
     */
    suspend fun createVideoRoom(name: String, topic: String?, visibility: NewRoomVisibility): String {
        val session = session() ?: throw IOException("No session")
        val base = apiBase() ?: throw IOException("No API base URL")

        val isPublic = visibility is NewRoomVisibility.PublicRoom
        val body = JSONObject().apply {
            put("name", name)
            put("preset", if (isPublic) "public_chat" else "private_chat")
            put("creation_content", JSONObject().put("type", "io.element.video"))
            if (!topic.isNullOrEmpty()) put("topic", topic)
            (visibility as? NewRoomVisibility.SpaceMembers)?.let { space ->
                put("initial_state", JSONArray().put(JSONObject().apply {
                    put("type", "m.room.join_rules")
                    put("state_key", "")
                    put("content", JSONObject().apply {
                        put("join_rule", "restricted")
                        put("allow", JSONArray().put(JSONObject().apply {
                            put("type", "m.room_membership")
                            put("room_id", space.spaceId)
                        }))
                    })
                }))
            }
        }
        val (code, response) = httpRequest(
            method = "POST",
            url = "$base/_matrix/client/v3/createRoom",
            bearer = session.accessToken,
            body = body.toString(),
        ) ?: throw IOException("createRoom request failed")
        if (code != 200) throw IOException("createRoom failed with HTTP $code")
        return JSONObject(response ?: "").optString("room_id").ifEmpty {
            throw IOException("createRoom returned no room_id")
        }
    }

    /**
     * Video rooms among a space's children, via the hierarchy API — the
     * SDK's space listing doesn't surface `m.room.create` types.
     */
    suspend fun videoRoomIds(inSpace: String): Set<String> {
        val session = session() ?: return emptySet()
        val base = apiBase() ?: return emptySet()
        val (code, response) = httpRequest(
            method = "GET",
            url = "$base/_matrix/client/v1/rooms/${encodePath(inSpace)}/hierarchy?limit=200",
            bearer = session.accessToken,
        ) ?: return emptySet()
        if (code != 200) return emptySet()
        val rooms = runCatching { JSONObject(response ?: "").optJSONArray("rooms") }.getOrNull()
            ?: return emptySet()
        val videoTypes = setOf("io.element.video", "org.matrix.msc3417.call")
        return buildSet {
            for (i in 0 until rooms.length()) {
                val room = rooms.optJSONObject(i) ?: continue
                if (room.optString("room_type") in videoTypes) {
                    room.optString("room_id").takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }
    }

    /** Joins a room by `#alias:server` or `!roomid:server` and returns its ID. */
    suspend fun joinRoom(address: String): String {
        val room = client.joinRoomByIdOrAlias(roomIdOrAlias = address, serverNames = emptyList())
        return room.id()
    }

    // MARK: Encryption / verification

    val verificationState: VerificationState
        get() = client.encryption().verificationState()

    /**
     * Live verification-state updates. The SDK listener + task handle live for
     * the collection's duration (the Kotlin analogue of iOS returning the
     * bridge + handle as `retained`).
     */
    fun verificationStates(): Flow<VerificationState> = callbackFlow {
        // encryption()/verificationStateListener are blocking FFI; flowOn(IO)
        // keeps the listener setup + teardown off the collector's Main thread.
        val handle = client.encryption().verificationStateListener(object : VerificationStateListener {
            override fun onUpdate(status: VerificationState) {
                trySend(status)
            }
        })
        awaitClose {
            handle.cancel()
            handle.close()
        }
    }.flowOn(Dispatchers.IO)

    private var cachedVerificationController: SessionVerificationController? = null

    /**
     * One shared controller for the whole session. `getSessionVerificationController`
     * mints a NEW controller each call, and separate controllers get separate
     * delegates, so the active flow's accept/emoji events land on the incoming
     * watcher's delegate instead — stalling verification. Sharing one instance
     * keeps every event on the currently-set delegate. Call from the main thread.
     */
    suspend fun sessionVerificationController(): SessionVerificationController {
        cachedVerificationController?.let { return it }
        // getSessionVerificationController is blocking FFI; callers are on Main.
        val controller = withContext(Dispatchers.IO) { client.getSessionVerificationController() }
        cachedVerificationController = controller
        return controller
    }

    suspend fun recover(recoveryKey: String) {
        client.encryption().recover(recoveryKey)
    }

    /**
     * Reads a room's custom state event content — the FFI exposes no state
     * reader, so this hits the client-server API directly. null = absent/error.
     */
    suspend fun stateEventContent(roomId: String, type: String): JSONObject? {
        val session = session() ?: return null
        val base = apiBase() ?: return null
        val (code, response) = httpRequest(
            method = "GET",
            url = "$base/_matrix/client/v3/rooms/${encodePath(roomId)}/state/${encodePath(type)}",
            bearer = session.accessToken,
        ) ?: return null
        if (code != 200) return null
        return runCatching { JSONObject(response ?: "") }.getOrNull()
    }

    /**
     * Whether the signed-in user is allowed to send a given state event in a
     * room — used to hide edit controls (e.g. a space banner) the user has no
     * power to change, rather than letting them try and fail.
     */
    suspend fun canSendStateEvent(roomId: String, type: String): Boolean {
        val levels = withContext(Dispatchers.IO) {
            val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return@withContext null
            runCatching { room.getPowerLevels() }.getOrNull()
        } ?: return false
        return levels.canOwnUserSendState(StateEventType.Custom(value = type))
    }

    /**
     * Writes a room state event (empty state key). Returns true on 2xx — false
     * includes M_FORBIDDEN when the user lacks permission in the room.
     */
    suspend fun setStateEvent(roomId: String, type: String, content: JSONObject): Boolean {
        val session = session() ?: return false
        val base = apiBase() ?: return false
        val (code, _) = httpRequest(
            method = "PUT",
            url = "$base/_matrix/client/v3/rooms/${encodePath(roomId)}/state/${encodePath(type)}/",
            bearer = session.accessToken,
            body = content.toString(),
        ) ?: return false
        return code in 200..299
    }

    /**
     * One room's ephemeral state (read receipts + typing), read from regular
     * `/sync` — which, unlike the sliding-sync extensions, gives the FULL
     * receipt state (every reader, so avatars stack) and streams typing.
     * `since == null` is the initial snapshot; pass the returned `nextBatch`
     * with a long `timeout` to stream changes in real time. `receipts` is
     * `userId -> eventId` for readers present in THIS batch; `typing` is the
     * current typer list when a typing event was included (else null).
     * Returns null on failure (so we don't wipe existing receipts).
     */
    data class RoomEphemerals(
        val receipts: Map<String, String>,
        val typing: List<String>?,
        val nextBatch: String?,
    )

    suspend fun fetchRoomEphemerals(roomId: String, since: String?): RoomEphemerals? {
        val session = session() ?: return null
        val base = apiBase() ?: return null
        val filter = JSONObject().apply {
            put("room", JSONObject().apply {
                put("rooms", JSONArray().put(roomId))
                put("ephemeral", JSONObject()
                    .put("types", JSONArray().put("m.receipt").put("m.typing"))
                    .put("limit", 100))
                put("timeline", JSONObject().put("limit", 0))
                put("state", JSONObject().put("types", JSONArray()))
            })
            put("presence", JSONObject().put("types", JSONArray()))
            put("account_data", JSONObject().put("types", JSONArray()))
        }
        var url = "$base/_matrix/client/v3/sync" +
            "?filter=${URLEncoder.encode(filter.toString(), "UTF-8")}" +
            // Snapshot returns immediately; streaming long-polls up to 30s.
            "&timeout=${if (since == null) "0" else "30000"}"
        if (since != null) url += "&since=${URLEncoder.encode(since, "UTF-8")}"
        val (code, response) = httpRequest(
            method = "GET",
            url = url,
            bearer = session.accessToken,
            timeoutMillis = 45_000,
        ) ?: return null
        if (code != 200) return null
        val json = runCatching { JSONObject(response ?: "") }.getOrNull() ?: return null
        val nextBatch = json.optString("next_batch").takeIf { it.isNotEmpty() }
        val room = json.optJSONObject("rooms")?.optJSONObject("join")?.optJSONObject(roomId)
        val events = room?.optJSONObject("ephemeral")?.optJSONArray("events") ?: JSONArray()
        val receipts = mutableMapOf<String, String>()
        val receiptTs = mutableMapOf<String, Double>()
        var typing: List<String>? = null
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            when (event.optString("type")) {
                "m.receipt" -> {
                    val content = event.optJSONObject("content") ?: continue
                    for (eventId in content.keys()) {
                        val read = content.optJSONObject(eventId)?.optJSONObject("m.read") ?: continue
                        for (userId in read.keys()) {
                            val ts = read.optJSONObject(userId)?.optDouble("ts", 0.0) ?: 0.0
                            if (ts >= (receiptTs[userId] ?: -1.0)) {
                                receiptTs[userId] = ts
                                receipts[userId] = eventId
                            }
                        }
                    }
                }
                "m.typing" -> {
                    val ids = event.optJSONObject("content")?.optJSONArray("user_ids") ?: JSONArray()
                    typing = (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { s -> s.isNotEmpty() } }
                }
            }
        }
        return RoomEphemerals(receipts = receipts, typing = typing, nextBatch = nextBatch)
    }

    /**
     * One entry in `foxchat.social_links`: a labeled external link with an
     * optional icon (mxc or https URL).
     */
    data class SocialLink(
        val img: String? = null,
        val title: String,
        val link: String,
    ) {
        val id: String get() = "$title$link"
    }

    data class ProfileInfo(
        val displayName: String? = null,
        val avatarUrl: String? = null,
        val pronouns: String? = null,
        val bio: String? = null,
        val status: String? = null,
        val bannerUrl: String? = null,
        val timezone: String? = null,
        val socialLinks: List<SocialLink> = emptyList(),
    )

    /**
     * The client-API base URL, resolving `.well-known/matrix/client` once so
     * delegated deployments (server name ≠ client host) work. Falls back to the
     * session's homeserver URL if resolution fails.
     */
    private suspend fun apiBase(): String? {
        synchronized(profileCacheLock) { resolvedApiBase }?.let { return it }
        val session = session() ?: return null
        val raw = session.homeserverUrl.trimEnd('/')
        val resolved = resolveClientApiBase(raw) ?: raw
        synchronized(profileCacheLock) { resolvedApiBase = resolved }
        return resolved
    }

    /** Per-server client-API base cache for cross-server profile lookups. */
    private val serverBaseCache = mutableMapOf<String, String>()

    /**
     * Serializes the two profile-fetch URL caches (`resolvedApiBase` +
     * `serverBaseCache`). `fetchProfile` fans out concurrently — one coroutine
     * per call participant when the participant strip resolves everyone's
     * pronouns/avatars at once — and without a lock those concurrent map
     * mutations corrupt state (on iOS this was a heap-corrupting crash
     * mid-call). Never held across a suspension point.
     */
    private val profileCacheLock = Any()

    /**
     * The client-API base URL for a Matrix server name (the `domain` in
     * `@user:domain`), resolving its `.well-known/matrix/client` so we can query
     * a remote user's *own* homeserver directly. This matters because Matrix
     * federation doesn't relay custom extended-profile fields (bio/status/etc.)
     * — the origin server does return them, and profiles are world-readable.
     */
    private suspend fun serverApiBase(forUserId: String): String? {
        val colon = forUserId.indexOf(':')
        if (colon < 0) return apiBase()
        val server = forUserId.substring(colon + 1)
        synchronized(profileCacheLock) { serverBaseCache[server] }?.let { return it }
        val serverUrl = "https://$server"
        val resolved = resolveClientApiBase(serverUrl) ?: serverUrl
        synchronized(profileCacheLock) { serverBaseCache[server] = resolved }
        return resolved
    }

    suspend fun fetchProfile(userId: String): ProfileInfo? {
        val session = session() ?: return null
        val base = serverApiBase(forUserId = userId) ?: return null
        // Profiles are world-readable; only attach our token when the query goes
        // to our own homeserver, never leaking it to a remote server.
        val bearer = if (userId.endsWith(":$ownServerName")) session.accessToken else null
        val (code, response) = httpRequest(
            method = "GET",
            url = "$base/_matrix/client/v3/profile/${encodePath(userId)}",
            bearer = bearer,
        ) ?: return null
        if (code != 200) return null
        val json = runCatching { JSONObject(response ?: "") }.getOrNull() ?: return null

        var pronouns: String? = null
        for (key in pronounKeys) {
            val raw = json.optString(key).takeIf { json.opt(key) is String }
                ?: json.optJSONObject(key)?.optString("body")
            val value = raw?.trim()
            if (!value.isNullOrEmpty()) {
                pronouns = value
                break
            }
        }
        fun nonEmpty(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }
        val socialLinks = buildList {
            val entries = json.optJSONArray(socialLinksKey) ?: JSONArray()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val link = nonEmpty(entry.optString("link")) ?: continue
                val title = nonEmpty(entry.optString("title")) ?: link
                add(SocialLink(img = nonEmpty(entry.optString("img")), title = title, link = link))
            }
        }
        return ProfileInfo(
            displayName = json.opt("displayname") as? String,
            avatarUrl = json.opt("avatar_url") as? String,
            pronouns = pronouns,
            bio = nonEmpty(json.optJSONObject(bioKey)?.optString("body")
                ?: (json.opt(bioKey) as? String)),
            status = nonEmpty((json.opt(statusKey) as? String) ?: (json.opt("status_msg") as? String)),
            bannerUrl = (json.opt(bannerKey) as? String),
            timezone = nonEmpty((json.opt(timezoneKey) as? String)
                ?: (json.opt(timezoneKeyFallback) as? String)),
            socialLinks = socialLinks,
        )
    }

    /**
     * Sets one of the signed-in user's extended-profile fields (empty clears).
     * `value` may be a String or JSON (e.g. bio's `{"body": …}` as JSONObject,
     * social links as JSONArray).
     */
    suspend fun setProfileField(key: String, value: Any): Boolean {
        val session = session() ?: return false
        val base = apiBase() ?: return false
        val (code, _) = httpRequest(
            method = "PUT",
            url = "$base/_matrix/client/v3/profile/${encodePath(userId)}/${encodePath(key)}",
            bearer = session.accessToken,
            body = JSONObject().put(key, value).toString(),
        ) ?: return false
        return code in 200..299
    }

    /**
     * Sets our own presence `status_msg` — the field Commet-family clients read
     * as the user's status. Empty clears it. `presence: "online"` is required by
     * the endpoint.
     */
    suspend fun setPresenceStatus(statusMsg: String): Boolean {
        val session = session() ?: return false
        val base = apiBase() ?: return false
        val (code, _) = httpRequest(
            method = "PUT",
            url = "$base/_matrix/client/v3/presence/${encodePath(userId)}/status",
            bearer = session.accessToken,
            body = JSONObject().put("presence", "online").put("status_msg", statusMsg).toString(),
        ) ?: return false
        return code in 200..299
    }

    /**
     * Room IDs shared with `userId` (both of us joined), via MSC2666. Returns
     * [] when the homeserver doesn't support the endpoint. Paginates through
     * `next_batch_token`, capped so a broken server can't loop forever.
     */
    suspend fun mutualRooms(with: String): List<String> {
        val session = session() ?: return emptyList()
        val base = apiBase() ?: return emptyList()
        val joined = mutableListOf<String>()
        var batch: String? = null
        repeat(20) {
            var url = "$base/_matrix/client/unstable/uk.half-shot.msc2666/user/mutual_rooms" +
                "?user_id=${URLEncoder.encode(with, "UTF-8")}"
            batch?.let { url += "&batch_token=${URLEncoder.encode(it, "UTF-8")}" }
            val (code, response) = httpRequest(method = "GET", url = url, bearer = session.accessToken)
                ?: return joined
            if (code != 200) return joined
            val json = runCatching { JSONObject(response ?: "") }.getOrNull() ?: return joined
            val ids = json.optJSONArray("joined") ?: return joined
            for (i in 0 until ids.length()) {
                ids.optString(i).takeIf { it.isNotEmpty() }?.let { joined.add(it) }
            }
            batch = json.optString("next_batch_token").takeIf { it.isNotEmpty() } ?: return joined
        }
        return joined
    }

    /** A user's pronouns; null when unset. */
    suspend fun fetchPronouns(userId: String): String? = fetchProfile(userId)?.pronouns

    /**
     * Sets the signed-in user's own pronouns, writing the common keys (empty
     * string clears them). Returns true if at least one write succeeded.
     */
    suspend fun setPronouns(pronouns: String): Boolean {
        val session = session() ?: return false
        val base = apiBase() ?: return false
        var anySucceeded = false
        for (key in listOf("pronouns", "foxchat.pronouns")) {
            val (code, _) = httpRequest(
                method = "PUT",
                url = "$base/_matrix/client/v3/profile/${encodePath(userId)}/${encodePath(key)}",
                bearer = session.accessToken,
                body = JSONObject().put(key, pronouns).toString(),
            ) ?: continue
            if (code in 200..299) anySucceeded = true
        }
        return anySucceeded
    }

    // MARK: Session lifecycle

    /**
     * Ends the session: stop sync, flush key backup, log out server-side.
     * Call from the main thread — shares confinement with pause/resume so a
     * lifecycle pause/resume can't race this teardown.
     */
    suspend fun logOut() {
        syncMonitorJob?.cancel()
        sendQueueJob?.cancel()
        syncService?.stop()
        // Give recent message keys a chance to reach key backup before the
        // store is destroyed — otherwise those messages are unrecoverable on
        // other devices restored from backup. Best-effort and time-bounded so
        // a stuck/offline backup can't wedge sign-out.
        runWithTimeout(seconds = 8.0) {
            runCatching { client.encryption().waitForBackupUploadSteadyState(progressListener = null) }
        }
        runCatching { client.logout() }
    }

    companion object {
        /**
         * Custom profile keys clients use for pronouns (there's no single standard
         * yet — MSC4133 extended profiles are namespaced per client). Read all,
         * write the common ones so pronouns interoperate across clients.
         */
        private val pronounKeys = listOf(
            "foxchat.pronouns", "pronouns", "io.fsky.nyx.pronouns", "m.pronouns")

        // Commet-compatible extended-profile fields (MSC4133).
        const val bioKey = "chat.commet.profile_bio"
        const val statusKey = "chat.commet.profile_status"
        const val bannerKey = "chat.commet.profile_banner"

        /**
         * MSC4175 standard timezone key. Note: MSC4133 reserves the `m.*` namespace,
         * and servers that implement extended profiles but NOT MSC4175 (e.g.
         * Tuwunel) silently reject writes to it — so the field would never persist.
         */
        const val timezoneKey = "m.tz"

        /**
         * Non-reserved fallback so timezone survives on such servers. We write both
         * and read either (preferring the standard key).
         */
        const val timezoneKeyFallback = "chat.commet.profile_timezone"
        const val socialLinksKey = "foxchat.social_links"

        /**
         * Fire-and-forget background work kicked off during session lifecycle
         * (E2EE init after restore) that must outlive the caller.
         */
        private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Phase 1 of login: build a client for the homeserver and discover which
         * auth methods it supports.
         */
        suspend fun prepare(homeserver: String, context: Context): PendingLogin = withContext(Dispatchers.IO) {
            MatrixPlatform.initializeOnce()
            val sessionStore = SessionStore(context.applicationContext)
            val sessionId = UUID.randomUUID().toString()
            val (dataPath, cachePath) = sessionStore.makeSessionDirectories(sessionId)
            val passphrase = SessionStore.randomPassphrase()
            val sessionDelegate = SessionDelegate(sessionStore)
            val client = buildClient(
                homeserver = homeserver,
                dataPath = dataPath,
                cachePath = cachePath,
                passphrase = passphrase,
                sessionDelegate = sessionDelegate,
            )
            val details = client.homeserverLoginDetails()
            PendingLogin(
                client = client,
                sessionDelegate = sessionDelegate,
                dataPath = dataPath,
                cachePath = cachePath,
                passphrase = passphrase,
                supportsPassword = details.supportsPasswordLogin(),
                supportsOAuth = details.supportsOauthLogin(),
                supportsSso = details.supportsSsoLogin(),
            )
        }

        suspend fun logIn(
            homeserver: String,
            username: String,
            password: String,
            context: Context,
        ): LoginResult {
            val pending = prepare(homeserver, context)
            return pending.finishWithPassword(username = username, password = password)
        }

        suspend fun restore(token: RestorationToken, context: Context): MatrixService = withContext(Dispatchers.IO) {
            MatrixPlatform.initializeOnce()
            val sessionStore = SessionStore(context.applicationContext)
            // Resolve against the current container — the token's absolute
            // paths can be stale after a reinstall/restore.
            val (dataPath, cachePath) = sessionStore.currentSessionDirectories(token)
            val sessionDelegate = SessionDelegate(sessionStore)
            val client = buildClient(
                homeserver = token.session.homeserverUrl,
                dataPath = dataPath,
                cachePath = cachePath,
                passphrase = token.storePassphrase,
                sessionDelegate = sessionDelegate,
                // Use the version already recorded at login — no rediscovery.
                slidingSyncVersion = if (token.session.slidingSyncVersion == "native") {
                    SlidingSyncVersionBuilder.NATIVE
                } else {
                    SlidingSyncVersionBuilder.NONE
                },
            )
            client.restoreSession(token.session.ffiSession)
            // Let cross-signing/backup setup finish in the background so encrypted
            // history unlocks without user action where possible.
            detachedScope.launch { client.encryption().waitForE2eeInitializationTasks() }
            MatrixService(client = client, sessionDelegate = sessionDelegate)
        }

        internal fun fromAuthenticatedClient(client: Client, sessionDelegate: SessionDelegate): MatrixService =
            MatrixService(client = client, sessionDelegate = sessionDelegate)

        private suspend fun buildClient(
            homeserver: String,
            dataPath: String,
            cachePath: String,
            passphrase: String,
            sessionDelegate: SessionDelegate,
            slidingSyncVersion: SlidingSyncVersionBuilder = SlidingSyncVersionBuilder.DISCOVER_NATIVE,
        ): Client = ClientBuilder()
            .serverNameOrHomeserverUrl(serverNameOrUrl = homeserver)
            .sqliteStore(SqliteStoreBuilder(dataPath = dataPath, cachePath = cachePath)
                .passphrase(passphrase))
            // Login discovers the version; restore already knows it (from the
            // stored session), so it skips the network round-trip — the cold
            // launch no longer waits on the homeserver before showing cached data.
            .slidingSyncVersionBuilder(slidingSyncVersion)
            // Persists mid-session OAuth token refreshes to the secure store, so a
            // relaunch restores with the current (rotated) refresh token.
            .setSessionDelegate(sessionDelegate)
            // "Invisible crypto": set up cross-signing and key backup without
            // user ceremony, and self-heal UTDs from backup.
            .autoEnableCrossSigning(true)
            .autoEnableBackups(true)
            .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
            .enableShareHistoryOnInvite(true)
            .build()

        /**
         * Resolves `.well-known/matrix/client` → `m.homeserver.base_url` for a
         * server URL. Returns null if there's no delegation (caller falls back).
         */
        private suspend fun resolveClientApiBase(serverUrl: String): String? {
            val (code, response) = httpRequest(
                method = "GET",
                url = "${serverUrl.trimEnd('/')}/.well-known/matrix/client",
                bearer = null,
            ) ?: return null
            if (code != 200) return null
            val json = runCatching { JSONObject(response ?: "") }.getOrNull() ?: return null
            val base = json.optJSONObject("m.homeserver")?.optString("base_url")
                ?.takeIf { it.isNotEmpty() } ?: return null
            return base.trimEnd('/')
        }

        /** Percent-encodes one URL path segment (user/room/event IDs contain `:` etc). */
        private fun encodePath(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        /**
         * Minimal HTTP helper for the manual client-server API calls. Returns
         * (statusCode, bodyText) or null on a transport failure. Runs on IO.
         */
        private suspend fun httpRequest(
            method: String,
            url: String,
            bearer: String?,
            body: String? = null,
            timeoutMillis: Int = 15_000,
        ): Pair<Int, String?>? = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = timeoutMillis
                    conn.readTimeout = timeoutMillis
                    bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    if (body != null) {
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.doOutput = true
                        conn.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }
                    code to text
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }
}

/**
 * A client built for a homeserver, pre-authentication. Wraps all three auth
 * methods; whichever succeeds produces the (service, token) pair.
 */
class PendingLogin internal constructor(
    private val client: Client,
    private val sessionDelegate: SessionDelegate,
    private val dataPath: String,
    private val cachePath: String,
    private val passphrase: String,
    val supportsPassword: Boolean,
    val supportsOAuth: Boolean,
    val supportsSso: Boolean,
) {
    private var oauthData: OAuthAuthorizationData? = null
    private var ssoHandler: SsoHandler? = null

    companion object {
        // Reverse-DNS (dotted) scheme: MAS rejects single-word private-use schemes
        // like "discourse" during client registration (RFC 8252 §7.1).
        const val callbackScheme = "com.riiiiiiiley.discourse"
        const val oauthRedirectUrl = "$callbackScheme:/oauth-callback"
        const val ssoRedirectUrl = "$callbackScheme:/sso-callback"
    }

    suspend fun finishWithPassword(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        if (!supportsPassword) throw MatrixServiceException.PasswordLoginUnsupported()
        client.login(username = username, password = password,
            initialDeviceName = "Discourse (Android)", deviceId = null)
        finish()
    }

    /**
     * Creates a new account, then signs in. The Rust SDK exposes no
     * registration API, so this drives `/_matrix/client/v3/register` directly:
     * an initial call yields a UIA session, which we satisfy with the
     * registration token (this homeserver's only registration flow), plus any
     * trailing `m.login.dummy` stage. `inhibit_login` keeps registration from
     * minting a throwaway device — once the account exists we log in through the
     * SDK's own path, so the session/crypto setup is identical to a normal login.
     */
    suspend fun finishWithRegistration(
        username: String,
        password: String,
        registrationToken: String,
    ): LoginResult = withContext(Dispatchers.IO) {
        val base = client.homeserver().trimEnd('/')
        val registerUrl = "$base/_matrix/client/v3/register"

        var auth: JSONObject? = null
        var sessionId: String? = null
        // Bounded: a single token stage (optionally + dummy). The cap stops a
        // misbehaving server from looping forever.
        repeat(5) {
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("initial_device_display_name", "Discourse (Android)")
                put("inhibit_login", true)
                auth?.let { put("auth", it) }
            }
            val (code, response) = registerRequest(registerUrl, body.toString())
                ?: throw MatrixServiceException.RegistrationFailed("Couldn't reach the homeserver.")
            val json = runCatching { JSONObject(response ?: "") }.getOrNull()

            when (code) {
                200 -> {
                    // Account created (no device, thanks to inhibit_login) — sign in.
                    client.login(username = username, password = password,
                        initialDeviceName = "Discourse (Android)", deviceId = null)
                    return@withContext finish()
                }
                401 -> {
                    // User-interactive auth: hand back the registration token, then
                    // satisfy any trailing dummy stage once the token is accepted.
                    sessionId = json?.optString("session")?.ifBlank { null } ?: sessionId
                    val sid = sessionId ?: throw MatrixServiceException.RegistrationFailed(
                        "Registration couldn't start on this homeserver.")
                    val completed = json?.optJSONArray("completed")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    } ?: emptyList()
                    val stages = buildList {
                        json?.optJSONArray("flows")?.let { flows ->
                            for (i in 0 until flows.length()) {
                                flows.optJSONObject(i)?.optJSONArray("stages")?.let { st ->
                                    for (j in 0 until st.length()) add(st.optString(j))
                                }
                            }
                        }
                    }
                    if (stages.isNotEmpty() && !stages.contains("m.login.registration_token")) {
                        // A flow we don't drive from a native form (recaptcha/email/terms).
                        throw MatrixServiceException.RegistrationUnsupported()
                    }
                    auth = if (completed.contains("m.login.registration_token")) {
                        JSONObject().put("type", "m.login.dummy").put("session", sid)
                    } else {
                        JSONObject().put("type", "m.login.registration_token")
                            .put("token", registrationToken).put("session", sid)
                    }
                }
                else -> throw registrationError(json, code)
            }
        }
        throw MatrixServiceException.RegistrationFailed("Registration didn't complete. Please try again.")
    }

    /** POST JSON to the register endpoint; (status, body) or null on transport error. */
    private fun registerRequest(url: String, body: String): Pair<Int, String?>? =
        runCatching {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }
                code to text
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    /** Maps a `/register` error body to a friendly message. */
    private fun registrationError(json: JSONObject?, status: Int): MatrixServiceException {
        val serverMessage = json?.optString("error")?.ifBlank { null }
        return when (json?.optString("errcode")) {
            "M_USER_IN_USE" -> MatrixServiceException.RegistrationFailed("That username is already taken.")
            "M_INVALID_USERNAME" -> MatrixServiceException.RegistrationFailed(
                "That username isn't allowed. Use lowercase letters, numbers, and ._=-/")
            "M_WEAK_PASSWORD" -> MatrixServiceException.RegistrationFailed(
                serverMessage?.let { "Weak password: $it" } ?: "That password is too weak.")
            "M_EXCLUSIVE" -> MatrixServiceException.RegistrationFailed("That username is reserved.")
            "M_FORBIDDEN" -> MatrixServiceException.RegistrationFailed("That registration token isn't valid.")
            else -> MatrixServiceException.RegistrationFailed(
                serverMessage ?: "Registration failed (HTTP $status).")
        }
    }

    /** OAuth step 1: the browser URL to authorize at. */
    suspend fun startOAuth(): String {
        val data = client.urlForOauth(
            oauthConfiguration = OAuthConfiguration(
                clientName = "Discourse",
                redirectUri = oauthRedirectUrl,
                clientUri = "https://github.com/riiiiiiiley/Discourse",
                logoUri = null,
                tosUri = null,
                policyUri = null,
                staticRegistrations = emptyMap(),
            ),
            prompt = null,
            loginHint = null,
            deviceId = null,
            additionalScopes = null,
        )
        oauthData = data
        return data.loginUrl()
    }

    /** OAuth step 2: the `com.riiiiiiiley.discourse:/oauth-callback?...` URL from the browser. */
    suspend fun finishOAuth(callbackUrl: String): LoginResult = withContext(Dispatchers.IO) {
        client.loginWithOauthCallback(callbackUrl)
        finish()
    }

    suspend fun abortOAuth() {
        oauthData?.let { client.abortOauthAuth(it) }
        oauthData = null
    }

    /** Legacy SSO step 1. */
    suspend fun startSso(): String {
        val handler = client.startSsoLogin(redirectUrl = ssoRedirectUrl, idpId = null)
        ssoHandler = handler
        return handler.url()
    }

    /** Legacy SSO step 2. */
    suspend fun finishSso(callbackUrl: String): LoginResult {
        val handler = ssoHandler ?: throw IOException("No SSO flow in progress")
        handler.finish(callbackUrl)
        return finish()
    }

    private fun finish(): LoginResult {
        val session = client.session()
        val token = RestorationToken(
            session = RestorationToken.SessionData(session),
            storePassphrase = passphrase,
            dataPath = dataPath,
            cachePath = cachePath,
        )
        return LoginResult(
            service = MatrixService.fromAuthenticatedClient(client, sessionDelegate),
            token = token,
        )
    }
}
