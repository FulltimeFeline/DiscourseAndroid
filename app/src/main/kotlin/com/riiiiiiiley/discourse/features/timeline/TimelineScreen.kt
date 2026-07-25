@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.riiiiiiiley.discourse.features.timeline

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import android.graphics.Bitmap
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.features.profile.ProfileTarget
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.LocalPresenceService
import com.riiiiiiiley.discourse.core.LocalPronounsStore
import com.riiiiiiiley.discourse.core.PowerLevelTag
import com.riiiiiiiley.discourse.core.PresenceIndicator
import com.riiiiiiiley.discourse.core.media.LocalMediaLoader
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.models.ImageItem
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One room's conversation: banners, timeline list, jump overlays, and the
 * composer slot. Port of iOS TimelineView (phone layout: the details column
 * becomes a bottom sheet, matching the compact-width iOS path).
 */
@Composable
fun TimelineScreen(
    viewModel: TimelineViewModel,
    appState: AppState,
    modifier: Modifier = Modifier,
    /** Set on phone, where chat is a slide-over layer: "back" slides it out. */
    closeChat: (() -> Unit)? = null,
    emoteLoader: EmoteAssetLoader? = null,
    mediaRenderers: TimelineMediaRenderers = TimelineMediaRenderers(),
    /**
     * Live video-room IDs from the room list (space listings are the only
     * source of creation types and may load after this timeline was built).
     */
    videoRoomIds: Set<String> = emptySet(),
    onStartCall: () -> Unit = {},
    /** Opens the room search sheet (search slice); hidden when null. */
    onOpenSearch: (() -> Unit)? = null,
    /** Opens a thread on its root event (ThreadView slice). */
    onOpenThread: (String) -> Unit = {},
    onOpenProfile: (ProfileTarget) -> Unit = {},
    /** Opens the emoji picker for reactions (emoji-picker slice). */
    onMoreReactions: ((MessageItem) -> Unit)? = null,
    /** Details-sheet hooks into other slices; entries hidden when null. */
    onInvitePeople: (() -> Unit)? = null,
    onOpenRoomSettings: (() -> Unit)? = null,
    /** The composer (composer slice) rendered below the list. */
    composer: @Composable () -> Unit = {},
) {
    val colors = LocalDiscourseColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs by appState.preferences.state.collectAsStateWithLifecycle()

    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val reachedStart by viewModel.reachedStart.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val topic by viewModel.topic.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val isEncrypted by viewModel.isEncrypted.collectAsStateWithLifecycle()
    val isDirect by viewModel.isDirect.collectAsStateWithLifecycle()
    val hasActiveCall by viewModel.hasActiveCall.collectAsStateWithLifecycle()
    val isVideoRoomFlag by viewModel.isVideoRoom.collectAsStateWithLifecycle()
    val firstUnreadMarkerId by viewModel.firstUnreadMarkerId.collectAsStateWithLifecycle()
    val unreadMarkerVisible by viewModel.unreadMarkerVisible.collectAsStateWithLifecycle()
    val unreadMarkerOnScreen by viewModel.unreadMarkerOnScreen.collectAsStateWithLifecycle()
    val lastOwnMessageId by viewModel.lastOwnMessageId.collectAsStateWithLifecycle()
    val shields by viewModel.shields.collectAsStateWithLifecycle()
    val membersById by viewModel.membersById.collectAsStateWithLifecycle()
    val canRedactOwn by viewModel.canRedactOwn.collectAsStateWithLifecycle()
    val canRedactOther by viewModel.canRedactOther.collectAsStateWithLifecycle()
    val unparkScrollTarget by viewModel.unparkScrollTarget.collectAsStateWithLifecycle()
    val activeCallRoomIds by appState.activeCallRoomIds.collectAsStateWithLifecycle()

    // Own messages show live profile edits at once, before sync echoes the
    // new member state back into the timeline.
    val phase by appState.phase.collectAsStateWithLifecycle()
    val sessionScope = (phase as? AppState.Phase.Active)?.scope
    val ownDisplayName = sessionScope?.ownDisplayName?.collectAsStateWithLifecycle()?.value
    val ownAvatarUrl = sessionScope?.ownAvatarUrl?.collectAsStateWithLifecycle()?.value

    val listState = rememberLazyListState()

    // Row-visibility set, mutated in place as rows enter/leave the viewport.
    // A fast fling churns dozens of rows per frame; a copy-on-write Set would
    // allocate a fresh set on every appear AND dispose (GC pressure → stutter).
    // One stable LinkedHashSet, handed to the VM once, mutated with add/remove.
    val visibleEntryIds = remember { linkedSetOf<String>() }
    // Hand the stable set to the VM (once per VM). A room switch that reuses
    // this composable brings a new VM; clear stale ids before adopting it so
    // the old room's rows don't leak into the new anchor read.
    remember(viewModel) {
        visibleEntryIds.clear()
        viewModel.visibleEntryIds = visibleEntryIds
    }

    // Bottom-proximity from real scroll geometry, not a sentinel: prefetch
    // instantiates rows ~a screen early, which would flip isAtBottom (and
    // fire read receipts) while the newest message is still below the fold.
    val bottomThresholdPx = with(density) { 40.dp.toPx() }
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= bottomThresholdPx
        }
    }
    LaunchedEffect(atBottom) {
        if (viewModel.isAtBottom != atBottom) {
            viewModel.isAtBottom = atBottom
            if (atBottom) viewModel.markAsRead()
        }
    }

    // Scroll target from reply clicks and search hits.
    var jumpEventId by remember { mutableStateOf<String?>(null) }
    // Like jumpEventId but lands with no animation.
    var restoreEventId by remember { mutableStateOf<String?>(null) }
    // One-shot: on open, land on the first unread unless a saved position wins.
    var openUnreadScrollId by remember { mutableStateOf<String?>(null) }
    // Transient status capsule; auto-clears, cancel-and-replace on repeat.
    var transientNotice by remember { mutableStateOf<String?>(null) }
    var transientNoticeJob by remember { mutableStateOf<Job?>(null) }
    var showsDetails by remember { mutableStateOf(false) }

    fun showNotice(text: String) {
        transientNoticeJob?.cancel()
        transientNotice = text
        transientNoticeJob = scope.launch {
            delay(3_000)
            transientNotice = null
        }
    }

    // Back-fills until the event is loaded; a miss (redacted, or past the
    // pagination bound) shows a notice instead of failing silently.
    fun jump(eventId: String) {
        scope.launch {
            if (viewModel.ensureLoaded(eventId)) jumpEventId = eventId
            else showNotice("Couldn't find that message")
        }
    }

    /** Timeline index of an event; -1 when not loaded. */
    fun entryIndexOfEvent(eventId: String): Int = entries.indexOfFirst {
        (it as? TimelineEntry.Message)?.item?.eventId == eventId
    }

    /** Reverse-layout list index for a timeline index. */
    fun lazyIndex(entryIndex: Int): Int = entries.size - 1 - entryIndex

    suspend fun scrollToBottomAnchored(entryIndex: Int) {
        listState.scrollToItem(lazyIndex(entryIndex).coerceIn(0, entries.size - 1))
    }

    suspend fun scrollToTopAnchored(entryIndex: Int) {
        val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
        val rowApprox = with(density) { 48.dp.toPx() }.toInt()
        listState.scrollToItem(
            lazyIndex(entryIndex).coerceIn(0, entries.size - 1),
            scrollOffset = -(viewport - rowApprox).coerceAtLeast(0),
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.start()
        // Land where the user left off, if that event is loaded; else on the
        // first unread; else the default bottom anchor.
        val saved = appState.timelineAnchor(forRoom = viewModel.roomId)
        val loaded = viewModel.entries.value
        if (saved != null && loaded.any { (it as? TimelineEntry.Message)?.item?.eventId == saved }) {
            restoreEventId = saved
        } else {
            viewModel.firstUnreadMarkerId.value?.let { openUnreadScrollId = it }
        }
        // Member profiles back the read-receipt avatars on rows.
        viewModel.loadMembers()
    }

    // Backup save (e.g. shell teardown). Skip if teardown drained the
    // visibility set — the room-switch path already saved.
    DisposableEffect(viewModel) {
        onDispose {
            if (viewModel.isAtBottom || viewModel.visibleEntryIds.isNotEmpty()) {
                appState.setTimelineAnchor(viewModel.scrollAnchorEventId, forRoom = viewModel.roomId)
            }
        }
    }

    // Follow the tail while at the bottom, and always for our own message —
    // including when the local echo is replaced by the confirmed event, which
    // would otherwise stop the follow and leave us just above the bottom.
    val lastEntryId = entries.lastOrNull()?.id
    LaunchedEffect(lastEntryId) {
        if (lastEntryId == null) return@LaunchedEffect
        val sentOwn = (entries.lastOrNull() as? TimelineEntry.Message)?.item?.isOwn == true
        if (viewModel.isAtBottom || sentOwn) {
            listState.scrollToItem(0)
            if (viewModel.isAtBottom) viewModel.markAsRead()
        }
    }

    // Land on the first unread on open, no travel animation.
    LaunchedEffect(openUnreadScrollId) {
        val id = openUnreadScrollId ?: return@LaunchedEffect
        openUnreadScrollId = null
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) scrollToTopAnchored(index)
    }

    // Unpark restore (the phone keeps the view mounted, so the start effect
    // doesn't re-run): land back on the pre-switch position after the reset.
    LaunchedEffect(unparkScrollTarget) {
        val eventId = unparkScrollTarget ?: return@LaunchedEffect
        viewModel.clearUnparkScrollTarget()
        val index = entryIndexOfEvent(eventId)
        if (index >= 0) scrollToBottomAnchored(index)
    }

    // Scroll-memory restore: land there instantly.
    LaunchedEffect(restoreEventId) {
        val eventId = restoreEventId ?: return@LaunchedEffect
        restoreEventId = null
        val index = entryIndexOfEvent(eventId)
        if (index >= 0) scrollToBottomAnchored(index)
    }

    LaunchedEffect(jumpEventId) {
        val eventId = jumpEventId ?: return@LaunchedEffect
        jumpEventId = null
        val index = entryIndexOfEvent(eventId)
        if (index >= 0) {
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            listState.animateScrollToItem(
                lazyIndex(index).coerceIn(0, entries.size - 1),
                scrollOffset = -viewport / 2,
            )
        }
    }

    // A ring accepted from the banner joins here once the room is on screen.
    val pendingCallJoin by appState.pendingCallJoin.collectAsStateWithLifecycle()
    LaunchedEffect(pendingCallJoin) {
        if (pendingCallJoin == viewModel.roomId) {
            appState.pendingCallJoin.value = null
            onStartCall()
        }
    }

    // Cross-room event navigation (global search): the shell opened the room;
    // consume the event half once the timeline can show it.
    val pendingEventNavigation by appState.pendingEventNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(pendingEventNavigation) {
        val navigation = pendingEventNavigation ?: return@LaunchedEffect
        if (navigation.roomId != viewModel.roomId) return@LaunchedEffect
        appState.pendingEventNavigation.value = null
        // Don't fire a stale request when the room is finally visited later.
        if (System.currentTimeMillis() - navigation.requestedAt < 30_000) jump(navigation.eventId)
    }

    val isVideoRoom = videoRoomIds.contains(viewModel.roomId) || isVideoRoomFlag
    val callHidden = activeCallRoomIds.contains(viewModel.roomId)

    // Recomputed as members load (dmPeerId reads the members list).
    val dmPeerId = remember(membersById, isDirect) { viewModel.dmPeerId }

    // Elevation-on-scroll: the bar lifts (a hairline separator fades in) once
    // the newest message is scrolled past the fold. Reverse layout means the
    // list is "at rest" at index 0/offset 0, so scrolled-away is simply not
    // that anchor — no nested-scroll wiring, no layout change, so it composes
    // cleanly and behaves correctly with reverseLayout = true.
    val barScrolledAway by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    Column(modifier.fillMaxSize().background(colors.bgApp).statusBarsPadding()) {
        TimelineTopBar(
            roomName = roomName,
            isDirect = isDirect,
            isEncrypted = isEncrypted,
            avatarUrl = avatarUrl,
            dmPeerId = dmPeerId,
            emoteLoader = emoteLoader,
            closeChat = closeChat,
            onStartCall = onStartCall,
            onOpenSearch = onOpenSearch,
            onOpenDetails = { showsDetails = true },
        )
        // The separator is the elevation cue: invisible while at the tail,
        // fading to the standard hairline once content scrolls beneath the bar.
        val separatorAlpha by animateFloatAsState(
            targetValue = if (barScrolledAway) 1f else 0f,
            animationSpec = tween(durationMillis = if (prefs.reduceMotion) 0 else 180),
            label = "timelineBarSeparator",
        )
        HorizontalDivider(
            color = colors.separator,
            thickness = 0.5.dp,
            modifier = Modifier.alpha(separatorAlpha),
        )

        // Banner motion: a smooth Material tween (the default expand is a
        // spring that can overshoot the banner height); collapses to an
        // instant show/hide under the reduce-motion preference.
        val bannerEnter = remember(prefs.reduceMotion) {
            if (prefs.reduceMotion) EnterTransition.None
            else expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220))
        }
        val bannerExit = remember(prefs.reduceMotion) {
            if (prefs.reduceMotion) ExitTransition.None
            else shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(220))
        }

        // A video room is a standing call; the join affordance is always present.
        AnimatedVisibility(
            visible = isVideoRoom && !callHidden,
            enter = bannerEnter,
            exit = bannerExit,
        ) {
            CallBanner(
                icon = { Icon(Icons.Filled.Videocam, null, tint = Color(0xFF3B82F6)) },
                text = if (hasActiveCall) "Video room — call in progress" else "Video room",
                tint = Color(0xFF3B82F6),
                onJoin = onStartCall,
            )
        }
        AnimatedVisibility(
            visible = !isVideoRoom && hasActiveCall && !callHidden,
            enter = bannerEnter,
            exit = bannerExit,
        ) {
            CallBanner(
                icon = { Icon(Icons.Filled.Phone, null, tint = Color(0xFF34C759)) },
                text = "Call in progress",
                tint = Color(0xFF34C759),
                onJoin = onStartCall,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp),
            ) {
                items(
                    count = entries.size,
                    key = { entries[entries.size - 1 - it].id },
                ) { index ->
                    val entry = entries[entries.size - 1 - index]
                    // Row-visibility bookkeeping: composition tracks the lazy
                    // viewport (plus a small prefetch window, like iOS's
                    // LazyVStack onAppear). Keyed on entry.id only and mutating
                    // the shared set in place, so scroll churn is one cheap
                    // add/remove per row with no per-frame allocation.
                    DisposableEffect(entry.id) {
                        visibleEntryIds.add(entry.id)
                        onDispose { visibleEntryIds.remove(entry.id) }
                    }
                    // The single unread-marker row tracks its own on-screen
                    // state, split out so the churn above never re-fires when
                    // the marker id changes (only this one row cares).
                    if (entry.id == firstUnreadMarkerId) {
                        DisposableEffect(entry.id) {
                            viewModel.setUnreadMarkerOnScreen(true)
                            onDispose { viewModel.setUnreadMarkerOnScreen(false) }
                        }
                    }
                    when (entry) {
                        is TimelineEntry.Message -> MessageRow(
                            message = entry.item,
                            viewModel = viewModel,
                            prefs = prefs,
                            lastOwnMessageId = lastOwnMessageId,
                            shield = entry.item.eventId?.let { shields[it] },
                            membersById = membersById,
                            canRedactOwn = canRedactOwn,
                            canRedactOther = canRedactOther,
                            ownDisplayName = ownDisplayName,
                            ownAvatarUrl = ownAvatarUrl,
                            emoteLoader = emoteLoader,
                            mediaRenderers = mediaRenderers,
                            openThread = onOpenThread,
                            openProfile = onOpenProfile,
                            jumpToEvent = { jump(it) },
                            onMoreReactions = onMoreReactions,
                        )
                        is TimelineEntry.System -> SystemRow(entry.text)
                        is TimelineEntry.DayDivider -> DayDividerView(entry.date)
                        is TimelineEntry.ReadMarker ->
                            // The inline "NEW" divider auto-clears once seen (and
                            // stays gone on return), tracked by the same dismissal
                            // state as the jump pill.
                            if (unreadMarkerVisible) ReadMarkerView()
                        is TimelineEntry.TimelineStart -> TimelineStartView()
                        is TimelineEntry.Hidden -> {}
                    }
                }
                if (!reachedStart) {
                    item(key = "pagination-header") { PaginationHeader(viewModel) }
                }
            }

            // Jump-to-present, alone observing `atBottom` (iOS overlay parity).
            Box(Modifier.align(Alignment.BottomEnd)) {
                // Qualified: an implicit ColumnScope up-stack would otherwise
                // capture the call and reject the BoxScope context.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !atBottom,
                    enter = if (prefs.reduceMotion) EnterTransition.None
                        else fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = if (prefs.reduceMotion) ExitTransition.None
                        else fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(end = 10.dp, bottom = 6.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.bgElevated2)
                            .clickable(onClickLabel = "Jump to latest") {
                                scope.launch {
                                    if (entries.isNotEmpty()) listState.animateScrollToItem(0)
                                }
                            },
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                            tint = colors.textSecondary,
                        )
                    }
                }
            }

            // "Jump to unread" pill: appears when the read marker is loaded
            // but scrolled out of view.
            Box(Modifier.align(Alignment.TopCenter)) {
                val pillVisible = unreadMarkerVisible && firstUnreadMarkerId != null &&
                    !atBottom && !unreadMarkerOnScreen
                androidx.compose.animation.AnimatedVisibility(
                    visible = pillVisible,
                    enter = if (prefs.reduceMotion) EnterTransition.None
                        else fadeIn() + slideInVertically { -it },
                    exit = if (prefs.reduceMotion) ExitTransition.None
                        else fadeOut() + slideOutVertically { -it },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clip(CircleShape)
                            .background(colors.bgElevated2)
                            .clickable {
                                val id = viewModel.firstUnreadMarkerId.value ?: return@clickable
                                scope.launch {
                                    val index = entries.indexOfFirst { it.id == id }
                                    if (index >= 0) scrollToTopAnchored(index)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowUp,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Jump to unread",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            // Transient status capsule.
            Box(Modifier.align(Alignment.TopCenter)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = transientNotice != null,
                    enter = if (prefs.reduceMotion) EnterTransition.None
                        else fadeIn() + slideInVertically { -it },
                    exit = if (prefs.reduceMotion) ExitTransition.None
                        else fadeOut() + slideOutVertically { -it },
                ) {
                    Text(
                        transientNotice ?: "",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(CircleShape)
                            .background(colors.bgElevated2)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            if (error != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(40.dp), tint = colors.textSecondary)
                    Text("Timeline Unavailable", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary)
                    Text(error ?: "", fontSize = 14.sp, color = colors.textSecondary,
                        textAlign = TextAlign.Center)
                }
            } else if (entries.isEmpty()) {
                // Only until the initial page lands (an empty room still gets
                // a timeline-start entry), so this never sticks.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
                    Text("Loading messages…", fontSize = 14.sp, color = colors.textSecondary)
                }
            }
        }

        composer()
    }

    if (showsDetails) {
        RoomDetailsSheet(
            viewModel = viewModel,
            emoteLoader = emoteLoader,
            appState = appState,
            isEncrypted = isEncrypted,
            hasActiveCall = hasActiveCall,
            onOpenProfile = onOpenProfile,
            onInvitePeople = onInvitePeople,
            onOpenRoomSettings = onOpenRoomSettings,
            jumpToEvent = { eventId ->
                showsDetails = false
                jump(eventId)
            },
            onDismiss = { showsDetails = false },
        )
    }
}

