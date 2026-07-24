package com.riiiiiiiley.discourse.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.NotificationManager
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.call.IncomingCallBanner
import com.riiiiiiiley.discourse.features.call.PhoneCallCover
import com.riiiiiiiley.discourse.features.compose.InviteSheet
import com.riiiiiiiley.discourse.features.compose.JoinRoomSheet
import com.riiiiiiiley.discourse.features.compose.NewChatSheet
import com.riiiiiiiley.discourse.features.compose.NewDirectMessageSheet
import com.riiiiiiiley.discourse.features.compose.NewRoomSheet
import com.riiiiiiiley.discourse.features.quickswitcher.QuickSwitcherView
import com.riiiiiiiley.discourse.features.roomlist.SidebarView
import com.riiiiiiiley.discourse.features.roomlist.SpacesRail
import com.riiiiiiiley.discourse.features.search.SearchResultsSheet
import com.riiiiiiiley.discourse.features.settings.RoomSettingsSheet
import com.riiiiiiiley.discourse.features.settings.SettingsTabScreen
import com.riiiiiiiley.discourse.features.settings.SettingsTarget
import com.riiiiiiiley.discourse.features.verification.VerificationSheet
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.abs

private enum class PhoneTab { CHAT, SETTINGS }

/** Pager settle spring, the analogue of the iOS `.pagerSettle` animation. */
private fun pagerSettle() = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * The signed-in main window: 68dp spaces rail + room list as one layer, the
 * open chat sliding over it as a full-screen pager layer, and a floating
 * Chat/Settings tab bar — the port of the iOS MainWindow phone layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(appState: AppState, scope: SessionScope) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val roomList = scope.roomList
    val prefs by appState.preferences.state.collectAsStateWithLifecycle()
    val selectedSpaceId by roomList.selectedSpaceId.collectAsStateWithLifecycle()
    val activeCallRoomIds by appState.activeCallRoomIds.collectAsStateWithLifecycle()
    // Hoisted out of the hot pager content lambda: each subscription is created
    // once here, so only its own emission triggers a narrow recomposition
    // rather than being re-established inside a per-frame-invalidated lambda.
    val otherAccountsHaveUnread by appState.otherAccountsHaveUnread.collectAsStateWithLifecycle()
    val sidebarFilterFocusRequest by appState.sidebarFilterFocusRequest.collectAsStateWithLifecycle()

    /**
     * The chat mounted over the room list. Distinct from `selectedRoom`,
     * which persists per space: swiping back slides the chat away but the
     * room stays selected.
     */
    var pushedRoomId by rememberSaveable(scope.userId) { mutableStateOf<String?>(null) }
    var selectedRoom by rememberSaveable(scope.userId) { mutableStateOf<String?>(null) }
    var phoneTab by rememberSaveable { mutableStateOf(PhoneTab.CHAT) }
    // Stable tab-select callback so the tab bar isn't recomposed by an
    // identity-changing lambda when the shell recomposes.
    val onSelectTab: (PhoneTab) -> Unit = remember { { phoneTab = it } }
    var showsVerification by remember { mutableStateOf(false) }
    /** 0 = room list, 1 = chat fully on screen; tracks the finger mid-swipe. */
    val chatProgress = remember { Animatable(0f) }
    var showsPhoneCall by remember(scope.userId) { mutableStateOf(false) }

    // MARK: Per-space room-selection memory (iOS @AppStorage roomSelectionBySpace)

    val selectionPrefs = remember {
        context.getSharedPreferences("discourse", Context.MODE_PRIVATE)
    }
    val json = remember { Json { ignoreUnknownKeys = true } }
    fun selectionMap(): Map<String, String> {
        val raw = selectionPrefs.getString("roomSelectionBySpace", null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
    }
    fun rememberSelection(roomId: String?, forKey: String) {
        val map = selectionMap().toMutableMap()
        if (roomId == null) map.remove(forKey) else map[forKey] = roomId
        selectionPrefs.edit {
            putString("roomSelectionBySpace", json.encodeToString(map))
        }
    }

    fun spaceKey() = "${scope.userId}|${roomList.selectedSpaceId.value ?: "home"}"
    val spaceMemoryKey = "${scope.userId}|__space"

    fun setFocusedChat(roomId: String?) {
        // Suppresses the focused room's banners and clears delivered ones.
        NotificationManager.focusedRoomId = roomId
        roomList.setActiveRoom(roomId)
    }

    /** Slides the chat layer in (mounting it first if it's a different room). */
    fun openChat(roomId: String) {
        // The call cover hangs off the pushed room's timeline; remounting for
        // a different room would tear it down and hang up. Drop foreign-room
        // navigation while a call is live.
        val current = pushedRoomId
        if (current != null && current != roomId &&
            appState.activeCallRoomIds.value.contains(current)) {
            return
        }
        // Opening a room always lands on the Chat tab.
        phoneTab = PhoneTab.CHAT
        if (pushedRoomId != roomId) pushedRoomId = roomId
        val vm = scope.timeline(roomId)
        vm?.isParked = false
        vm?.markAsRead()
        setFocusedChat(roomId)
        coroutineScope.launch {
            if (prefs.reduceTimelineMotion) chatProgress.snapTo(1f)
            else chatProgress.animateTo(1f, pagerSettle())
        }
    }

    /**
     * Slides the chat layer out but keeps it mounted, parked offscreen, so
     * swiping back reveals it as left.
     */
    fun closeChat() {
        keyboard?.hide()
        focusManager.clearFocus()
        pushedRoomId?.let { id ->
            scope.timeline(id)?.let { vm ->
                appState.setTimelineAnchor(vm.scrollAnchorEventId, forRoom = id)
                vm.isParked = true
            }
        }
        setFocusedChat(null)
        coroutineScope.launch {
            if (prefs.reduceTimelineMotion) chatProgress.snapTo(0f)
            else chatProgress.animateTo(0f, pagerSettle())
        }
    }

    /** Selects a room and slides its chat screen in (row taps, navigation). */
    fun selectRoom(roomId: String?) {
        // Save the outgoing room's scroll anchor while its visibility set is
        // still intact; teardown drains it.
        selectedRoom?.takeIf { it != roomId }?.let { old ->
            scope.timeline(old)?.let { vm ->
                appState.setTimelineAnchor(vm.scrollAnchorEventId, forRoom = old)
            }
        }
        selectedRoom = roomId
        // Foreign-space opens (quick switcher, notifications, search) stay
        // transient: only persist when the room belongs to the current view,
        // or when clearing (null) unwinds it.
        val belongs = roomId == null || run {
            val visible = roomList.visibleRoomIds.value
            if (visible != null) visible.contains(roomId)
            else roomList.rooms.value.firstOrNull { it.id == roomId }
                ?.let { it.isDirect || !roomList.allSpaceChildIds.value.contains(roomId) }
                ?: false
        }
        if (belongs) rememberSelection(roomId, forKey = spaceKey())
        if (roomId != null) openChat(roomId)
    }

    // Notification routing (the iOS MainWindow onAppear wiring): taps open
    // rooms (switching accounts first when the banner belongs to a background
    // account), Reply/Mark-as-Read run against the owning warm scope, and the
    // call transitions drive AppState.ringingCall.
    LaunchedEffect(appState, scope) {
        NotificationManager.loadAvatar = { mxcUrl, accountUserId ->
            appState.notificationAvatarBitmap(mxcUrl, accountUserId)
        }
        // Only label the account when more than one is signed in; show the
        // full user id (@user:server) so it's unambiguous.
        NotificationManager.accountLabel = { accountUserId ->
            if (appState.accountTokens.value.size > 1) accountUserId else null
        }
        NotificationManager.openRoom = { roomId, _, accountUserId ->
            appState.launchDetached {
                // A background account's room isn't in this scope; switch
                // first, then the fresh shell's navigation collector picks up
                // the pending navigation. A notification's event is the newest
                // message, already at the bottom once the room opens — no
                // event jump (iOS comment: it only paginates backwards).
                if (accountUserId != null && accountUserId != appState.activeUserId) {
                    appState.switchAccount(to = accountUserId)
                }
                appState.pendingRoomNavigation.value = roomId
            }
        }
        NotificationManager.sendReply = { roomId, text, accountUserId ->
            appState.launchDetached {
                // Background-account replies go straight to the owning warm
                // scope, no account switch.
                val target = appState.sessionForNotificationAction(accountUserId)
                target?.sendMessage(text, toRoomId = roomId)
            }
        }
        NotificationManager.markRoomRead = { roomId, accountUserId ->
            appState.launchDetached {
                appState.sessionForNotificationAction(accountUserId)
                    ?.roomList?.markRead(listOf(roomId))
            }
        }
        NotificationManager.onIncomingCall = { room ->
            if (appState.ringingCall.value == null) {
                appState.ringingCall.value = AppState.RingingCall(
                    roomId = room.id, roomName = room.name,
                    avatarUrl = room.avatarUrl, isDirect = room.isDirect,
                )
            }
        }
        NotificationManager.onCallEnded = { roomId ->
            // Caller hung up before answer: stop the ring.
            if (appState.ringingCall.value?.roomId == roomId) {
                appState.ringingCall.value = null
            }
        }
    }

    // Startup: monitor + profile don't depend on the room list; run them
    // alongside start(). Auto-prompt verification once both land.
    LaunchedEffect(scope) {
        scope.startVerificationMonitor()
        val start = launch { runCatching { roomList.start() } }
        val profile = launch { runCatching { scope.loadOwnProfile() } }
        start.join()
        profile.join()
        if (scope.needsVerification.value) showsVerification = true
    }

    // Reopen last session's space + its remembered room (iOS onAppear).
    LaunchedEffect(scope) {
        if (selectedRoom == null) {
            val map = selectionMap()
            val storedSpace = map[spaceMemoryKey]
            if (storedSpace != null && storedSpace != "home" &&
                roomList.selectedSpaceId.value == null) {
                // Its room is restored by the selectedSpaceId effect below.
                roomList.selectSpace(storedSpace)
            } else {
                selectedRoom = map[spaceKey()]
            }
        }
        // The list is what's on screen at launch; only an open chat
        // suppresses its notifications/unreads.
        setFocusedChat(if (chatProgress.value >= 1f) pushedRoomId else null)
    }

    // Each space keeps its own remembered room (skip the initial value).
    var lastSpaceForMemory by remember(scope.userId) { mutableStateOf(selectedSpaceId) }
    LaunchedEffect(selectedSpaceId) {
        if (selectedSpaceId == lastSpaceForMemory) return@LaunchedEffect
        lastSpaceForMemory = selectedSpaceId
        selectedRoom = selectionMap()["${scope.userId}|${selectedSpaceId ?: "home"}"]
        rememberSelection(selectedSpaceId ?: "home", forKey = spaceMemoryKey)
        // Keep the parked chat layer in sync so a swipe-in reveals the
        // selected room, pre-warmed.
        if (chatProgress.value == 0f && pushedRoomId != selectedRoom) {
            selectedRoom?.let { scope.timeline(it)?.isParked = true }
            pushedRoomId = selectedRoom
        }
    }

    // Notification/search navigation; collected (not keyed) so a set just
    // before an account-switch remount still lands (iOS initial: true).
    LaunchedEffect(appState) {
        appState.pendingRoomNavigation.collect { roomId ->
            if (roomId != null) {
                selectRoom(roomId)
                appState.pendingRoomNavigation.value = null
            }
        }
    }
    LaunchedEffect(appState) {
        appState.pendingEventNavigation.collect { navigation ->
            // Not cleared here: the timeline consumes it once it has scrolled.
            if (navigation != null) selectRoom(navigation.roomId)
        }
    }

    // Pager pan on the ROOT container: parent pointer-input nodes observe the
    // whole subtree (after children), so rows/buttons keep their taps and the
    // pan wins only once horizontal slop is exceeded. A sibling overlay would
    // instead block all touches underneath.
    var panWidthPx by remember { mutableStateOf(1f) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .pointerInput(phoneTab) {
                if (phoneTab != PhoneTab.CHAT) return@pointerInput
                var accepted: Boolean? = null
                var panBase = 0f
                var totalDrag = 0f
                // Smoothed per-frame delta: an exponential moving average of the
                // last few frames instead of the raw last delta. A single-frame
                // delta is noisy — a stall frame at release reads as zero
                // velocity, a catch-up frame over-predicts — which made the
                // settle target flip inconsistently and the release look jumpy.
                var velEma = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        accepted = null
                        totalDrag = 0f
                        velEma = 0f
                    },
                    onDragEnd = {
                        if (accepted != true) return@detectHorizontalDragGestures
                        accepted = false
                        // Project the fling ~0.18s out (delta/frame ≈ velocity·16ms).
                        val velocity = velEma / 0.016f
                        val predicted =
                            panBase - (totalDrag + velocity * 0.18f) / panWidthPx
                        if (predicted > 0.5f) {
                            pushedRoomId?.let { id ->
                                val vm = scope.timeline(id)
                                vm?.isParked = false
                                vm?.markAsRead()
                                setFocusedChat(id)
                            }
                            coroutineScope.launch {
                                chatProgress.animateTo(1f, pagerSettle())
                            }
                        } else {
                            keyboard?.hide()
                            pushedRoomId?.let { id ->
                                scope.timeline(id)?.let { vm ->
                                    appState.setTimelineAnchor(
                                        vm.scrollAnchorEventId, forRoom = id)
                                    vm.isParked = true
                                }
                            }
                            setFocusedChat(null)
                            coroutineScope.launch {
                                chatProgress.animateTo(0f, pagerSettle())
                            }
                        }
                    },
                    onDragCancel = { accepted = false },
                ) { change, dragAmount ->
                    if (accepted == false) return@detectHorizontalDragGestures
                    if (accepted == null) {
                        // Direction gate on first movement: open chat only
                        // closes rightward (leftward is swipe-to-reply); the
                        // list only opens leftward, with a chat to show.
                        val atChat = chatProgress.value >= 1f
                        val atList = chatProgress.value <= 0f
                        accepted = when {
                            atChat -> dragAmount > 0f
                            atList -> dragAmount < 0f &&
                                (pushedRoomId != null || selectedRoom != null)
                            else -> true
                        }
                        if (accepted != true) return@detectHorizontalDragGestures
                        if (pushedRoomId == null) {
                            // Nothing parked yet (fresh session): mount the
                            // selected room, parked, so the drag reveals it.
                            val room = selectedRoom
                                ?: run { accepted = false; return@detectHorizontalDragGestures }
                            scope.timeline(room)?.isParked = true
                            pushedRoomId = room
                        } else if (chatProgress.value > 0f) {
                            // Closing drag: drop the keyboard with the layer.
                            keyboard?.hide()
                        }
                        panBase = chatProgress.value
                    }
                    change.consume()
                    totalDrag += dragAmount
                    // Weighted toward recent frames (≈3-frame smoothing) so the
                    // release momentum matches the finger, not one last frame.
                    velEma = velEma * 0.6f + dragAmount * 0.4f
                    // Finger-tracking must never animate.
                    val target = (panBase - totalDrag / panWidthPx).coerceIn(0f, 1f)
                    coroutineScope.launch { chatProgress.snapTo(target) }
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        panWidthPx = widthPx
        // `chatProgress.value` is intentionally NOT read here in composition:
        // it ticks every frame (finger snapTo + settle spring), so a
        // composition-phase read would recompose the whole shell subtree ~60x/s
        // while swiping. Every consumer below reads it in the layout/draw phase
        // (offset {}, drawBehind {}, graphicsLayer {}) so a swipe touches only
        // layout + draw, never recomposition.

        // MARK: Base layer (rail + room list, or Settings) with parallax.

        Box(
            Modifier
                .fillMaxSize()
                .offset {
                    if (phoneTab == PhoneTab.CHAT)
                        IntOffset((-chatProgress.value * widthPx * 0.25f).toInt(), 0)
                    else IntOffset.Zero
                },
        ) {
            // Cross-fade the Chat<->Settings tab content so the swap eases in
            // instead of hard-cutting. The container above keeps the parallax
            // offset keyed to the live `phoneTab`; this only fades the content.
            Crossfade(
                targetState = phoneTab,
                animationSpec = if (prefs.reduceTimelineMotion) snap()
                    else tween(durationMillis = 250),
                label = "phone-tab",
            ) { tab ->
            if (tab == PhoneTab.CHAT) {
                Row(Modifier.fillMaxSize()) {
                    SpacesRail(
                        viewModel = roomList,
                        preferences = appState.preferences,
                        onNewSpace = { appState.newChatSheet.value = NewChatSheet.Space },
                    )
                    SidebarView(
                        scope = scope,
                        viewModel = roomList,
                        selection = selectedRoom,
                        onSelect = { selectRoom(it) },
                        onNewChatSheet = { appState.newChatSheet.value = it },
                        onShowVerification = { showsVerification = true },
                        filterFocusRequest = sidebarFilterFocusRequest,
                        searchResultsSheet = { query, onDismiss ->
                            // Full height (iOS uses a plain .sheet, no detents);
                            // half-expanded clips the result list and the
                            // in-list "Load More Results" button.
                            ModalBottomSheet(
                                onDismissRequest = onDismiss,
                                sheetState = rememberModalBottomSheetState(
                                    skipPartiallyExpanded = true),
                                containerColor = colors.bgApp,
                            ) {
                                SearchResultsSheet(
                                    scope = scope, roomList = roomList,
                                    appState = appState, query = query,
                                    onDismiss = onDismiss,
                                )
                            }
                        },
                        roomSettingsSheet = { target, onDismiss ->
                            RoomSettingsLayer(scope, target, onDismiss)
                        },
                        inviteSheet = { roomId, roomName, onDismiss ->
                            ModalBottomSheet(
                                onDismissRequest = onDismiss,
                                containerColor = colors.bgApp,
                            ) {
                                InviteSheet(
                                    scope = scope, roomList = roomList,
                                    roomId = roomId, roomName = roomName,
                                    onDismiss = onDismiss,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                SettingsTabScreen(
                    appState = appState,
                    scope = scope,
                    mediaLoader = scope.mediaLoader,
                )
            }
            }

            // Dim wash as the chat rides over the list. Draw-only (no
            // pointer handling), so it never swallows touches. Always composed
            // while on the Chat tab; the alpha is read in the DRAW phase via
            // drawBehind so ramping it with the swipe never recomposes. At
            // progress 0 it draws a fully-transparent rect (a no-op).
            if (phoneTab == PhoneTab.CHAT) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(Color.Black, alpha = 0.15f * chatProgress.value)
                        },
                )
            }

            // Floating tab bar, owned by the base layer so the chat covers it.
            PhoneTabBar(
                selected = phoneTab,
                otherAccountsHaveUnread = otherAccountsHaveUnread,
                onSelect = onSelectTab,
                // NavigationBar applies its own system-bar inset; no extra pad.
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // MARK: Chat layer.

        if (phoneTab == PhoneTab.CHAT) {
            pushedRoomId?.let { roomId ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset { IntOffset(((1f - chatProgress.value) * widthPx).toInt(), 0) }
                        // Ramp the drop-shadow with the slide so the elevation
                        // eases in with the pager instead of snapping on at 0.
                        // graphicsLayer drives elevation in the layout/draw
                        // phase (reading chatProgress.value there), so the
                        // shadow animates without recomposing the shell.
                        .graphicsLayer {
                            shadowElevation = 14.dp.toPx() * chatProgress.value
                            clip = false
                        }
                        .background(colors.bgApp),
                ) {
                    key(roomId) {
                        RoomTimelineLayer(
                            appState = appState,
                            scope = scope,
                            roomId = roomId,
                            closeChat = { closeChat() },
                            onStartCall = { showsPhoneCall = true },
                        )
                    }
                }
            }
        }

        // MARK: Ringing banner overlay (top; animation scoped to the overlay).
        // Composed BEFORE the call cover so a live call's cover draws OVER this
        // banner (iOS z-order: the window banner stays audible but invisible
        // during a call; PhoneCallCover mirrors its own banner with the
        // end-current-call-first accept path).

        val ringing by appState.ringingCall.collectAsStateWithLifecycle()
        AnimatedVisibility(
            visible = ringing != null,
            enter = if (prefs.reduceTimelineMotion) fadeIn()
                else slideInVertically(spring(stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn(),
            exit = if (prefs.reduceTimelineMotion) fadeOut()
                else slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        ) {
            ringing?.let { call ->
                IncomingCallBanner(
                    call = call,
                    accept = {
                        appState.ringingCall.value = null
                        appState.pendingCallJoin.value = call.roomId
                        appState.pendingRoomNavigation.value = call.roomId
                    },
                    decline = { appState.ringingCall.value = null },
                )
            }
        }

        // MARK: Call cover (full-screen, over everything including the ring banner).

        if (showsPhoneCall) {
            pushedRoomId?.let { roomId ->
                PhoneCallCover(
                    appState = appState,
                    roomId = roomId,
                    call = scope.calls.callForRoom(roomId),
                    endCall = { scope.calls.endCall(roomId) },
                    onDismiss = { showsPhoneCall = false },
                )
            }
        }
    }

    // MARK: Window-level sheets.

    val quickSwitcherShown by appState.isQuickSwitcherPresented.collectAsStateWithLifecycle()
    if (quickSwitcherShown) {
        val rooms by roomList.rooms.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = { appState.isQuickSwitcherPresented.value = false },
            containerColor = colors.bgApp,
        ) {
            QuickSwitcherView(
                rooms = rooms,
                open = { selectRoom(it) },
                onDismiss = { appState.isQuickSwitcherPresented.value = false },
            )
        }
    }

    val newChatSheet by appState.newChatSheet.collectAsStateWithLifecycle()
    newChatSheet?.let { sheet ->
        val dismiss = { appState.newChatSheet.value = null }
        val open: (String) -> Unit = { selectRoom(it) }
        ModalBottomSheet(onDismissRequest = dismiss, containerColor = colors.bgApp) {
            when (sheet) {
                is NewChatSheet.DirectMessage ->
                    NewDirectMessageSheet(scope = scope, open = open, onDismiss = dismiss)
                is NewChatSheet.Room ->
                    NewRoomSheet(scope = scope, roomList = roomList, isSpace = false,
                                 destinationSpaceId = sheet.spaceId, open = open,
                                 onDismiss = dismiss)
                is NewChatSheet.VideoRoom ->
                    NewRoomSheet(scope = scope, roomList = roomList, isSpace = false,
                                 destinationSpaceId = sheet.spaceId, isVideoRoom = true,
                                 open = open, onDismiss = dismiss)
                is NewChatSheet.Space ->
                    NewRoomSheet(scope = scope, roomList = roomList, isSpace = true,
                                 open = open, onDismiss = dismiss)
                is NewChatSheet.Join ->
                    JoinRoomSheet(scope = scope, open = open, onDismiss = dismiss)
            }
        }
    }

    // Manual/auto verification sheet (VerificationSheet hosts its own modal).
    if (showsVerification) {
        VerificationSheet(scope = scope, onDismiss = { showsVerification = false })
    }

    // Incoming-request sheet, keyed on flowId so a new request re-presents.
    val incoming by scope.incomingVerification.collectAsStateWithLifecycle()
    incoming?.let { request ->
        key(request.flowId) {
            VerificationSheet(
                scope = scope,
                incoming = request,
                onDismiss = { scope.clearIncomingVerification() },
            )
        }
    }

    // Sign-out confirmation (menu command / rail switcher parity).
    val signOutShown by appState.isSignOutConfirmPresented.collectAsStateWithLifecycle()
    if (signOutShown) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { appState.isSignOutConfirmPresented.value = false },
            containerColor = colors.bgElevated2,
            title = { Text("Sign out of ${scope.userId}?", color = colors.textPrimary) },
            text = {
                Text("This signs ${scope.userId} out of Discourse on this device.",
                     color = colors.textSecondary)
            },
            confirmButton = {
                TextButton(onClick = {
                    appState.isSignOutConfirmPresented.value = false
                    coroutineScope.launch { appState.logOut() }
                }) { Text("Sign Out", color = colors.unreadMention) }
            },
            dismissButton = {
                TextButton(onClick = { appState.isSignOutConfirmPresented.value = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

/**
 * Full-screen room-settings host: the sheet draws its own title bar with
 * Back/Done and handles internal navigation + system back.
 */
@Composable
internal fun RoomSettingsLayer(
    scope: SessionScope,
    target: SettingsTarget,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(LocalDiscourseColors.current.bgApp)) {
            RoomSettingsSheet(
                scope = scope,
                roomList = scope.roomList,
                target = target,
                timelineForRoom = { scope.timeline(it) },
                onDismiss = onDismiss,
            )
        }
    }
}

/** Floating Chat/Settings capsule (iOS phoneTabBar). */
@Composable
private fun PhoneTabBar(
    selected: PhoneTab,
    otherAccountsHaveUnread: Boolean,
    onSelect: (PhoneTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    // Native Material 3 bottom navigation bar (full width, self-inset for the
    // system nav bar), replacing the iOS-style floating pill.
    NavigationBar(
        modifier = modifier,
        containerColor = colors.bgElevated,
    ) {
        NavigationBarItem(
            selected = selected == PhoneTab.CHAT,
            onClick = { onSelect(PhoneTab.CHAT) },
            icon = { Icon(Icons.Filled.Forum, contentDescription = null) },
            label = { Text("Chat") },
        )
        NavigationBarItem(
            selected = selected == PhoneTab.SETTINGS,
            onClick = { onSelect(PhoneTab.SETTINGS) },
            icon = {
                // Badge when another (non-active) account has unread activity.
                BadgedBox(badge = { if (otherAccountsHaveUnread) Badge() }) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                }
            },
            label = { Text("Settings") },
            modifier = Modifier.semantics {
                if (otherAccountsHaveUnread) {
                    stateDescription = "Other accounts have unread messages"
                }
            },
        )
    }
}

