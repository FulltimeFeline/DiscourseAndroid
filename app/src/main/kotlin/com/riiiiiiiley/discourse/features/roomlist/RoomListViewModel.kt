package com.riiiiiiiley.discourse.features.roomlist

import android.content.Context
import androidx.core.content.edit
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.NotificationManager
import com.riiiiiiiley.discourse.core.Platform
import com.riiiiiiiley.discourse.core.SpaceNameStore
import com.riiiiiiiley.discourse.models.RoomSummary
import com.riiiiiiiley.discourse.models.basicsOf
import com.riiiiiiiley.discourse.models.isVideoRoomType
import com.riiiiiiiley.discourse.models.updated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.SpaceListUpdate
import org.matrix.rustcomponents.sdk.SpaceRoom
import org.matrix.rustcomponents.sdk.SpaceService
import org.matrix.rustcomponents.sdk.SpaceServiceJoinedSpacesListener
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.SyncServiceState
import uniffi.matrix_sdk_ui.SpaceRoomListPaginationState
import java.io.File

/** Drives the sidebar: applies SDK room-list diffs and keeps unread counts current. */
class RoomListViewModel(
    private val service: MatrixService,
    context: Context,
) {
    data class SpaceItem(
        val id: String,
        val name: String,
        val avatarUrl: String? = null,
        val topic: String? = null,
    )

    data class SpaceChild(
        val id: String,
        val name: String,
        val isSpace: Boolean,
        val isVideoRoom: Boolean,
        val avatarUrl: String?,
        val topic: String?,
        val memberCount: ULong,
        val isJoined: Boolean,
        val via: List<String>,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("discourse", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** All state mutates on the main dispatcher (the iOS @MainActor analogue). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Internal mutable mirror of `rooms`; published as an immutable snapshot
    // once per diff batch / flush so collectors never see mid-batch state.
    private val roomsList = mutableListOf<RoomSummary>()
    private val _rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val rooms: StateFlow<List<RoomSummary>> = _rooms

    // StateFlow skips equal re-publishes, which is this port's version of the
    // iOS equality guards ("fires on every sync tick — don't republish").
    private fun publishRooms() {
        _rooms.value = roomsList.toList()
    }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    private val _syncBanner = MutableStateFlow<String?>(null)
    val syncBanner: StateFlow<String?> = _syncBanner

    /**
     * One-off action failures, auto-cleared after a few seconds. Separate from
     * `syncBanner`, which republishes sync state each tick and would clobber it.
     */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError
    private var actionErrorJob: Job? = null

    /** The sync service reports offline/error, as opposed to a one-off action failure. */
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    /** Restored content is on screen but this launch's first sync hasn't caught up. */
    val isCatchingUp: Boolean get() = !_isLoaded.value && roomsList.isNotEmpty()

    /**
     * SDK-ordered: the space diff stream is positional, so this stays index-aligned
     * with it. Display order lives in `orderedSpaces`.
     */
    private val spacesList = mutableListOf<SpaceItem>()
    private val _spaces = MutableStateFlow<List<SpaceItem>>(emptyList())
    val spaces: StateFlow<List<SpaceItem>> = _spaces

    /**
     * `spaces` in the user's drag-arranged order, persisted per account. Unknown
     * spaces go to the end.
     */
    private val _orderedSpaces = MutableStateFlow<List<SpaceItem>>(emptyList())
    val orderedSpaces: StateFlow<List<SpaceItem>> = _orderedSpaces

    private val _selectedSpaceId = MutableStateFlow<String?>(null)
    val selectedSpaceId: StateFlow<String?> = _selectedSpaceId

    /** Room IDs visible for the selected space; null = Home. */
    private val _visibleRoomIds = MutableStateFlow<Set<String>?>(null)
    val visibleRoomIds: StateFlow<Set<String>?> = _visibleRoomIds

    /**
     * Direct children of every joined top-level space, by space ID. Also keeps
     * Home to space-less rooms only.
     */
    private val spaceChildIdsMap = mutableMapOf<String, Set<String>>()
    private val _spaceChildIds = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val spaceChildIds: StateFlow<Map<String, Set<String>>> = _spaceChildIds

    /** Full child listings per space, including rooms not yet joined. */
    private val spaceChildrenMap = mutableMapOf<String, List<SpaceChild>>()
    private val _spaceChildren = MutableStateFlow<Map<String, List<SpaceChild>>>(emptyMap())
    val spaceChildren: StateFlow<Map<String, List<SpaceChild>>> = _spaceChildren

    private val videoRoomIdsSet = mutableSetOf<String>()
    private val _videoRoomIds = MutableStateFlow<Set<String>>(emptySet())
    val videoRoomIds: StateFlow<Set<String>> = _videoRoomIds

    private val _joiningRoomIds = MutableStateFlow<Set<String>>(emptySet())
    val joiningRoomIds: StateFlow<Set<String>> = _joiningRoomIds

    private val _joiningInviteIds = MutableStateFlow<Set<String>>(emptySet())
    val joiningInviteIds: StateFlow<Set<String>> = _joiningInviteIds

    /**
     * Union of all space children — anything here is hidden from Home. Memoized:
     * hot paths read it per render, so it's rebuilt only where `spaceChildIds` mutates.
     */
    private val _allSpaceChildIds = MutableStateFlow<Set<String>>(emptySet())
    val allSpaceChildIds: StateFlow<Set<String>> = _allSpaceChildIds

    private fun rebuildAllSpaceChildIds() {
        val union = spaceChildIdsMap.values.fold(mutableSetOf<String>()) { acc, ids ->
            acc.apply { addAll(ids) }
        }
        _allSpaceChildIds.value = union
        _spaceChildIds.value = spaceChildIdsMap.toMap()
        recomputeUnreadFlags()
    }

    /**
     * Rail unread state, stored rather than derived to avoid a per-space O(rooms)
     * scan on every rail render. StateFlow equality-guards so the rail only
     * re-renders when a flag actually flips.
     */
    private val _unreadSpaceIds = MutableStateFlow<Set<String>>(emptySet())
    val unreadSpaceIds: StateFlow<Set<String>> = _unreadSpaceIds

    private val _homeHasUnread = MutableStateFlow(false)
    val homeHasUnread: StateFlow<Boolean> = _homeHasUnread

    /**
     * Spaces (and Home) with a real mention waiting — a red rail badge, distinct
     * from a plain unread pip.
     */
    private val _mentionSpaceIds = MutableStateFlow<Set<String>>(emptySet())
    val mentionSpaceIds: StateFlow<Set<String>> = _mentionSpaceIds

    private val _homeHasMention = MutableStateFlow(false)
    val homeHasMention: StateFlow<Boolean> = _homeHasMention

    private fun recomputeUnreadFlags() {
        val spaceIds = mutableSetOf<String>()
        val mentionIds = mutableSetOf<String>()
        var homeUnread = false
        var homeMention = false
        val filed = _allSpaceChildIds.value
        for (room in roomsList) {
            if (!room.hasAnyUnread) continue
            val isHomeRoom = !room.isSpace && (room.isDirect || !filed.contains(room.id))
            if (isHomeRoom) {
                homeUnread = true
                if (room.isMentioned) homeMention = true
            }
            for ((spaceId, children) in spaceChildIdsMap) {
                if (children.contains(room.id)) {
                    spaceIds.add(spaceId)
                    if (room.isMentioned) mentionIds.add(spaceId)
                }
            }
        }
        _unreadSpaceIds.value = spaceIds
        _homeHasUnread.value = homeUnread
        _mentionSpaceIds.value = mentionIds
        _homeHasMention.value = homeMention
    }

    fun spaceHasUnread(spaceId: String): Boolean = _unreadSpaceIds.value.contains(spaceId)

    fun spaceHasMention(spaceId: String): Boolean = _mentionSpaceIds.value.contains(spaceId)

    /**
     * Publishes an action failure and clears it ~6s later. Cancel-and-replace
     * so a second failure gets its full display window.
     */
    private fun reportActionError(message: String) {
        _actionError.value = message
        actionErrorJob?.cancel()
        actionErrorJob = scope.launch {
            delay(6_000)
            _actionError.value = null
        }
    }

    /** Leaving the selected space falls back to Home. */
    suspend fun leave(roomId: String) {
        val room = ffiRoom(roomId) ?: return
        try {
            room.leave()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val name = roomIndexById[roomId]?.let { roomsList[it].name } ?: roomId
            reportActionError("Couldn't leave $name: ${error.message}")
            return
        }
        if (_selectedSpaceId.value == roomId) {
            selectSpace(null)
        }
    }

    // MARK: Go-menu navigation

    /**
     * Rooms visible in the sidebar for the current space, most-recent-activity
     * first — the same order the sidebar renders. Drives Next/Previous navigation.
     */
    val orderedVisibleRoomIds: List<String>
        get() {
            val filed = _allSpaceChildIds.value
            val visible = _visibleRoomIds.value
            return roomsList.filter { room ->
                if (room.isSpace || room.isInvited) return@filter false
                if (visible != null) return@filter visible.contains(room.id)
                room.isDirect || !filed.contains(room.id)
            }
                .sortedByDescending { it.lastActivity ?: Long.MIN_VALUE }
                .map { it.id }
        }

    /**
     * The room `delta` steps away in sidebar order, clamped to the ends. With no
     * current room, steps in from the nearest end.
     */
    fun roomIdOffsetBy(delta: Int, from: String?): String? {
        val ids = orderedVisibleRoomIds
        if (ids.isEmpty()) return null
        val index = from?.let { ids.indexOf(it).takeIf { i -> i >= 0 } }
            ?: return if (delta > 0) ids.first() else ids.last()
        val target = (index + delta).coerceIn(0, ids.size - 1)
        if (target == index) return null
        return ids[target]
    }

    /** The next/previous unread room in sidebar order, wrapping around. */
    fun nextUnreadRoomId(from: String?, forward: Boolean): String? {
        val ids = orderedVisibleRoomIds
        if (ids.isEmpty()) return null
        val unread = roomsList.filter { it.hasAnyUnread }.map { it.id }.toSet()
        if (unread.isEmpty()) return null
        val start = from?.let { ids.indexOf(it).takeIf { i -> i >= 0 } }
            ?: if (forward) -1 else ids.size
        val step = if (forward) 1 else -1
        for (offset in 1..ids.size) {
            val index = ((start + step * offset) % ids.size + ids.size) % ids.size
            val id = ids[index]
            if (id != from && unread.contains(id)) return id
        }
        return null
    }

    /**
     * The room open in the main window. Its unreads clear locally the moment it's
     * selected and stay cleared while it's on screen — waiting for the server echo
     * makes pips and badges lag or flicker.
     */
    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId

    fun setActiveRoom(roomId: String?) {
        _activeRoomId.value = roomId
        if (roomId != null) clearUnreadLocally(listOf(roomId))
    }

    /**
     * FFI rooms, index-aligned with `rooms`. Diffs are positional, so both arrays
     * mutate in lockstep.
     */
    private val ffiRooms = mutableListOf<Room>()

    /**
     * Room ID → index into `rooms`/`ffiRooms`, rebuilt after every diff batch so
     * lookups skip O(n) scans.
     */
    private var roomIndexById: Map<String, Int> = emptyMap()

    /** Bridges/controllers/TaskHandles that must stay alive for subscriptions to fire. */
    private var retained = mutableListOf<Any>()
    private val streamJobs = mutableListOf<Job>()
    private var spaceService: SpaceService? = null

    /** Retains the SpaceRoomList (and its updates subscription) per visited space. */
    private val spaceRoomLists = mutableMapOf<String, Any>()

    /**
     * Synchronous re-entrancy guard. `retained` isn't populated until after several
     * suspensions, so two callers racing into `start()` at launch would both pass a
     * `retained`-based check and double-subscribe. Set before the first suspension;
     * reset on failure so the retry path can run again.
     */
    private var hasStarted = false

    suspend fun start() {
        if (hasStarted) return
        hasStarted = true
        // Paint the last run's sidebar before sync produces anything; the first
        // diff batch supersedes it.
        restoreSnapshot()
        try {
            service.startSync()
            val roomListService = service.roomListService ?: return
            // allRooms() + the dynamic-adapter/loading-state wiring are blocking
            // FFI. start() runs from a Main-dispatched LaunchedEffect, so doing
            // them inline froze the main thread right after login/launch (ANR).
            // Build the whole sliding-sync pipeline off-main; the diff/loading
            // collectors below still hop back to Main via `scope`.
            lateinit var entriesBridge: EntriesBridge
            lateinit var loadingBridge: LoadingBridge
            withContext(Dispatchers.IO) {
                val roomList = roomListService.allRooms()
                entriesBridge = EntriesBridge()
                val result = roomList.entriesWithDynamicAdapters(200u, entriesBridge)
                val controller = result.controller()
                // deduplicateVersions: after a room upgrade, hide the tombstoned
                // room and show only its replacement.
                controller.setFilter(RoomListEntriesDynamicFilterKind.All(filters = listOf(
                    RoomListEntriesDynamicFilterKind.NonLeft,
                    RoomListEntriesDynamicFilterKind.DeduplicateVersions,
                )))
                loadingBridge = LoadingBridge()
                val loadingResult = roomList.loadingState(loadingBridge)
                retained = mutableListOf(roomList, entriesBridge, result, controller,
                                         result.entriesStream(), loadingBridge, loadingResult)
            }

            streamJobs.add(scope.launch {
                for (diffs in entriesBridge.channel) {
                    // basicsOf reads 4 FFI getters per room (id/displayName/
                    // avatarUrl/topic); a Reset re-basicsOf's the whole list.
                    // Materialize those off-main, keyed by room identity, then
                    // do the index-aligned rooms/ffiRooms splice on Main.
                    val basics = withContext(Dispatchers.IO) { materializeBasics(diffs) }
                    apply(diffs, basics)
                }
            })
            streamJobs.add(scope.launch {
                for (state in loadingBridge.channel) {
                    if (state is RoomListLoadingState.Loaded) _isLoaded.value = true
                }
            })
            streamJobs.add(scope.launch {
                service.syncStateFlow.collect { state ->
                    val banner: String? = when (state) {
                        SyncServiceState.RUNNING, SyncServiceState.IDLE,
                        SyncServiceState.TERMINATED,
                        -> null
                        SyncServiceState.OFFLINE -> "Offline — reconnecting…"
                        SyncServiceState.ERROR -> "Sync error — retrying…"
                    }
                    // Fires on every sync tick — StateFlow drops same-value writes.
                    _syncBanner.value = banner
                    val reconnecting = banner != null
                    if (_isReconnecting.value != reconnecting) {
                        _isReconnecting.value = reconnecting
                        // Send failures while offline disable the affected rooms'
                        // send queues; re-enable them on reconnect.
                        if (!reconnecting) {
                            scope.launch { service.enableAllSendQueues() }
                        }
                    }
                }
            })
            startSpaces()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _syncBanner.value = "Failed to start sync: ${error.message}"
            // This attempt never established `retained`; let the retry (and any
            // later caller) past the guard.
            hasStarted = false
            // Tracked so stop() can cancel it. Each failed attempt reschedules,
            // making the guard-and-retry a de-facto backoff loop.
            startRetryJob?.cancel()
            startRetryJob = scope.launch {
                delay(10_000)
                if (retained.isEmpty()) start()
            }
        }
    }

    private var startRetryJob: Job? = null

    // MARK: Spaces

    private suspend fun startSpaces() {
        // spaceService()/subscribe/topLevelJoinedSpaces are blocking FFI; keep
        // them off the main thread (startSpaces runs in start()'s call chain).
        val spaceService = withContext(Dispatchers.IO) { service.client.spaceService() }
        this.spaceService = spaceService
        val bridge = JoinedSpacesBridge()
        retained.add(bridge)
        retained.add(withContext(Dispatchers.IO) {
            spaceService.subscribeToTopLevelJoinedSpaces(bridge)
        })
        streamJobs.add(scope.launch {
            for (diffs in bridge.channel) {
                // spaceItem reads roomId/displayName/avatarUrl/topic FFI per
                // space; materialize off-main, then splice on Main.
                val items = withContext(Dispatchers.IO) { materializeSpaceItems(diffs) }
                applySpaceDiffs(diffs, items)
            }
        })
        val topLevel = withContext(Dispatchers.IO) { spaceService.topLevelJoinedSpaces() }
        val topLevelItems = withContext(Dispatchers.IO) {
            java.util.IdentityHashMap<SpaceRoom, SpaceItem>().apply {
                topLevel.forEach { if (!containsKey(it)) put(it, spaceItem(it)) }
            }
        }
        spacesList.clear()
        spacesList.addAll(topLevel.map { topLevelItems[it] ?: spaceItem(it) })
        _spaces.value = spacesList.toList()
        rebuildOrderedSpaces()
        refreshAllSpaceChildren()
    }

    // MARK: Rail arrangement

    private val spaceOrderKey: String get() = "spaceOrder|${service.userId}"

    private fun savedSpaceOrder(): List<String> {
        val raw = prefs.getString(spaceOrderKey, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    private fun rebuildOrderedSpaces() {
        val saved = savedSpaceOrder()
        if (saved.isEmpty()) {
            _orderedSpaces.value = spacesList.toList()
            return
        }
        val position = saved.withIndex().associate { (index, id) -> id to index }
        val ordered = spacesList.withIndex()
            .sortedBy { (offset, item) -> position[item.id] ?: (saved.size + offset) }
            .map { it.value }
        _orderedSpaces.value = ordered
    }

    /**
     * Moves a space to just before `targetId` (or the end when null) and persists
     * the arrangement per account.
     */
    fun moveSpace(spaceId: String, beforeId: String?) {
        if (spaceId == beforeId) return
        val arranged = _orderedSpaces.value.toMutableList()
        val from = arranged.indexOfFirst { it.id == spaceId }
        if (from < 0) return
        val item = arranged.removeAt(from)
        val to = beforeId?.let { target -> arranged.indexOfFirst { it.id == target } } ?: -1
        if (beforeId != null && to >= 0) {
            arranged.add(to, item)
        } else {
            arranged.add(item)
        }
        _orderedSpaces.value = arranged
        prefs.edit { putString(spaceOrderKey, json.encodeToString(arranged.map { it.id })) }
    }

    /** Selects a space (null = Home) and resolves which rooms it contains. */
    suspend fun selectSpace(spaceId: String?) {
        _selectedSpaceId.value = spaceId
        if (spaceId == null) {
            _visibleRoomIds.value = null
            return
        }
        // Show the cached (or empty) set until the fetch resolves, not the previous
        // space's rooms.
        _visibleRoomIds.value = spaceChildIdsMap[spaceId] ?: emptySet()
        val children = loadSpaceChildren(spaceId)
        if (_selectedSpaceId.value != spaceId) return
        // A failed load keeps the cached/empty set rather than blanking a
        // snapshot-restored space.
        _visibleRoomIds.value = if (children != null) {
            children.filter { !it.isSpace }.map { it.id }.toSet()
        } else {
            spaceChildIdsMap[spaceId] ?: emptySet()
        }
    }

    /** Fetches (and caches) the direct children of one space. */
    private suspend fun loadSpaceChildren(spaceId: String): List<SpaceChild>? {
        val spaceService = spaceService ?: return null
        return try {
            // spaceRoomList + the pagination loop + per-child getters are blocking
            // FFI (and paginate() hits the network). This runs in start()'s
            // Main-dispatched chain, so build the child list off-main and only
            // splice the state on Main below.
            val (list, children) = withContext(Dispatchers.IO) {
                val list = spaceService.spaceRoomList(spaceId)
                // Drive pagination to completion. The list starts out .loading, so
                // wait through that rather than bailing early.
                var guardCounter = 0
                paging@ while (guardCounter < 200) {
                    guardCounter++
                    when (val state = list.paginationState()) {
                        is SpaceRoomListPaginationState.Idle -> {
                            if (state.endReached) break@paging
                            list.paginate()
                        }
                        is SpaceRoomListPaginationState.Loading -> delay(50)
                    }
                }
                val ffiChildren = list.rooms()
                // The space listing reports plain `room` even for video rooms; the
                // hierarchy endpoint is the only source of the type.
                val hierarchyVideoIds = service.videoRoomIds(inSpace = spaceId)
                list to ffiChildren.map { child ->
                    SpaceChild(
                        id = child.roomId,
                        name = child.displayName,
                        isSpace = child.roomType is org.matrix.rustcomponents.sdk.RoomType.Space,
                        isVideoRoom = isVideoRoomType(child.roomType) ||
                            hierarchyVideoIds.contains(child.roomId),
                        avatarUrl = child.avatarUrl,
                        topic = child.topic,
                        memberCount = child.numJoinedMembers,
                        isJoined = child.state == org.matrix.rustcomponents.sdk.Membership.JOINED,
                        via = child.via,
                    )
                }
            }
            spaceRoomLists[spaceId] = list
            // Equality-guarded (via StateFlow): these refresh on every space diff,
            // and a no-op write would still invalidate every sidebar view.
            val ids = children.map { it.id }.toSet()
            if (spaceChildIdsMap[spaceId] != ids) {
                spaceChildIdsMap[spaceId] = ids
                rebuildAllSpaceChildIds()
            }
            if (spaceChildrenMap[spaceId] != children) {
                spaceChildrenMap[spaceId] = children
                _spaceChildren.value = spaceChildrenMap.toMap()
            }
            // At cold start the restored space can be selected before sync delivers
            // any children; refresh it so it doesn't sit empty until reselected.
            if (_selectedSpaceId.value == spaceId) {
                val visible = children.filter { !it.isSpace }.map { it.id }.toSet()
                _visibleRoomIds.value = visible
            }
            val videoIds = children.filter { it.isVideoRoom }.map { it.id }.toSet()
            if (!videoRoomIdsSet.containsAll(videoIds)) {
                videoRoomIdsSet.addAll(videoIds)
                _videoRoomIds.value = videoRoomIdsSet.toSet()
                // Flag already-loaded rows; new ones pick it up in refreshDetails.
                var changed = false
                for (index in roomsList.indices) {
                    val room = roomsList[index]
                    if (videoRoomIdsSet.contains(room.id) && !room.isVideoRoom) {
                        roomsList[index] = room.copy(isVideoRoom = true)
                        changed = true
                    }
                }
                if (changed) publishRooms()
            }
            children
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Refreshes every space's child list so Home can exclude them. Deduped: an
     * in-flight run covers the same ground.
     */
    suspend fun refreshAllSpaceChildren() {
        if (isRefreshingSpaceChildren) return
        isRefreshingSpaceChildren = true
        try {
            for (space in spacesList.toList()) {
                loadSpaceChildren(spaceId = space.id)
            }
        } finally {
            isRefreshingSpaceChildren = false
        }
    }

    private var isRefreshingSpaceChildren = false
    private var spaceRefreshJob: Job? = null

    /**
     * Space diffs arrive in bursts and each refresh is a full crawl (a round-trip
     * per space), so coalesce to one trailing run ~2s after the last diff.
     */
    private fun scheduleSpaceChildrenRefresh() {
        spaceRefreshJob?.cancel()
        spaceRefreshJob = scope.launch {
            delay(2_000)
            refreshAllSpaceChildren()
        }
    }

    suspend fun toggleRoom(roomId: String, inSpace: String) {
        if (spaceChildIdsMap[inSpace]?.contains(roomId) == true) {
            val spaceService = spaceService ?: return
            try {
                spaceService.removeChildFromSpace(roomId, inSpace)
                spaceChildIdsMap[inSpace] = (spaceChildIdsMap[inSpace] ?: emptySet()) - roomId
                rebuildAllSpaceChildIds()
                if (_selectedSpaceId.value == inSpace) {
                    _visibleRoomIds.value = spaceChildIdsMap[inSpace]
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Likely missing power level; leave state unchanged but say so.
                reportActionError("Couldn't remove from space: ${error.message}")
            }
        } else {
            fileRoom(roomId, intoSpace = inSpace)
        }
    }

    /**
     * Files a room into a space. Retries because a just-created room takes a sync
     * round-trip to exist locally, and filing before that throws.
     */
    suspend fun fileRoom(roomId: String, intoSpace: String) {
        val spaceService = spaceService ?: return
        for (attempt in 0 until 10) {
            try {
                spaceService.addChildToSpace(roomId, intoSpace)
                // Inherit the space's roles (power levels + role labels).
                runCatching { service.copySpaceRolesToRoom(spaceId = intoSpace, roomId = roomId) }
                spaceChildIdsMap[intoSpace] = (spaceChildIdsMap[intoSpace] ?: emptySet()) + roomId
                rebuildAllSpaceChildIds()
                if (_selectedSpaceId.value == intoSpace) {
                    selectSpace(intoSpace)
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt == 9) {
                    reportActionError("Couldn't add to space: ${error.message}")
                    return
                }
                delay(500)
            }
        }
    }

    /**
     * Pre-computes `spaceItem` (FFI getters) for every `SpaceRoom` a diff batch
     * introduces, off-main, keyed by identity; `applySpaceDiffs` reads it back
     * on Main.
     */
    private fun materializeSpaceItems(
        diffs: List<SpaceListUpdate>,
    ): Map<SpaceRoom, SpaceItem> {
        val out = java.util.IdentityHashMap<SpaceRoom, SpaceItem>()
        fun put(room: SpaceRoom) {
            if (!out.containsKey(room)) out[room] = spaceItem(room)
        }
        for (diff in diffs) {
            when (diff) {
                is SpaceListUpdate.Append -> diff.values.forEach(::put)
                is SpaceListUpdate.PushFront -> put(diff.value)
                is SpaceListUpdate.PushBack -> put(diff.value)
                is SpaceListUpdate.Insert -> put(diff.value)
                is SpaceListUpdate.Set -> put(diff.value)
                is SpaceListUpdate.Reset -> diff.values.forEach(::put)
                else -> Unit
            }
        }
        return out
    }

    private fun applySpaceDiffs(diffs: List<SpaceListUpdate>, items: Map<SpaceRoom, SpaceItem>) {
        fun itemFor(room: SpaceRoom): SpaceItem = items[room] ?: spaceItem(room)
        for (diff in diffs) {
            when (diff) {
                is SpaceListUpdate.Append -> spacesList.addAll(diff.values.map(::itemFor))
                is SpaceListUpdate.Clear -> spacesList.clear()
                is SpaceListUpdate.PushFront -> spacesList.add(0, itemFor(diff.value))
                is SpaceListUpdate.PushBack -> spacesList.add(itemFor(diff.value))
                is SpaceListUpdate.PopFront -> if (spacesList.isNotEmpty()) spacesList.removeAt(0)
                is SpaceListUpdate.PopBack ->
                    if (spacesList.isNotEmpty()) spacesList.removeAt(spacesList.size - 1)
                is SpaceListUpdate.Insert ->
                    spacesList.add(minOf(diff.index.toInt(), spacesList.size),
                                   itemFor(diff.value))
                is SpaceListUpdate.Set -> {
                    val i = diff.index.toInt()
                    if (i in spacesList.indices) spacesList[i] = itemFor(diff.value)
                }
                is SpaceListUpdate.Remove -> {
                    val i = diff.index.toInt()
                    if (i in spacesList.indices) spacesList.removeAt(i)
                }
                is SpaceListUpdate.Truncate -> {
                    val length = diff.length.toInt()
                    while (spacesList.size > length) spacesList.removeAt(spacesList.size - 1)
                }
                is SpaceListUpdate.Reset -> {
                    spacesList.clear()
                    spacesList.addAll(diff.values.map(::itemFor))
                }
            }
        }
        _spaces.value = spacesList.toList()
        rebuildOrderedSpaces()
        val selected = _selectedSpaceId.value
        if (selected != null && spacesList.none { it.id == selected }) {
            scope.launch { selectSpace(null) }
        }
        scheduleSpaceChildrenRefresh()
    }

    private fun spaceItem(room: SpaceRoom): SpaceItem =
        SpaceItem(id = room.roomId, name = room.displayName, avatarUrl = room.avatarUrl,
                  topic = room.topic)

    /**
     * The space banner mxc (Commet's `page.codeberg.everypizza.room.banner`
     * state event), fetched lazily when a space is opened.
     */
    suspend fun spaceBannerUrl(forSpace: String): String? {
        val content = service.stateEventContent(
            roomId = forSpace, type = "page.codeberg.everypizza.room.banner")
        return content?.opt("url") as? String
    }

    fun stop() {
        streamJobs.forEach { it.cancel() }
        streamJobs.clear()
        retained = mutableListOf()
        hasStarted = false
        invitableRoomIdsSet.clear()
        _invitableRoomIds.value = emptySet()
        invitePermissionChecked.clear()
        manageableSpaceIdsSet.clear()
        _manageableSpaceIds.value = emptySet()
        spaceManageChecked.clear()
        moveableRoomIdsSet.clear()
        _moveableRoomIds.value = emptySet()
        movePermissionChecked.clear()
        spaceRefreshJob?.cancel()
        spaceRefreshJob = null
        startRetryJob?.cancel()
        startRetryJob = null
        actionErrorJob?.cancel()
        actionErrorJob = null
        // Logout deletes the snapshot right after stopping — a pending debounced
        // write must not fire afterward.
        snapshotJob?.cancel()
        snapshotJob = null
    }

    fun ffiRoom(withId: String): Room? {
        val index = roomIndexById[withId] ?: return null
        return ffiRooms.getOrNull(index)
    }

    private fun rebuildRoomIndex() {
        // First occurrence wins, matching an indexOfFirst it replaces.
        val index = mutableMapOf<String, Int>()
        for ((i, room) in roomsList.withIndex()) {
            index.putIfAbsent(room.id, i)
        }
        roomIndexById = index
    }

    // MARK: Diff application

    /**
     * Pre-computes `basicsOf` (4 FFI getters each) for every `Room` a diff
     * batch introduces, off-main, keyed by object identity. `apply`/`add` read
     * the result back on Main so the index-aligned splice never crosses JNI.
     */
    private fun materializeBasics(
        diffs: List<RoomListEntriesUpdate>,
    ): Map<Room, RoomSummary> {
        val out = java.util.IdentityHashMap<Room, RoomSummary>()
        fun put(room: Room) {
            if (!out.containsKey(room)) out[room] = RoomSummary.basicsOf(room)
        }
        for (diff in diffs) {
            when (diff) {
                is RoomListEntriesUpdate.Append -> diff.values.forEach(::put)
                is RoomListEntriesUpdate.PushFront -> put(diff.value)
                is RoomListEntriesUpdate.PushBack -> put(diff.value)
                is RoomListEntriesUpdate.Insert -> put(diff.value)
                is RoomListEntriesUpdate.Set -> put(diff.value)
                is RoomListEntriesUpdate.Reset -> diff.values.forEach(::put)
                else -> Unit
            }
        }
        return out
    }

    private fun apply(diffs: List<RoomListEntriesUpdate>, basics: Map<Room, RoomSummary>) {
        // Snapshot placeholders have no FFI backing, so positional diffs can't apply
        // to them. A leading .reset replaces the array wholesale (and carries
        // restored summaries into the fresh rows); anything else must start from an
        // empty baseline, or `rooms` and `ffiRooms` diverge.
        if (isShowingRestoredSnapshot) {
            isShowingRestoredSnapshot = false
            if (diffs.firstOrNull() !is RoomListEntriesUpdate.Reset) {
                roomsList.clear()
                rebuildRoomIndex()
            }
        }
        // Fall back to a (rare) on-main basicsOf only if the pre-map missed a
        // room — keeps rooms/ffiRooms aligned rather than skipping the row.
        fun summaryFor(room: Room): RoomSummary =
            basics[room] ?: RoomSummary.basicsOf(room)
        for (diff in diffs) {
            when (diff) {
                is RoomListEntriesUpdate.Append ->
                    diff.values.forEach { add(it, at = roomsList.size, summary = summaryFor(it)) }
                is RoomListEntriesUpdate.Clear -> {
                    ffiRooms.clear()
                    roomsList.clear()
                }
                is RoomListEntriesUpdate.PushFront -> add(diff.value, at = 0, summary = summaryFor(diff.value))
                is RoomListEntriesUpdate.PushBack -> add(diff.value, at = roomsList.size, summary = summaryFor(diff.value))
                is RoomListEntriesUpdate.PopFront -> {
                    if (roomsList.isEmpty() || ffiRooms.isEmpty()) continue
                    ffiRooms.removeAt(0)
                    roomsList.removeAt(0)
                }
                is RoomListEntriesUpdate.PopBack -> {
                    if (roomsList.isEmpty() || ffiRooms.isEmpty()) continue
                    ffiRooms.removeAt(ffiRooms.size - 1)
                    roomsList.removeAt(roomsList.size - 1)
                }
                is RoomListEntriesUpdate.Insert -> add(diff.value, at = diff.index.toInt(), summary = summaryFor(diff.value))
                is RoomListEntriesUpdate.Set -> {
                    val i = diff.index.toInt()
                    // Both arrays are checked: a restored snapshot fills `rooms` but
                    // not `ffiRooms`, so during that window they can differ in length
                    // and an ffiRooms[i] on a rooms-valid index would crash.
                    if (i !in roomsList.indices || i !in ffiRooms.indices) continue
                    ffiRooms[i] = diff.value
                    // Same room: keep the populated summary. Resetting to basics blanks
                    // unreads/preview for a beat, flickering and re-sorting the row.
                    if (roomsList[i].id != diff.value.id()) {
                        roomsList[i] = summaryFor(diff.value)
                    }
                    refreshDetails(diff.value)
                }
                is RoomListEntriesUpdate.Remove -> {
                    val i = diff.index.toInt()
                    if (i !in roomsList.indices || i !in ffiRooms.indices) continue
                    ffiRooms.removeAt(i)
                    roomsList.removeAt(i)
                }
                is RoomListEntriesUpdate.Truncate -> {
                    val length = diff.length.toInt()
                    if (roomsList.size <= length || ffiRooms.size < length) continue
                    while (ffiRooms.size > length) ffiRooms.removeAt(ffiRooms.size - 1)
                    while (roomsList.size > length) roomsList.removeAt(roomsList.size - 1)
                }
                is RoomListEntriesUpdate.Reset -> {
                    // Carry known summaries over so the list doesn't blank and reshuffle
                    // while details reload.
                    val known = mutableMapOf<String, RoomSummary>()
                    for (room in roomsList) known.putIfAbsent(room.id, room)
                    ffiRooms.clear()
                    ffiRooms.addAll(diff.values)
                    roomsList.clear()
                    roomsList.addAll(diff.values.map { known[it.id()] ?: summaryFor(it) })
                    diff.values.forEach(::refreshDetails)
                }
            }
        }
        rebuildRoomIndex()
        publishRooms()
        updateUnreadTotal()
        recomputeUnreadFlags()
        subscribeForPreviews()
        scheduleSnapshotWrite()
    }

    /**
     * Previews come from the room-list sync's own timeline limit
     * (`withRoomListTimelineLimit`), so we don't blanket-subscribe every room.
     * Subscribing hundreds of rooms produced a huge per-sync request and
     * suppressed the live receipts/typing extensions (which only stream for
     * subscribed rooms). Only the open room is subscribed (by the timeline),
     * which keeps those ephemeral updates flowing.
     */
    private fun subscribeForPreviews() = Unit

    /**
     * Rooms and spaces the current user may invite to. Filled lazily by the
     * sidebar as rows/menus appear — power levels are async, but context menus
     * build synchronously. Fail closed: a room stays absent until confirmed.
     */
    private val invitableRoomIdsSet = mutableSetOf<String>()
    private val _invitableRoomIds = MutableStateFlow<Set<String>>(emptySet())
    val invitableRoomIds: StateFlow<Set<String>> = _invitableRoomIds
    private val invitePermissionChecked = mutableSetOf<String>()

    suspend fun refreshInvitePermission(forRoomId: String) {
        if (!invitePermissionChecked.add(forRoomId)) return
        val room = ffiRoom(forRoomId)
            ?: withContext(Dispatchers.IO) { runCatching { service.client.getRoom(forRoomId) }.getOrNull() }
        val levels = try {
            // getPowerLevels() is a suspend FFI that polls the Rust runtime;
            // it fires per row appearing during scroll, so keep it off Main.
            withContext(Dispatchers.IO) { room?.getPowerLevels() }
        } catch (error: CancellationException) {
            // A cancelled row effect (fast fling) must not poison the
            // fail-closed cache for the rest of the session.
            invitePermissionChecked.remove(forRoomId)
            throw error
        } catch (_: Exception) {
            null
        }
        if (levels == null) return
        if (levels.canOwnUserInvite()) {
            invitableRoomIdsSet.add(forRoomId)
            _invitableRoomIds.value = invitableRoomIdsSet.toSet()
        }
    }

    /**
     * Spaces whose child list this user may edit (send `m.space.child`) — i.e.
     * spaces a room can actually be moved in/out of. Filled lazily like
     * `invitableRoomIds`; a space stays absent until confirmed (fail closed), so
     * the Spaces menu only offers spaces the move would actually succeed in.
     */
    private val manageableSpaceIdsSet = mutableSetOf<String>()
    private val _manageableSpaceIds = MutableStateFlow<Set<String>>(emptySet())
    val manageableSpaceIds: StateFlow<Set<String>> = _manageableSpaceIds
    private val spaceManageChecked = mutableSetOf<String>()

    suspend fun refreshSpaceManagePermission(spaceId: String) {
        if (!spaceManageChecked.add(spaceId)) return
        val room = ffiRoom(spaceId)
            ?: withContext(Dispatchers.IO) { runCatching { service.client.getRoom(spaceId) }.getOrNull() }
        val levels = try {
            withContext(Dispatchers.IO) { room?.getPowerLevels() }
        } catch (error: CancellationException) {
            spaceManageChecked.remove(spaceId)
            throw error
        } catch (_: Exception) {
            null
        }
        if (levels == null) return
        if (levels.canOwnUserSendState(StateEventType.SpaceChild)) {
            manageableSpaceIdsSet.add(spaceId)
            _manageableSpaceIds.value = manageableSpaceIdsSet.toSet()
        }
    }

    /**
     * Rooms this user may move into/out of a space at all — filing a room sets
     * `m.space.parent` in the room, so it needs power in the *room*, not just
     * the space. Without it the Spaces menu is hidden (fail closed) rather than
     * offering a move that would fail on a room you don't administer.
     */
    private val moveableRoomIdsSet = mutableSetOf<String>()
    private val _moveableRoomIds = MutableStateFlow<Set<String>>(emptySet())
    val moveableRoomIds: StateFlow<Set<String>> = _moveableRoomIds
    private val movePermissionChecked = mutableSetOf<String>()

    suspend fun refreshMovePermission(forRoomId: String) {
        if (!movePermissionChecked.add(forRoomId)) return
        val room = ffiRoom(forRoomId)
            ?: withContext(Dispatchers.IO) { runCatching { service.client.getRoom(forRoomId) }.getOrNull() }
        val levels = try {
            withContext(Dispatchers.IO) { room?.getPowerLevels() }
        } catch (error: CancellationException) {
            movePermissionChecked.remove(forRoomId)
            throw error
        } catch (_: Exception) {
            null
        }
        if (levels == null) return
        if (levels.canOwnUserSendState(StateEventType.SpaceParent)) {
            moveableRoomIdsSet.add(forRoomId)
            _moveableRoomIds.value = moveableRoomIdsSet.toSet()
        }
    }

    private fun add(room: Room, at: Int, summary: RoomSummary) {
        val i = at.coerceIn(0, roomsList.size)
        // Clamp each array to its own count: a restored snapshot leaves `ffiRooms`
        // shorter than `rooms`, and inserting past ffiRooms.count would crash.
        ffiRooms.add(minOf(i, ffiRooms.size), room)
        roomsList.add(i, summary)
        refreshDetails(room)
    }

    /**
     * Fills in the async parts of a summary (unreads, last message) and queues it
     * for a batched write by room ID, since the row may have moved by then.
     */
    private fun refreshDetails(room: Room) {
        scope.launch {
            // These FFI reads poll the Rust async runtime; on the Main.immediate
            // scope they blocked input long enough to ANR and stall room-list
            // scrolling (fired per room on load and on every re-sort). Do the
            // reads on IO, then resume on Main for the list mutations below.
            val info = withContext(Dispatchers.IO) { runCatching { room.roomInfo() }.getOrNull() }
            val latest = withContext(Dispatchers.IO) { runCatching { room.latestEvent() }.getOrNull() }
            val id = room.id()
            val index = roomIndexById[id] ?: return@launch
            if (index !in roomsList.indices) return@launch
            var summary = pendingSummaries[id] ?: roomsList[index]
            if (info != null) summary = summary.updated(info)
            if (latest != null) summary = summary.updated(latest)
            summary = summary.copy(isVideoRoom = videoRoomIdsSet.contains(summary.id))
            if (summary.isInvited && summary.inviterName == null) {
                withContext(Dispatchers.IO) { runCatching { room.inviter() }.getOrNull() }?.let { inviter ->
                    summary = summary.copy(
                        inviterName = inviter.displayName ?: inviter.userId)
                }
            }
            // The room on screen stays read: the server echo would re-light the pip
            // for a beat between a new message and the timeline receipting it.
            if (summary.id == _activeRoomId.value && Platform.isAppActive) {
                summary = summary.copy(
                    unreadMessages = 0u,
                    unreadNotifications = 0u,
                    unreadMentions = 0u,
                    isMarkedUnread = false,
                )
            }
            enqueue(summary)
        }
    }

    // MARK: Batched summary publication

    /**
     * Refreshed summaries awaiting a single batched write into `rooms`. Publishing
     * them one at a time re-rendered the whole sidebar once per room.
     */
    private val pendingSummaries = mutableMapOf<String, RoomSummary>()
    private var flushJob: Job? = null

    private fun enqueue(summary: RoomSummary) {
        // Only queue real changes: every flush invalidates the whole sidebar, and
        // busy rooms refresh constantly — the churn was rebuilding views mid-click
        // and swallowing header-menu presses.
        if (pendingSummaries[summary.id] == null) {
            val index = roomIndexById[summary.id]
            if (index != null && index in roomsList.indices && roomsList[index] == summary) {
                return
            }
        }
        pendingSummaries[summary.id] = summary
        if (flushJob != null) return
        // One drain job per burst: flush ~every 100ms while work exists.
        flushJob = scope.launch {
            while (true) {
                delay(100)
                flushPendingSummaries()
                if (pendingSummaries.isEmpty()) {
                    flushJob = null
                    return@launch
                }
            }
        }
    }

    /**
     * Applies every pending summary in one `rooms` mutation. Indexes are re-resolved
     * by ID — the row may have moved since the refresh ran.
     */
    private fun flushPendingSummaries() {
        if (pendingSummaries.isEmpty()) return
        val changed = mutableListOf<RoomSummary>()
        for ((id, summary) in pendingSummaries) {
            val index = roomIndexById[id] ?: continue
            if (index !in roomsList.indices || roomsList[index] == summary) continue
            roomsList[index] = summary
            changed.add(summary)
        }
        pendingSummaries.clear()
        if (changed.isEmpty()) return
        publishRooms()
        updateUnreadTotal()
        recomputeUnreadFlags()
        scheduleSnapshotWrite()
        persistSpaceNamesForPush()
        // Respect this account's per-account notification toggle. (Calls still
        // ring in-app; only banners are gated.)
        val notify = NotificationManager.notificationsEnabled(forUserId = service.userId)
        for (summary in changed) {
            val avatarUrl = notificationAvatarUrl(summary)
            if (notify) {
                NotificationManager.maybeNotify(
                    room = summary,
                    spaceName = spaceName(ofRoom = summary.id),
                    avatarUrl = avatarUrl,
                    accountUserId = service.userId,
                )
                NotificationManager.maybeNotifyInvite(
                    room = summary, avatarUrl = avatarUrl, accountUserId = service.userId)
            }
            NotificationManager.maybeNotifyCall(
                room = summary, avatarUrl = avatarUrl, accountUserId = service.userId)
        }
    }

    /** Last mirrored push maps; skip the disk write when nothing changed. */
    private var lastPersistedSpaceNames: Map<String, String>? = null
    private var lastPersistedSpaceAvatars: Map<String, String>? = null
    private var lastPersistedRoomAvatars: Map<String, String>? = null

    /**
     * Mirror the room→space names/avatars so pushes can be titled
     * "Space › Room" with the right avatar (iOS persistSpaceNamesForPush;
     * the pending remote-push service reads these).
     */
    private fun persistSpaceNamesForPush() {
        val names = mutableMapOf<String, String>()
        val avatars = mutableMapOf<String, String>()
        for ((spaceId, childIds) in spaceChildIdsMap) {
            val space = spacesList.firstOrNull { it.id == spaceId } ?: continue
            for (roomId in childIds) {
                if (names[roomId] != null) continue
                names[roomId] = space.name
                space.avatarUrl?.let { avatars[roomId] = it }
            }
        }
        if (names != lastPersistedSpaceNames) {
            lastPersistedSpaceNames = names
            SpaceNameStore.save(appContext, names)
        }
        if (avatars != lastPersistedSpaceAvatars) {
            lastPersistedSpaceAvatars = avatars
            SpaceNameStore.saveAvatars(appContext, avatars)
        }

        // The exact avatar each room's push should show (DM → other person,
        // room-in-space → space, else the room).
        val roomAvatars = mutableMapOf<String, String>()
        for (room in roomsList) {
            notificationAvatarUrl(room)?.let { roomAvatars[room.id] = it }
        }
        if (roomAvatars != lastPersistedRoomAvatars) {
            lastPersistedRoomAvatars = roomAvatars
            SpaceNameStore.saveRoomAvatars(appContext, roomAvatars)
        }
    }

    /** The first space containing this room, for notification titles/avatars. */
    fun space(ofRoom: String): SpaceItem? {
        val spaceId = spaceChildIdsMap.entries.firstOrNull { it.value.contains(ofRoom) }?.key
            ?: return null
        return spacesList.firstOrNull { it.id == spaceId }
    }

    /** The first space containing this room, for notification titles. */
    fun spaceName(ofRoom: String): String? = space(ofRoom)?.name

    /**
     * Avatar to show on a room's notification: a DM shows the other person, a
     * room inside a space shows the space, a plain room shows the room itself.
     */
    fun notificationAvatarUrl(room: RoomSummary): String? {
        if (room.isDirect) return room.avatarUrl
        space(ofRoom = room.id)?.avatarUrl?.let { return it }
        return room.avatarUrl
    }

    /**
     * Zeroes the local unread state so pips, badges, and banners react immediately
     * instead of after the server round-trip.
     */
    private fun clearUnreadLocally(roomIds: List<String>) {
        // Reading a room also retires its delivered banners (iOS parity).
        NotificationManager.clearDelivered(roomIds.toSet())
        var changedRooms = false
        for (id in roomIds) {
            val index = roomIndexById[id]
            if (index != null && index in roomsList.indices) {
                roomsList[index] = roomsList[index].copy(
                    unreadMessages = 0u,
                    unreadNotifications = 0u,
                    unreadMentions = 0u,
                    isMarkedUnread = false,
                )
                changedRooms = true
            }
            // A refresh queued before the clear must not re-light the pip on flush.
            pendingSummaries[id]?.let {
                pendingSummaries[id] = it.copy(
                    unreadMessages = 0u,
                    unreadNotifications = 0u,
                    unreadMentions = 0u,
                    isMarkedUnread = false,
                )
            }
        }
        // Phase 9: NotificationManager.clearDelivered(roomIds) — one batched
        // fetch, not one per room; Mark All as Read clears dozens.
        if (changedRooms) publishRooms()
        updateUnreadTotal()
        // The direct `rooms` mutation above bypasses the flush, so clear the rail
        // pips now rather than on the next batched publish.
        recomputeUnreadFlags()
    }

    /** Sends read receipts and clears unread flags for the given rooms. */
    fun markRead(roomIds: List<String>) {
        clearUnreadLocally(roomIds)
        scope.launch {
            for (id in roomIds) {
                val room = ffiRoom(id) ?: continue
                runCatching { room.markAsRead(ReceiptType.READ) }
                runCatching { room.setUnreadFlag(false) }
            }
        }
    }

    /** Every room filed in the space, for Mark All as Read. */
    fun childRoomIds(of: String): List<String> = (spaceChildIdsMap[of] ?: emptySet()).toList()

    /** Everything visible on Home: DMs plus unfiled rooms. */
    val homeRoomIds: List<String>
        get() {
            val filed = _allSpaceChildIds.value
            return roomsList
                .filter { !it.isSpace && (it.isDirect || !filed.contains(it.id)) }
                .map { it.id }
        }

    /**
     * Records a room known to be a video room before any space listing says so
     * (e.g. one just created here).
     */
    fun noteVideoRoom(roomId: String) {
        videoRoomIdsSet.add(roomId)
        _videoRoomIds.value = videoRoomIdsSet.toSet()
        val index = roomIndexById[roomId]
        if (index != null && index in roomsList.indices) {
            roomsList[index] = roomsList[index].copy(isVideoRoom = true)
            publishRooms()
        }
        pendingSummaries[roomId]?.let { pendingSummaries[roomId] = it.copy(isVideoRoom = true) }
    }

    /**
     * Joins a room discovered in a space's listing; the diff stream adds the row
     * once sync delivers it.
     */
    suspend fun joinSpaceChild(child: SpaceChild) {
        if (_joiningRoomIds.value.contains(child.id)) return
        _joiningRoomIds.value = _joiningRoomIds.value + child.id
        try {
            service.client.joinRoomByIdOrAlias(child.id, child.via)
            // Reload so the row moves from "join" to joined.
            val spaceId = _selectedSpaceId.value
            if (spaceId != null) {
                val children = loadSpaceChildren(spaceId)
                if (_selectedSpaceId.value == spaceId && children != null) {
                    _visibleRoomIds.value =
                        children.filter { !it.isSpace }.map { it.id }.toSet()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportActionError("Couldn't join ${child.name}: ${error.message}")
        } finally {
            _joiningRoomIds.value = _joiningRoomIds.value - child.id
        }
    }

    /** The diff stream flips the row to joined. */
    suspend fun acceptInvite(roomId: String) {
        val room = ffiRoom(roomId) ?: return
        if (_joiningInviteIds.value.contains(roomId)) return
        _joiningInviteIds.value = _joiningInviteIds.value + roomId
        try {
            room.join()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val name = roomIndexById[roomId]?.let { roomsList[it].name } ?: roomId
            reportActionError("Couldn't accept the invite to $name: ${error.message}")
        } finally {
            _joiningInviteIds.value = _joiningInviteIds.value - roomId
        }
    }

    /**
     * This account's unread-notification total. AppState owns the app badge and
     * sums every warm scope's total, so this must stay per-scope.
     */
    private val _unreadTotal = MutableStateFlow(0)
    val unreadTotal: StateFlow<Int> = _unreadTotal

    /** Set by AppState; fires whenever `unreadTotal` changes. */
    var onUnreadTotalChanged: (() -> Unit)? = null

    private fun updateUnreadTotal() {
        // Muted rooms contribute only real mentions.
        val total = roomsList.sumOf { it.badgeCount.toLong() }.toInt()
        if (total == _unreadTotal.value) return
        _unreadTotal.value = total
        onUnreadTotalChanged?.invoke()
    }

    // MARK: Cold-launch snapshot

    /**
     * True while `rooms` holds disk-restored rows with no FFI backing. Cleared by
     * the first diff batch, which replaces them wholesale.
     */
    private var isShowingRestoredSnapshot = false

    /** Trailing-debounced snapshot writer. */
    private var snapshotJob: Job? = null

    /**
     * Set by the owning scope: bulk-loads disk-cached avatar thumbnails into memory
     * so the restored sidebar's first frame has them.
     */
    var prewarmAvatars: (suspend (List<String>) -> Unit)? = null

    /**
     * Paints the cached sidebar before the window mounts, so the first main-shell
     * frame shows chats instead of an empty list. Idempotent — `restoreSnapshot`
     * guards on `rooms.isEmpty`, so the later `start()` call is a no-op.
     */
    suspend fun primeSnapshotForLaunch() {
        restoreSnapshot()
    }

    private suspend fun restoreSnapshot() {
        if (roomsList.isNotEmpty()) return
        val file = snapshotFile(appContext, service.userId) ?: return
        val snapshot = readSnapshot(file) ?: return
        if (roomsList.isNotEmpty() || snapshot.rooms.isEmpty()) return
        // Paint the rows now; don't block the first frame on avatar disk I/O. Avatars
        // warm in the background (their per-row async load also covers them).
        isShowingRestoredSnapshot = true
        roomsList.addAll(snapshot.rooms)
        if (spacesList.isEmpty()) {
            spacesList.addAll(snapshot.spaces.map {
                SpaceItem(id = it.id, name = it.name, avatarUrl = it.avatarUrl)
            })
            _spaces.value = spacesList.toList()
            rebuildOrderedSpaces()
        }
        if (spaceChildIdsMap.isEmpty()) {
            spaceChildIdsMap.putAll(snapshot.spaceChildIds)
            rebuildAllSpaceChildIds()
        }
        rebuildRoomIndex()
        publishRooms()
        recomputeUnreadFlags()
        val avatarUrls = snapshot.rooms.mapNotNull { it.avatarUrl } +
            snapshot.spaces.mapNotNull { it.avatarUrl }
        if (avatarUrls.isNotEmpty()) {
            scope.launch { prewarmAvatars?.invoke(avatarUrls) }
        }
    }

    /**
     * When the last write was kicked off; caps latency under continuous churn,
     * which the trailing debounce alone would starve.
     */
    private var lastSnapshotWriteAt = System.currentTimeMillis()

    /**
     * Persists the sidebar ~2s (trailing) after it last changed, capped at 30s under
     * continuous churn. Skipped while showing restored rows — they came from this file.
     */
    private fun scheduleSnapshotWrite() {
        if (isShowingRestoredSnapshot) return
        if (System.currentTimeMillis() - lastSnapshotWriteAt > 30_000) {
            snapshotJob?.cancel()
            snapshotJob = null
            writeSnapshot()
            return
        }
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(2_000)
            writeSnapshot()
        }
    }

    private fun writeSnapshot() {
        val file = snapshotFile(appContext, service.userId) ?: return
        lastSnapshotWriteAt = System.currentTimeMillis()
        // Build the value on-main; encode (the part that scales with sidebar
        // size) and write off-main.
        val snapshot = RoomListSnapshot(
            rooms = roomsList.toList(),
            spaces = spacesList.map {
                RoomListSnapshot.Space(id = it.id, name = it.name, avatarUrl = it.avatarUrl)
            },
            spaceChildIds = spaceChildIdsMap.toMap(),
        )
        scope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(file)
            }
        }
    }

    /** Reads and decodes off-main; only the decoded value hops threads. */
    private suspend fun readSnapshot(file: File): RoomListSnapshot? =
        withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString<RoomListSnapshot>(file.readText())
            }.getOrNull()
        }

    companion object {
        /**
         * filesDir/<account>/roomlist-snapshot.json, keyed by the
         * filesystem-sanitized Matrix user ID.
         */
        fun snapshotFile(context: Context, userId: String): File? {
            val safe = userId.map {
                if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_'
            }.joinToString("")
            return File(context.filesDir, "$safe/roomlist-snapshot.json")
        }

        /**
         * Deletes an account's snapshot and its per-account directory, which holds
         * nothing else (SDK stores live under Sessions/).
         */
        fun removeSnapshot(context: Context, userId: String) {
            snapshotFile(context, userId)?.parentFile?.deleteRecursively()
        }
    }

    // MARK: Bridges

    /**
     * Listener callbacks arrive on Rust threads; unbounded channels hand them
     * to the main dispatcher without dropping (a dropped positional diff would
     * corrupt the index-aligned arrays).
     */
    private class EntriesBridge : RoomListEntriesListener {
        val channel = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
        override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
            channel.trySend(roomEntriesUpdate)
        }
    }

    private class LoadingBridge : RoomListLoadingStateListener {
        val channel = Channel<RoomListLoadingState>(Channel.UNLIMITED)
        override fun onUpdate(state: RoomListLoadingState) {
            channel.trySend(state)
        }
    }

    private class JoinedSpacesBridge : SpaceServiceJoinedSpacesListener {
        val channel = Channel<List<SpaceListUpdate>>(Channel.UNLIMITED)
        override fun onUpdate(roomUpdates: List<SpaceListUpdate>) {
            channel.trySend(roomUpdates)
        }
    }
}

/**
 * On-disk shape of the sidebar snapshot. Holds only state RoomSummary already
 * has, never decrypted timeline content.
 */
@Serializable
private data class RoomListSnapshot(
    val rooms: List<RoomSummary>,
    val spaces: List<Space>,
    val spaceChildIds: Map<String, Set<String>>,
) {
    @Serializable
    data class Space(
        val id: String,
        val name: String,
        val avatarUrl: String? = null,
    )
}