/**
 * Fused title bar: back chevron (slide-over layer), avatar + name + lock as
 * one tappable element opening room details, then call/search actions —
 * the iOS iPhone toolbar arrangement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineTopBar(
    roomName: String,
    isDirect: Boolean,
    isEncrypted: Boolean,
    avatarUrl: String?,
    dmPeerId: String?,
    emoteLoader: EmoteAssetLoader?,
    closeChat: (() -> Unit)?,
    onStartCall: () -> Unit,
    onOpenSearch: (() -> Unit)?,
    onOpenDetails: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    // Native Material 3 top app bar: standard navigation icon + title + action
    // slots, replacing the iOS-style custom header row.
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.bgApp,
            titleContentColor = colors.textPrimary,
            navigationIconContentColor = colors.textPrimary,
            actionIconContentColor = colors.textPrimary,
        ),
        navigationIcon = {
            if (closeChat != null) {
                IconButton(onClick = closeChat) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        },
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Shows room details", onClick = onOpenDetails)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                // DM peer presence dot on the room avatar (iOS .presenceIndicator).
                PresenceIndicator(userId = dmPeerId, size = 10.dp) {
                    TimelineAvatarView(name = roomName, size = 32.dp, avatarUrl = avatarUrl, loader = emoteLoader)
                }
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            roomName,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isEncrypted) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "End-to-end encrypted",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    // DM peer's Commet status as a caption under the room name.
                    if (dmPeerId != null) {
                        val pronounsStore = LocalPronounsStore.current
                        // Observed so the caption lands with the profile fetch and
                        // tracks live presence status changes.
                        pronounsStore?.cache?.collectAsStateWithLifecycle()?.value
                        LocalPresenceService.current?.entries(dmPeerId)
                            ?.collectAsStateWithLifecycle()?.value
                        pronounsStore?.status(dmPeerId)?.takeIf { it.isNotEmpty() }?.let { status ->
                            Text(
                                status,
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onStartCall) {
                Icon(Icons.Outlined.Call, contentDescription = "Start or join a call")
            }
            if (onOpenSearch != null) {
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search this room")
                }
            }
        },
    )
}

@Composable
private fun CallBanner(
    icon: @Composable () -> Unit,
    text: String,
    tint: Color,
    onJoin: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon()
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary,
            modifier = Modifier.weight(1f))
        Button(
            onClick = onJoin,
            colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp),
        ) {
            Text("Join", fontSize = 13.sp)
        }
    }
}

/**
 * Visibility-driven, not keyed on entry count: the count changes on every
 * diff, which cancelled and re-fired pagination constantly. Polls while the
 * header is composed (paginateBackwards has its own reentrancy guard); dies
 * on disposal or reachedStart.
 */
