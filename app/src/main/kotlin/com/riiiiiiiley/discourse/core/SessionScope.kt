package com.riiiiiiiley.discourse.core

import android.content.Context
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.call.CallStore
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import com.riiiiiiiley.discourse.features.timeline.TimelineViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import org.matrix.rustcomponents.sdk.VerificationState

/**
 * Everything scoped to a signed-in session. Torn down wholesale on logout.
 * Mirrors the iOS SessionScope: the session itself, the room list, media
 * loader, sticker/custom-emoji stores, presence, pronouns, the own-profile
 * state, the verification/auth-error monitors, and the timeline/call
 * view-model caches.
 */
class SessionScope(
    val service: MatrixService,
    val token: RestorationToken,
    context: Context,
    private val preferences: Preferences? = null,
) {
    /** Coroutine home for the scope's monitors; cancelled by [tearDown]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val appContext = context.applicationContext

    val roomList = RoomListViewModel(service, appContext)
    val mediaLoader = MediaLoader(service.client, appContext)
    val stickers by lazy { StickerStore(service.client) }
    val customEmoji = CustomEmojiStore(service.client)
    val pronouns = PronounsStore(service)
    val presence = PresenceService(
        homeserverUrl = token.session.homeserverUrl,
        accessToken = token.session.accessToken,
        ownUserId = token.session.userId,
        preferences = preferences,
    )

    /** Live Element Call sessions, kept alive independent of screen lifecycle. */
    val calls = CallStore { roomId -> timeline(roomId)?.callViewModel() }

    init {
        // Status comes from presence `status_msg` (Commet's store), so let the
        // profile cache read it with the profile field as fallback.
        pronouns.presence = presence
        customEmoji.spacesProvider = {
            roomList.spaces.value.map { it.id to it.name }
        }
        // Prime once the first sync has delivered the space list, so emote
        // reactions label correctly and `:autocomplete:` works before the
        // picker opens. The store re-checks the space set on every later call.
        scope.launch {
            delay(5_000)
            runCatching { customEmoji.refreshIfStale() }
        }
        // 56px = the sidebar rows' request; the rail's 80px falls back to these
        // entries until its own lands.
        roomList.prewarmAvatars = { urls ->
            mediaLoader.prewarmThumbnails(urls, pixelSize = 56f)
        }
    }

    data class IncomingVerification(
        val senderId: String,
        val flowId: String,
    ) {
        val id: String get() = flowId
    }

    /**
     * Not yet cross-signed: encrypted history stays locked until the user
     * verifies or enters their recovery key.
     */
    private val _needsVerification = MutableStateFlow(false)
    val needsVerification: StateFlow<Boolean> = _needsVerification

    /** Own avatar (mxc URL), for the rail switcher. */
    private val _ownAvatarUrl = MutableStateFlow<String?>(null)
    val ownAvatarUrl: StateFlow<String?> = _ownAvatarUrl

    private val _ownDisplayName = MutableStateFlow<String?>(null)
    val ownDisplayName: StateFlow<String?> = _ownDisplayName

    // Own extended-profile fields (Settings → Account).
    private val _ownPronouns = MutableStateFlow<String?>(null)
    val ownPronouns: StateFlow<String?> = _ownPronouns
    private val _ownBio = MutableStateFlow<String?>(null)
    val ownBio: StateFlow<String?> = _ownBio
    private val _ownStatus = MutableStateFlow<String?>(null)
    val ownStatus: StateFlow<String?> = _ownStatus
    private val _ownTimezone = MutableStateFlow<String?>(null)
    val ownTimezone: StateFlow<String?> = _ownTimezone
    private val _ownBannerUrl = MutableStateFlow<String?>(null)
    val ownBannerUrl: StateFlow<String?> = _ownBannerUrl
    private val _ownSocialLinks = MutableStateFlow<List<MatrixService.SocialLink>>(emptyList())
    val ownSocialLinks: StateFlow<List<MatrixService.SocialLink>> = _ownSocialLinks

    /** Set when another device asks this one to verify; drives a sheet. */
    private val _incomingVerification = MutableStateFlow<IncomingVerification?>(null)
    val incomingVerification: StateFlow<IncomingVerification?> = _incomingVerification

    fun clearIncomingVerification() {
        _incomingVerification.value = null
    }

    val userId: String get() = service.userId

    suspend fun loadOwnProfile() {
        // avatarUrl()/displayName() are blocking FFI; loadOwnProfile runs from a
        // Main-dispatched LaunchedEffect, so keep them off the main thread.
        withContext(Dispatchers.IO) { runCatching { service.client.avatarUrl() }.getOrNull() }
            ?.let { _ownAvatarUrl.value = it }
        withContext(Dispatchers.IO) { runCatching { service.client.displayName() }.getOrNull() }
            ?.let { _ownDisplayName.value = it }
        service.fetchProfile(service.userId)?.let { profile ->
            _ownPronouns.value = profile.pronouns
            _ownBio.value = profile.bio
            _ownStatus.value = profile.status
            _ownTimezone.value = profile.timezone
            _ownBannerUrl.value = profile.bannerUrl
            _ownSocialLinks.value = profile.socialLinks
        }
    }

    // MARK: Profile editing (Settings → Account)
    // Each write invalidates/updates the PronounsStore cache so open profile
    // sheets and member rows reflect the edit at once (iOS parity).

    suspend fun setDisplayName(name: String) {
        service.client.setDisplayName(name)
        _ownDisplayName.value = name
        pronouns.invalidate(service.userId)
    }

    suspend fun setPronouns(value: String) {
        val trimmed = value.trim()
        service.setPronouns(trimmed)
        _ownPronouns.value = trimmed.ifEmpty { null }
        pronouns.setLocal(trimmed.ifEmpty { null }, forUserId = service.userId)
    }

    suspend fun setBio(value: String) {
        val trimmed = value.trim()
        // Bio is an object with a `body`, per Commet's schema.
        service.setProfileField(MatrixService.bioKey, JSONObject().put("body", trimmed))
        _ownBio.value = trimmed.ifEmpty { null }
        pronouns.invalidate(service.userId)
    }

    suspend fun setStatus(value: String) {
        val trimmed = value.trim()
        // Commet-family clients read the status from presence `status_msg`; also
        // keep the profile field for clients that read that instead.
        service.setPresenceStatus(trimmed)
        service.setProfileField(MatrixService.statusKey, trimmed)
        _ownStatus.value = trimmed.ifEmpty { null }
        pronouns.invalidate(service.userId)
    }

    suspend fun setTimezone(value: String) {
        val trimmed = value.trim()
        // Write the standard MSC4175 key (for interop) AND a non-reserved
        // fallback: servers like Tuwunel reject `m.tz`, so the fallback is what
        // actually persists and reads back — otherwise the field looks emptied.
        service.setProfileField(MatrixService.timezoneKey, trimmed)
        service.setProfileField(MatrixService.timezoneKeyFallback, trimmed)
        _ownTimezone.value = trimmed.ifEmpty { null }
        pronouns.invalidate(service.userId)
    }

    suspend fun setSocialLinks(links: List<MatrixService.SocialLink>) {
        val payload = org.json.JSONArray().apply {
            links.forEach { link ->
                put(JSONObject().apply {
                    put("title", link.title)
                    put("link", link.link)
                    link.img?.takeIf { it.isNotEmpty() }?.let { put("img", it) }
                })
            }
        }
        service.setProfileField(MatrixService.socialLinksKey, payload)
        _ownSocialLinks.value = links
    }

    suspend fun setAvatar(data: ByteArray, mimeType: String) {
        service.client.uploadAvatar(mimeType, data)
        loadOwnProfile()
    }

    suspend fun removeAvatar() {
        service.client.removeAvatar()
        _ownAvatarUrl.value = null
    }

    /** Uploads an image and sets it as the Commet profile banner. */
    suspend fun setBanner(data: ByteArray, mimeType: String) {
        val mxc = service.client.uploadMedia(mimeType, data, progressWatcher = null)
        service.setProfileField(MatrixService.bannerKey, mxc)
        _ownBannerUrl.value = mxc
        pronouns.invalidate(service.userId)
    }

    suspend fun removeBanner() {
        service.setProfileField(MatrixService.bannerKey, "")
        _ownBannerUrl.value = null
        pronouns.invalidate(service.userId)
    }

    /**
     * Whether this user can change the given space's banner — the edit controls
     * hide when this is false rather than offering an action that would fail.
     */
    suspend fun canEditSpaceBanner(spaceId: String): Boolean =
        service.canSendStateEvent(roomId = spaceId, type = spaceBannerEventType)

    /**
     * Uploads an image and sets it as a space's banner (state event). Returns
     * the new banner mxc URL on success, or null if the user lacks permission.
     */
    suspend fun setSpaceBanner(spaceId: String, data: ByteArray, mimeType: String): String? {
        val mxc = service.client.uploadMedia(mimeType, data, progressWatcher = null)
        val ok = service.setStateEvent(
            roomId = spaceId,
            type = spaceBannerEventType,
            content = JSONObject().put("url", mxc).put("mimetype", mimeType),
        )
        return if (ok) mxc else null
    }

    suspend fun removeSpaceBanner(spaceId: String): Boolean =
        service.setStateEvent(roomId = spaceId, type = spaceBannerEventType, content = JSONObject())

    private var verificationJob: Job? = null
    private var incomingWatchJob: Job? = null
    private var verificationDelegate: SessionVerificationControllerDelegate? = null

    /**
     * Fires with this scope's user ID when the SDK reports the token is dead.
     * Wired by AppState to drop into re-auth.
     */
    var onAuthError: ((String) -> Unit)? = null
    private var authErrorJob: Job? = null

    /**
     * Watches the client's unknown-token signal. Start once, after AppState
     * wires `onAuthError`.
     */
    fun startAuthErrorMonitor() {
        if (authErrorJob != null) return
        authErrorJob = scope.launch {
            service.authErrorFlow.collect {
                onAuthError?.invoke(userId)
            }
        }
    }

    fun startVerificationMonitor() {
        if (verificationJob != null) return
        verificationJob = scope.launch {
            // verificationState is a blocking FFI read; startVerificationMonitor
            // is called on Main, so seed the initial value off-main before the
            // live collection takes over.
            _needsVerification.value =
                withContext(Dispatchers.IO) { service.verificationState } == VerificationState.UNVERIFIED
            service.verificationStates().collect { state ->
                _needsVerification.value = state == VerificationState.UNVERIFIED
            }
        }
        scope.launch { watchForIncomingVerification() }
    }

    /**
     * Delegate on the session-verification controller, so requests from other
     * devices surface here. (An active verification flow's UI replaces the
     * delegate for its duration — the controller is the session-wide shared
     * instance, so its events always land on the currently-set delegate.)
     */
    suspend fun watchForIncomingVerification() {
        val controller = runCatching { service.sessionVerificationController() }.getOrNull() ?: return
        val delegate = object : SessionVerificationControllerDelegate {
            override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
                _incomingVerification.value = IncomingVerification(
                    senderId = details.senderProfile.userId,
                    flowId = details.flowId,
                )
            }

            override fun didAcceptVerificationRequest() = Unit
            override fun didStartSasVerification() = Unit
            override fun didReceiveVerificationData(data: SessionVerificationData) = Unit
            override fun didFail() = Unit
            override fun didCancel() = Unit
            override fun didFinish() = Unit
        }
        verificationDelegate = delegate
        withContext(Dispatchers.IO) { controller.setDelegate(delegate) }
    }

    // MARK: Timeline view-model cache

    private val timelines = mutableMapOf<String, TimelineViewModel>()

    /** Room IDs by access recency, oldest first. Drives LRU eviction. */
    private val timelineAccessOrder = mutableListOf<String>()

    /**
     * One view model per room, kept alive up to a cap so revisiting a recent
     * room is instant. Evicted rooms rebuild on reopen.
     */
    fun timeline(forRoomId: String): TimelineViewModel? {
        timelines[forRoomId]?.let {
            touchTimeline(forRoomId)
            return it
        }
        val room = roomList.ffiRoom(withId = forRoomId) ?: return null
        val viewModel = TimelineViewModel(
            room = room, ownUserId = userId, preferences = preferences ?: Preferences(appContext),
            context = appContext, service = service, customEmoji = customEmoji,
        )
        viewModel.isVideoRoom.value = roomList.videoRoomIds.value.contains(forRoomId)
        timelines[forRoomId] = viewModel
        touchTimeline(forRoomId)
        evictTimelinesIfNeeded()
        return viewModel
    }

    private fun touchTimeline(roomId: String) {
        timelineAccessOrder.remove(roomId)
        timelineAccessOrder.add(roomId)
    }

    /**
     * Pauses/resumes every cached timeline's ephemeral long-poll; called from
     * the process-lifecycle handler so a backgrounded app doesn't keep a 30s
     * /sync loop running.
     */
    fun setEphemeralSyncPaused(paused: Boolean) {
        for (timeline in timelines.values) {
            if (paused) timeline.pauseEphemeralSync() else timeline.resumeEphemeralSync()
        }
    }

    private fun evictTimelinesIfNeeded() {
        if (timelines.size <= MAX_LIVE_TIMELINES) return
        for (roomId in timelineAccessOrder.toList()) {
            if (timelines.size <= MAX_LIVE_TIMELINES) return
            // Never evict the visible room (the only unparked one) or a room
            // whose call is still running behind the call cover.
            val viewModel = timelines[roomId] ?: continue
            if (!viewModel.isParked || calls.hasCall(roomId)) continue
            viewModel.stop()
            timelines.remove(roomId)
            timelineAccessOrder.remove(roomId)
        }
    }

    /**
     * Sends plain text to a room without its timeline being on screen (the
     * notification Reply action). Starts the cached view model if needed, then
     * re-parks it so it stays evictable.
     */
    suspend fun sendMessage(text: String, toRoomId: String) {
        // Cold launch replays queued replies while the sidebar is still
        // snapshot-only (no FFI rooms yet); wait for sync to deliver the room
        // instead of dropping the user's text.
        val viewModel = awaitTimeline(toRoomId) ?: return
        viewModel.start()
        // A parked view model may hold an in-progress edit/reply; a
        // notification reply must not hijack it.
        val savedEdit = viewModel.editTarget.value
        val savedReply = viewModel.replyTarget.value
        viewModel.editTarget.value = null
        viewModel.replyTarget.value = null
        viewModel.sendText(text)
        // Restore only if the user didn't touch the composer during the send.
        if (viewModel.editTarget.value == null) viewModel.editTarget.value = savedEdit
        if (viewModel.replyTarget.value == null) viewModel.replyTarget.value = savedReply
        if (roomList.activeRoomId.value != toRoomId) {
            viewModel.isParked = true
        }
    }

    /**
     * `timeline(forRoomId)` with a bounded wait for the FFI room (up to ~30s,
     * polled at 500ms); snapshot-restored rooms have no backing until the
     * first sync batch lands.
     */
    private suspend fun awaitTimeline(roomId: String): TimelineViewModel? {
        repeat(60) {
            timeline(roomId)?.let { return it }
            delay(500)
        }
        return null
    }

    fun tearDown() {
        verificationJob?.cancel()
        verificationJob = null
        incomingWatchJob?.cancel()
        incomingWatchJob = null
        authErrorJob?.cancel()
        authErrorJob = null
        verificationDelegate = null
        calls.tearDown()
        timelines.values.forEach { it.stop() }
        timelines.clear()
        timelineAccessOrder.clear()
        roomList.stop()
        presence.pause()
        mediaLoader.tearDown()
        scope.cancel()
    }

    companion object {
        /** Custom state event holding a space's banner image. */
        const val spaceBannerEventType = "page.codeberg.everypizza.room.banner"

        /**
         * Each cached view model holds a live FFI timeline + diff listener, so
         * unbounded caching gets expensive.
         */
        private const val MAX_LIVE_TIMELINES = 8
    }
}