@Composable
private fun PaginationHeader(viewModel: TimelineViewModel) {
    val colors = LocalDiscourseColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
    }
    LaunchedEffect(Unit) {
        while (isActive && !viewModel.reachedStart.value) {
            viewModel.paginateBackwards()
            delay(1_000)
        }
    }
}

@Composable
fun SystemRow(text: String) {
    val colors = LocalDiscourseColors.current
    // Gutter math mirrors MessageRow (40dp gutter, 10dp gap, 8dp inset) so
    // "X joined" aligns with message text.
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp, horizontal = 8.dp),
    ) {
        Text(
            "→",
            fontSize = 11.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp),
        )
        Text(text, fontSize = 15.sp, color = colors.textSecondary)
    }
}

@Composable
fun DayDividerView(epochMillis: Long) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = colors.separator)
        Text(
            dayDividerText(epochMillis),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
        HorizontalDivider(Modifier.weight(1f), color = colors.separator)
    }
}

@Composable
fun ReadMarkerView() {
    val red = Color(0xFFFF453A)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = red.copy(alpha = 0.6f))
        Text("NEW", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = red)
        HorizontalDivider(Modifier.weight(1f), color = red.copy(alpha = 0.6f))
    }
}

@Composable
fun TimelineStartView() {
    val colors = LocalDiscourseColors.current
    Text(
        "This is the beginning of the conversation.",
        fontSize = 15.sp,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    )
}

// MARK: Room details sheet

/**
 * iPhone-style room details: header, actions, and the role-grouped member
 * list with moderation. The Media tab and the presence-based Offline section
 * attach in the media and presence phases (comment-marked).
 */
@Composable
private fun RoomDetailsSheet(
    viewModel: TimelineViewModel,
    emoteLoader: EmoteAssetLoader?,
    appState: AppState,
    isEncrypted: Boolean,
    hasActiveCall: Boolean,
    onOpenProfile: (ProfileTarget) -> Unit,
    onInvitePeople: (() -> Unit)?,
    onOpenRoomSettings: (() -> Unit)?,
    jumpToEvent: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val topic by viewModel.topic.collectAsStateWithLifecycle()
    val avatarUrl by viewModel.avatarUrl.collectAsStateWithLifecycle()
    val memberCount by viewModel.memberCount.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val membersLoadFailed by viewModel.membersLoadFailed.collectAsStateWithLifecycle()
    val canInvite by viewModel.canInvite.collectAsStateWithLifecycle()
    val canKick by viewModel.canKick.collectAsStateWithLifecycle()
    val canBan by viewModel.canBan.collectAsStateWithLifecycle()
    val canChangePowerLevels by viewModel.canChangePowerLevels.collectAsStateWithLifecycle()
    val ownPowerLevel by viewModel.ownPowerLevel.collectAsStateWithLifecycle()
    // Observed so role headers re-resolve as the Cinny tags event loads.
    val powerLevelTags by viewModel.powerLevelTags.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var moderation by remember { mutableStateOf<ModerationAction?>(null) }
    var moderationError by remember { mutableStateOf<String?>(null) }
    var customLevelTarget by remember { mutableStateOf<TimelineViewModel.MemberItem?>(null) }
    var roleError by remember { mutableStateOf<String?>(null) }
    var showsMediaGallery by remember { mutableStateOf(false) }

    fun applyRole(member: TimelineViewModel.MemberItem, level: Int) {
        scope.launch {
            roleError = viewModel.setPowerLevel(member.id, level.coerceIn(0, ownPowerLevel))
        }
    }

    LaunchedEffect(Unit) { viewModel.loadMembers() }

    // Same predicate as iOS MemberListView.filteredMembers.
    val trimmedQuery = query.trim()
    val filtered = if (trimmedQuery.isEmpty()) members else members.filter {
        it.name.contains(trimmedQuery, ignoreCase = true) || it.id.contains(trimmedQuery, ignoreCase = true)
    }
    // Members grouped by their actual power level, highest first — each level
    // is a named role via `in.cinny.room.power_level_tags`. Confirmed-offline
    // members split into a dimmed bottom section (iOS membersSections); the
    // not-yet-fetched stay up top until proven offline.
    @Suppress("UNUSED_EXPRESSION") powerLevelTags
    val presence = LocalPresenceService.current
    // Aggregate tick so the grouping re-evaluates as presence entries land.
    presence?.changeTick?.collectAsStateWithLifecycle()?.value
    val offlineMembers = filtered.filter {
        presence?.state(of = it.id) == com.riiiiiiiley.discourse.core.PresenceService.State.OFFLINE
    }
    val offlineIds = offlineMembers.map { it.id }.toSet()
    val groups = filtered.filter { it.id !in offlineIds }
        .groupBy { it.powerLevel }.entries.sortedByDescending { it.key }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // Searching narrows to member results; the static sections would
            // only push them below the fold.
            if (trimmedQuery.isEmpty()) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    ) {
                        TimelineAvatarView(name = roomName, size = 72.dp, avatarUrl = avatarUrl, loader = emoteLoader)
                        Text(roomName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary, textAlign = TextAlign.Center)
                        topic?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 15.sp, color = colors.textSecondary,
                                textAlign = TextAlign.Center)
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            FactLabel(icon = { Icon(Icons.Outlined.Group, null, Modifier.size(14.dp), tint = colors.textSecondary) },
                                text = "$memberCount members")
                            if (isEncrypted) {
                                FactLabel(icon = { Icon(Icons.Filled.Lock, null, Modifier.size(13.dp), tint = colors.presenceOnline) },
                                    text = "Encrypted", tint = colors.presenceOnline)
                            }
                            if (hasActiveCall) {
                                FactLabel(icon = { Icon(Icons.Filled.Phone, null, Modifier.size(13.dp), tint = colors.presenceOnline) },
                                    text = "Call in progress", tint = colors.presenceOnline)
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        if (canInvite && onInvitePeople != null) {
                            SheetActionRow(
                                icon = { Icon(Icons.Outlined.PersonAdd, null, tint = colors.textPrimary) },
                                label = "Invite People…",
                                onClick = { onInvitePeople() },
                            )
                        }
                        if (onOpenRoomSettings != null) {
                            SheetActionRow(
                                icon = { Icon(Icons.Outlined.Settings, null, tint = colors.textPrimary) },
                                label = "Room Settings…",
                                onClick = { onOpenRoomSettings() },
                            )
                        }
                        SheetActionRow(
                            icon = { Icon(Icons.Outlined.ContentCopy, null, tint = colors.textPrimary) },
                            label = "Copy Room ID",
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, viewModel.roomId)))
                                }
                            },
                        )
                        SheetActionRow(
                            icon = { Icon(Icons.Outlined.Image, null, tint = colors.textPrimary) },
                            label = "Media & Files",
                            onClick = { showsMediaGallery = true },
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search members") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = colors.textSecondary) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (members.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        if (membersLoadFailed) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Couldn't Load Members", color = colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { scope.launch { viewModel.loadMembers(force = true) } }) {
                                    Text("Retry")
                                }
                            }
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent)
                        }
                    }
                }
            } else if (filtered.isEmpty()) {
                item {
                    Text(
                        "No results for “$trimmedQuery”",
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            @Composable
            fun memberRow(member: TimelineViewModel.MemberItem, dimmed: Boolean) {
                MemberRow(
                    member = member,
                    ownUserId = viewModel.ownUserId,
                    emoteLoader = emoteLoader,
                    canKick = canKick,
                    canBan = canBan,
                    canChangeRoles = canChangePowerLevels,
                    ownPowerLevel = ownPowerLevel,
                    onSetRole = { level ->
                        if (level == null) customLevelTarget = member else applyRole(member, level)
                    },
                    dimmed = dimmed,
                    onOpenProfile = {
                        onOpenProfile(ProfileTarget(member.id, member.displayName, member.avatarUrl))
                    },
                    onMessage = {
                        scope.launch {
                            viewModel.startDm(member.id)?.let {
                                appState.pendingRoomNavigation.value = it
                                onDismiss()
                            }
                        }
                    },
                    onCopyId = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, member.id)))
                        }
                    },
                    onModerate = { isBan -> moderation = ModerationAction(member, isBan) },
                )
            }
            for ((level, groupMembers) in groups) {
                item(key = "role-$level") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    ) {
                        RoleTagLabel(tag = viewModel.roleTag(forLevel = level), loader = emoteLoader)
                        Text("— ${groupMembers.size}", fontSize = 14.sp, color = colors.textTertiary)
                    }
                }
                items(count = groupMembers.size, key = { "member-${groupMembers[it].id}" }) { i ->
                    memberRow(groupMembers[i], dimmed = false)
                }
            }
            // Confirmed-offline members, dimmed, below every role group.
            if (offlineMembers.isNotEmpty()) {
                item(key = "role-offline") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    ) {
                        Text("Offline", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary)
                        Text("— ${offlineMembers.size}", fontSize = 14.sp, color = colors.textTertiary)
                    }
                }
                items(count = offlineMembers.size, key = { "member-${offlineMembers[it].id}" }) { i ->
                    memberRow(offlineMembers[i], dimmed = true)
                }
            }
        }
    }

    // MARK: Media & Files gallery (iOS NavigationLink → MediaGalleryView).

    if (showsMediaGallery) {
        ModalBottomSheet(
            onDismissRequest = { showsMediaGallery = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true),
            containerColor = colors.bgApp,
        ) {
            MediaGalleryView(
                viewModel = viewModel.mediaViewModel(),
                jumpToEvent = { eventId ->
                    showsMediaGallery = false
                    jumpToEvent(eventId)
                },
            )
        }
    }

    // Kick/ban confirmation dialog and failure alert (iOS ModerationPrompts).
    moderation?.let { action ->
        AlertDialog(
            onDismissRequest = { moderation = null },
            title = {
                Text(if (action.isBan) "Ban ${action.member.name} from this room?"
                    else "Remove ${action.member.name} from this room?")
            },
            text = {
                Text(if (action.isBan) "They won't be able to rejoin until unbanned."
                    else "They can rejoin if the room allows it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    moderation = null
                    scope.launch {
                        moderationError = if (action.isBan) viewModel.ban(action.member.id)
                            else viewModel.kick(action.member.id)
                    }
                }) { Text(if (action.isBan) "Ban" else "Remove", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { moderation = null }) { Text("Cancel") } },
        )
    }
    moderationError?.let { message ->
        AlertDialog(
            onDismissRequest = { moderationError = null },
            title = { Text("Couldn't do that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { moderationError = null }) { Text("OK") } },
        )
    }
    customLevelTarget?.let { member ->
        var levelText by remember(member.id) { mutableStateOf(member.powerLevel.toString()) }
        AlertDialog(
            onDismissRequest = { customLevelTarget = null },
            title = { Text("Set power level") },
            text = {
                Column {
                    Text("Higher levels have more privileges. You can grant up to your own level ($ownPowerLevel).",
                        fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = levelText,
                        onValueChange = { levelText = it.filter(Char::isDigit) },
                        singleLine = true,
                        label = { Text("0–$ownPowerLevel") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val level = levelText.toIntOrNull()
                    customLevelTarget = null
                    if (level != null) applyRole(member, level)
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { customLevelTarget = null }) { Text("Cancel") } },
        )
    }
    roleError?.let { message ->
        AlertDialog(
            onDismissRequest = { roleError = null },
            title = { Text("Couldn't change role") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { roleError = null }) { Text("OK") } },
        )
    }
}

private data class ModerationAction(val member: TimelineViewModel.MemberItem, val isBan: Boolean)

@Composable
private fun FactLabel(icon: @Composable () -> Unit, text: String, tint: Color? = null) {
    val colors = LocalDiscourseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Text(text, fontSize = 13.sp, color = tint ?: colors.textSecondary)
    }
}

@Composable
private fun SheetActionRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        icon()
        Text(label, fontSize = 16.sp, color = colors.textPrimary)
    }
}

/** A named role: its emoji (unicode or custom emote) and its name in the tag color. */
@Composable
fun RoleTagLabel(tag: PowerLevelTag, loader: EmoteAssetLoader?) {
    val colors = LocalDiscourseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tag.iconKey?.takeIf { it.isNotEmpty() }?.let { key ->
            if (tag.iconIsMxc) {
                EmoteImageView(url = key, size = 15.dp, loader = loader)
            } else {
                Text(key, fontSize = 13.sp)
            }
        }
        val tagColor = tag.color?.let { hex ->
            runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
        } ?: colors.textSecondary
        Text(tag.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tagColor)
    }
}

// MARK: Media gallery

/** iOS `.dateTime.day().month().year()` for the attachment rows. */
private val galleryDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

private fun galleryDateText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(galleryDateFormat)

/**
 * The Media tab: image thumbnail grid on top, other attachments as rows.
 * Backed by an attachment-filtered timeline that back-fills on demand
 * (iOS MediaGalleryView).
 */
@Composable
private fun MediaGalleryView(
    viewModel: TimelineViewModel,
    jumpToEvent: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val loader = LocalMediaLoader.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val reachedStart by viewModel.reachedStart.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }

    // Newest first, like the iOS `entries.reversed()` scan.
    val messages = remember(entries) {
        entries.asReversed().mapNotNull { (it as? TimelineEntry.Message)?.item }
    }
    val images = remember(messages) {
        messages.mapNotNull { message ->
            (message.kind as? MessageItem.Kind.Image)?.let { message to it.item }
        }
    }
    val others = remember(messages) { messages.filter { it.kind !is MessageItem.Kind.Image } }

    Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(62.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = images.size, key = { "img-${images[it].first.id}" }) { i ->
                val (message, image) = images[i]
                MediaThumbCell(
                    image = image,
                    loader = loader,
                    // Thumbnail-only button; give it a spoken name.
                    contentDescription = image.caption
                        ?: image.filename.ifEmpty { "Image" },
                    onClick = { message.eventId?.let(jumpToEvent) },
                )
            }
            items(
                count = others.size,
                key = { "row-${others[it].id}" },
                span = { GridItemSpan(maxLineSpan) },
            ) { i ->
                val message = others[i]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { message.eventId?.let(jumpToEvent) }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Icon(
                        when (val kind = message.kind) {
                            is MessageItem.Kind.Audio ->
                                if (kind.item.isVoiceMessage) Icons.Outlined.GraphicEq
                                else Icons.Outlined.MusicNote
                            is MessageItem.Kind.Media -> materialIconFor(kind.systemImage)
                            else -> Icons.Outlined.Description
                        },
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            when (val kind = message.kind) {
                                is MessageItem.Kind.Audio ->
                                    if (kind.item.isVoiceMessage) "Voice message"
                                    else kind.item.filename
                                is MessageItem.Kind.Media -> kind.label
                                else -> "Attachment"
                            },
                            fontSize = 15.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            galleryDateText(message.timestamp),
                            fontSize = 11.sp,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
            if (!reachedStart) {
                item(key = "gallery-paginate", span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent,
                            strokeWidth = 2.dp)
                    }
                    // Visibility-driven, like the main timeline's header.
                    // Attachments are sparse; keep back-filling while visible.
                    // Dies on disposal, reachedStart, or once enough is loaded.
                    LaunchedEffect(Unit) {
                        while (isActive && !viewModel.reachedStart.value &&
                            viewModel.entries.value.size < 80
                        ) {
                            viewModel.paginateBackwards()
                            delay(1_000)
                        }
                    }
                }
            }
        }
        if (messages.isEmpty() && reachedStart) {
            // ContentUnavailableView("No Media") parity.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Icon(Icons.Outlined.Image, null, Modifier.size(40.dp), tint = colors.textSecondary)
                Text("No Media", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary)
                Text("Nothing has been shared here yet.", fontSize = 14.sp,
                    color = colors.textSecondary, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Square thumbnail for the media grid (iOS MediaThumbCell). */
@Composable
private fun MediaThumbCell(
    image: ImageItem,
    loader: MediaLoader?,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    // Clamped so extreme densities don't fragment cache keys (iOS parity).
    val pixelSize = 62f * LocalDensity.current.density.coerceIn(1f, 3f)
    var thumb by remember(image.source.url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(image.source.url, loader) {
        if (loader != null) thumb = loader.thumbnail(image.source, pixelSize)
    }
    // Seeded from cache so recycled grid cells don't flash the placeholder.
    val display = thumb ?: loader?.cachedThumbnail(image.source, pixelSize)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(62.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = contentDescription, onClick = onClick),
    ) {
        if (display != null) {
            Image(
                bitmap = display.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(quaternaryFill(colors)))
            CircularProgressIndicator(Modifier.size(16.dp), color = colors.textSecondary,
                strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun MemberRow(
    member: TimelineViewModel.MemberItem,
    ownUserId: String,
    emoteLoader: EmoteAssetLoader?,
    canKick: Boolean,
    canBan: Boolean,
    canChangeRoles: Boolean = false,
    ownPowerLevel: Int = 0,
    onSetRole: (level: Int?) -> Unit = {},
    dimmed: Boolean = false,
    onOpenProfile: () -> Unit,
    onMessage: () -> Unit,
    onCopyId: () -> Unit,
    onModerate: (isBan: Boolean) -> Unit,
) {
    // Can re-level a target strictly below our own level (and not ourselves).
    val canSetRole = canChangeRoles && member.id != ownUserId && member.powerLevel < ownPowerLevel
    val colors = LocalDiscourseColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    // iOS MemberRowLabel: presence dot on the avatar, pronouns tag next to
    // the name, Commet custom status under it; offline rows dim to 0.6.
    val pronounsStore = LocalPronounsStore.current
    pronounsStore?.cache?.collectAsStateWithLifecycle()?.value
    Box(Modifier.alpha(if (dimmed) 0.6f else 1f)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpenProfile, onLongClick = { menuExpanded = true })
                .padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            PresenceIndicator(userId = member.id, size = 8.dp) {
                TimelineAvatarView(name = member.name, size = 32.dp, avatarUrl = member.avatarUrl, loader = emoteLoader)
            }
            Column(Modifier.weight(1f, fill = false)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        member.name,
                        fontSize = 16.sp,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    pronounsStore?.pronouns(member.id)?.let { pronouns ->
                        Text(pronouns, fontSize = 12.sp, color = colors.textTertiary, maxLines = 1)
                    }
                    if (member.id == ownUserId) {
                        Text("you", fontSize = 12.sp, color = colors.textTertiary)
                    }
                }
                // Commet custom status, Discord-style, under the name.
                pronounsStore?.status(member.id)?.takeIf { it.isNotEmpty() }?.let { status ->
                    Text(
                        status,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("View Profile") }, onClick = {
                menuExpanded = false
                onOpenProfile()
            })
            if (member.id != ownUserId) {
                DropdownMenuItem(text = { Text("Message") }, onClick = {
                    menuExpanded = false
                    onMessage()
                })
            }
            DropdownMenuItem(text = { Text("Copy User ID") }, onClick = {
                menuExpanded = false
                onCopyId()
            })
            if (canSetRole) {
                HorizontalDivider()
                if (ownPowerLevel >= 100 && member.powerLevel != 100) {
                    DropdownMenuItem(text = { Text("Make Administrator (100)") }, onClick = {
                        menuExpanded = false
                        onSetRole(100)
                    })
                }
                if (ownPowerLevel > 50 && member.powerLevel != 50) {
                    DropdownMenuItem(text = { Text("Make Moderator (50)") }, onClick = {
                        menuExpanded = false
                        onSetRole(50)
                    })
                }
                if (member.powerLevel != 0) {
                    DropdownMenuItem(text = { Text("Make Member (0)") }, onClick = {
                        menuExpanded = false
                        onSetRole(0)
                    })
                }
                DropdownMenuItem(text = { Text("Set level…") }, onClick = {
                    menuExpanded = false
                    onSetRole(null)
                })
            }
            if (member.id != ownUserId && (canKick || canBan)) {
                HorizontalDivider()
                if (canKick) {
                    DropdownMenuItem(
                        text = { Text("Remove from Room…", color = Color(0xFFFF453A)) },
                        onClick = {
                            menuExpanded = false
                            onModerate(false)
                        },
                    )
                }
                if (canBan) {
                    DropdownMenuItem(
                        text = { Text("Ban from Room…", color = Color(0xFFFF453A)) },
                        onClick = {
                            menuExpanded = false
                            onModerate(true)
                        },
                    )
                }
            }
        }
    }
}
