package com.riiiiiiiley.discourse.features.roomlist

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.ArrowCircleRight
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.compose.NewChatSheet
import com.riiiiiiiley.discourse.features.settings.SettingsTarget
import com.riiiiiiiley.discourse.models.RoomSummary
import com.riiiiiiiley.discourse.ui.media.LocalMediaLoader
import com.riiiiiiiley.discourse.ui.presence.PresenceDot
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Which "new …" sheet to present, the Kotlin analogue of the iOS `NewChatSheet`
 * enum (NewChatSheets.swift). Defined here until the compose slice lands; if
 * that slice ships its own, keep one and update the callers.
 */
// NewChatSheet lives in features.compose (NewChatSheets.kt); SettingsTarget
// in features.settings (RoomSettingsSheet.kt) — single definitions app-wide.

/**
 * Display-name/avatar lookup for call-participant strips; PronounsStore
 * (profiles phase) provides it. Null falls back to user-id localparts, the
 * same behavior iOS has with a nil store.
 */
interface ProfileNameSource {
    fun displayName(userId: String): String?
    fun avatarUrl(userId: String): String?
}

val LocalProfileNames = compositionLocalOf<ProfileNameSource?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarView(
    scope: SessionScope,
    viewModel: RoomListViewModel,
    selection: String?,
    onSelect: (String?) -> Unit,
    onNewChatSheet: (NewChatSheet) -> Unit,
    onShowVerification: () -> Unit,
    /**
     * View ▸ Filter Rooms analogue: bump the counter
     * (AppState.sidebarFilterFocusRequest) to focus the search field.
     */
    filterFocusRequest: Int = 0,
    /**
     * Sheet content owned by other features. Each slot presents its own sheet
     * (ModalBottomSheet or dialog) and calls `onDismiss` when done. One driver
     * for all sidebar sheets — only one is ever presented at a time (the iOS
     * `SidebarModal` unification).
     */
    searchResultsSheet: (@Composable (query: String, onDismiss: () -> Unit) -> Unit)? = null,
    roomSettingsSheet: (@Composable (target: SettingsTarget, onDismiss: () -> Unit) -> Unit)? = null,
    inviteSheet: (@Composable (roomId: String, roomName: String, onDismiss: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    val orderedSpaces by viewModel.orderedSpaces.collectAsStateWithLifecycle()
    val selectedSpaceId by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    val visibleRoomIds by viewModel.visibleRoomIds.collectAsStateWithLifecycle()
    val allSpaceChildIds by viewModel.allSpaceChildIds.collectAsStateWithLifecycle()
    val spaceChildIds by viewModel.spaceChildIds.collectAsStateWithLifecycle()
    val spaceChildren by viewModel.spaceChildren.collectAsStateWithLifecycle()
    val invitableRoomIds by viewModel.invitableRoomIds.collectAsStateWithLifecycle()
    val manageableSpaceIds by viewModel.manageableSpaceIds.collectAsStateWithLifecycle()
    val moveableRoomIds by viewModel.moveableRoomIds.collectAsStateWithLifecycle()
    val joiningRoomIds by viewModel.joiningRoomIds.collectAsStateWithLifecycle()
    val joiningInviteIds by viewModel.joiningInviteIds.collectAsStateWithLifecycle()
    val syncBanner by viewModel.syncBanner.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val isLoaded by viewModel.isLoaded.collectAsStateWithLifecycle()
    val isReconnecting by viewModel.isReconnecting.collectAsStateWithLifecycle()
    val needsVerification by scope.needsVerification.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }

    /** Trails `searchQuery` by ~150ms to avoid re-filtering the list per keystroke. */
    var debouncedQuery by remember { mutableStateOf("") }
    // Restarting the effect is the cancel-and-replace; clearing skips the
    // debounce so the clear button doesn't leave a stale list.
    LaunchedEffect(searchQuery) {
        if (searchQuery.trim().isEmpty()) {
            debouncedQuery = searchQuery
        } else {
            delay(150)
            debouncedQuery = searchQuery
        }
    }

    var leaveTarget by remember { mutableStateOf<RoomSummary?>(null) }
    var modal by remember { mutableStateOf<SidebarModal?>(null) }
    var spaceBannerUrl by remember { mutableStateOf<String?>(null) }
    var showsSpaceHome by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Room context menu state; a two-page dropdown stands in for the iOS
    // nested Spaces submenu.
    var menuRoomId by remember { mutableStateOf<String?>(null) }
    var menuShowsSpaces by remember { mutableStateOf(false) }

    val searchFocus = remember { FocusRequester() }
    val initialFocusRequest = remember { filterFocusRequest }
    LaunchedEffect(filterFocusRequest) {
        if (filterFocusRequest != initialFocusRequest) searchFocus.requestFocus()
    }

    val trimmedQuery = debouncedQuery.trim()
    val foldedQuery = remember(trimmedQuery) { RoomSummary.foldedForSearch(trimmedQuery) }

    /** Invites are account-level, not space-level, so they show in every space. */
    val invites = remember(rooms) { rooms.filter { it.isInvited } }

    val visibleRooms = remember(rooms, allSpaceChildIds, visibleRoomIds, foldedQuery) {
        rooms.filter { room ->
            if (room.isSpace || room.isInvited) return@filter false
            if (foldedQuery.isNotEmpty() && !room.foldedName.contains(foldedQuery)) {
                return@filter false
            }
            visibleRoomIds?.let { return@filter it.contains(room.id) }
            // Home: people always, rooms only if not filed in a space.
            room.isDirect || !allSpaceChildIds.contains(room.id)
        }
    }
    // Rooms and DMs in one list, most recent activity first.
    val sorted = remember(visibleRooms) {
        visibleRooms.sortedByDescending { it.lastActivity ?: Long.MIN_VALUE }
    }

    val selectedSpace = remember(selectedSpaceId, spaces) {
        selectedSpaceId?.let { id -> spaces.firstOrNull { it.id == id } }
    }

    /** Rooms the selected space advertises that we haven't joined yet. */
    val unjoinedSpaceRooms = remember(selectedSpaceId, spaceChildren, foldedQuery) {
        val spaceId = selectedSpaceId ?: return@remember emptyList()
        // Same folded matching as the joined-room filter.
        (spaceChildren[spaceId] ?: emptyList()).filter { child ->
            !child.isSpace && !child.isJoined &&
                (foldedQuery.isEmpty() ||
                    RoomSummary.foldedForSearch(child.name).contains(foldedQuery))
        }
    }

    /** Spaces whose names match the filter; selecting one jumps to it. */
    val matchingSpaces = remember(orderedSpaces, foldedQuery) {
        if (foldedQuery.isEmpty()) emptyList()
        else orderedSpaces.filter { RoomSummary.foldedForSearch(it.name).contains(foldedQuery) }
    }

    LaunchedEffect(selectedSpaceId) {
        spaceBannerUrl = null
        selectedSpaceId?.let { spaceId ->
            viewModel.refreshInvitePermission(spaceId)
            spaceBannerUrl = viewModel.spaceBannerUrl(spaceId)
        }
    }

    /** Sync status for the header title; null once caught up. */
    val headerStatus: String? = when {
        isReconnecting -> "Reconnecting…"
        viewModel.isCatchingUp -> "Updating…"
        else -> null
    }

    fun canInvite(roomId: String): Boolean = invitableRoomIds.contains(roomId)

    // System reduce-motion switch (mirrors SpacesRail). SidebarView doesn't
    // receive Preferences, so the in-app reduceTimelineMotion toggle isn't read
    // here; the OS animator-scale check is the established gate for this folder.
    val reduceMotion = rememberSystemReduceMotion()

    Box(modifier.fillMaxSize().background(colors.bgSidebar)) {
      Column(Modifier.fillMaxSize()) {
        // Header backed by the sidebar surface so the list scrolls under it (the
        // theme's tinted background already carries the iOS windowWash).
        Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            // Space switcher and "new" menu atop the room list.
            var showsSpaceMenu by remember { mutableStateOf(false) }
            var showsNewMenu by remember { mutableStateOf(false) }
            // Material 3 top app bar; its title is the tappable space switcher,
            // with a "+" action to start a new chat.
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bgApp,
                    titleContentColor = colors.textPrimary,
                ),
                title = {
                Box {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showsSpaceMenu = true }
                            .padding(vertical = 2.dp, horizontal = 4.dp)
                            .defaultMinSize(minHeight = 44.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Sync status stacks under the title; content stays browsable.
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                selectedSpace?.name ?: "Home",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (headerStatus != null) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = colors.textSecondary,
                                    )
                                    Text(
                                        headerStatus,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showsSpaceMenu,
                        onDismissRequest = { showsSpaceMenu = false },
                    ) {
                        SpaceMenuItems(
                            selectedSpace = selectedSpace,
                            canInvite = selectedSpace?.let { canInvite(it.id) } == true,
                            dismiss = { showsSpaceMenu = false },
                            onJoinRoom = { onNewChatSheet(NewChatSheet.Join) },
                            onInvitePeople = { space ->
                                rooms.firstOrNull { it.id == space.id }?.let {
                                    modal = SidebarModal.Invite(it.id, it.name)
                                }
                            },
                            onSpaceSettings = { space ->
                                modal = SidebarModal.Settings(SettingsTarget(space.id, isSpace = true))
                            },
                            onRefreshRooms = { space ->
                                coroutineScope.launch { viewModel.selectSpace(space.id) }
                            },
                            onMarkAllRead = {
                                viewModel.markRead(
                                    selectedSpace?.let { viewModel.childRoomIds(it.id) }
                                        ?: viewModel.homeRoomIds)
                            },
                            onLeaveSpace = { space ->
                                rooms.firstOrNull { it.id == space.id }?.let { leaveTarget = it }
                            },
                        )
                    }
                }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showsNewMenu = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "New message or room",
                                tint = colors.textPrimary,
                            )
                        }
                        DropdownMenu(
                            expanded = showsNewMenu,
                            onDismissRequest = { showsNewMenu = false },
                        ) {
                            NewMenuItems(
                                selectedSpace = selectedSpace,
                                dismiss = { showsNewMenu = false },
                                onNewChatSheet = onNewChatSheet,
                            )
                        }
                    }
                },
            )

            // Search only in Home; a space's room list is short enough that a
            // second search bar is redundant.
            if (selectedSpaceId == null) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(colors.bgInput)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .heightIn(min = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    // Filters rooms as you type; Search reaches message content.
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium
                            .copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (searchQuery.trim().isNotEmpty()) {
                                modal = SidebarModal.SearchResults
                            }
                        }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textTertiary,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Cancel,
                                contentDescription = "Clear search",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                // In a space, mirror "Refresh Rooms". On Home, selectSpace(null) is
                // a no-op, so crawl every space to refresh the filed-room exclusions.
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        try {
                            val id = viewModel.selectedSpaceId.value
                            if (id != null) viewModel.selectSpace(id)
                            else viewModel.refreshAllSpaceChildren()
                        } finally {
                            isRefreshing = false
                        }
                    }
                },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom clearance so the last rooms scroll clear of the
                    // NavigationBar (~80dp + the system nav inset it consumes)
                    // rather than sitting behind it — including when the list is
                    // too short to scroll.
                    contentPadding = PaddingValues(
                        bottom = 80.dp +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                ) {
                    spaceBannerUrl?.let { banner ->
                        item(key = "space-banner") {
                            Box(
                                Modifier
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showsSpaceHome = true },
                            ) {
                                BannerImageView(banner, Modifier.matchParentSize())
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = "Space home",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (needsVerification) {
                        item(key = "verify") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onShowVerification)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // iOS semantic .orange.
                                val orange = Color(0xFFFF9500)
                                Icon(Icons.Filled.Shield, contentDescription = null,
                                     tint = orange, modifier = Modifier.size(18.dp))
                                Text(
                                    "Verify this session",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = orange,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                    syncBanner?.let { banner ->
                        item(key = "sync-banner") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.WifiOff, contentDescription = null,
                                     tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                                Text(banner, style = MaterialTheme.typography.bodyMedium,
                                     color = colors.textSecondary, maxLines = 2)
                            }
                        }
                    }
                    // One-off action failures (join/leave/invite); auto-cleared.
                    actionError?.let { error ->
                        item(key = "action-error") {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val orange = Color(0xFFFF9500)
                                Icon(Icons.Outlined.Warning, contentDescription = null,
                                     tint = orange, modifier = Modifier.size(18.dp))
                                Text(error, style = MaterialTheme.typography.bodyMedium,
                                     color = orange, maxLines = 2)
                            }
                        }
                    }
                    // Name filtering only; this row reaches message content.
                    if (trimmedQuery.isNotEmpty()) {
                        item(key = "search-messages") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable { modal = SidebarModal.SearchResults }
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ManageSearch, contentDescription = null,
                                     tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                                Text(
                                    "Search messages for “$trimmedQuery”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // The room list excludes spaces, so match them separately here.
                    if (matchingSpaces.isNotEmpty()) {
                        item(key = "header-spaces") { SectionHeader("Spaces") }
                        items(
                            count = matchingSpaces.size,
                            key = { "space-${matchingSpaces[it].id}" },
                        ) { index ->
                            val space = matchingSpaces[index]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .clickable {
                                        searchQuery = ""
                                        debouncedQuery = ""
                                        coroutineScope.launch { viewModel.selectSpace(space.id) }
                                    }
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RoomAvatarView(name = space.name, isDirect = false,
                                               size = 28.dp, avatarUrl = space.avatarUrl)
                                Text(space.name, style = MaterialTheme.typography.bodyLarge,
                                     color = colors.textPrimary, maxLines = 1,
                                     overflow = TextOverflow.Ellipsis,
                                     modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ChevronRight, contentDescription = null,
                                     tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    if (invites.isNotEmpty()) {
                        item(key = "header-invites") { SectionHeader("Invites") }
                        items(count = invites.size, key = { "invite-${invites[it].id}" }) { index ->
                            val room = invites[index]
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                InviteRow(
                                    room = room,
                                    isJoining = joiningInviteIds.contains(room.id),
                                    accept = {
                                        coroutineScope.launch {
                                            viewModel.acceptInvite(room.id)
                                            if (!room.isSpace) onSelect(room.id)
                                        }
                                    },
                                    decline = {
                                        coroutineScope.launch { viewModel.leave(room.id) }
                                    },
                                )
                            }
                        }
                    }

                    items(count = sorted.size, key = { sorted[it].id }) { index ->
                        val room = sorted[index]
                        val isSelected = selection == room.id
                        // Row content is factored into a stable composable so the
                        // per-frame churn (weight/badge animations, ~100ms sort
                        // republishes) skips it: the room and its callbacks are the
                        // only inputs, and the callbacks are remembered by room id.
                        SidebarRoomRow(
                            room = room,
                            isSelected = isSelected,
                            reduceMotion = reduceMotion,
                            menuExpanded = menuRoomId == room.id,
                            menuShowsSpaces = menuShowsSpaces,
                            canInvite = canInvite(room.id),
                            canMove = moveableRoomIds.contains(room.id),
                            spaces = spaces,
                            manageableSpaceIds = manageableSpaceIds,
                            spaceChildIds = spaceChildIds,
                            onSelect = onSelect,
                            onLongPress = {
                                menuShowsSpaces = false
                                menuRoomId = room.id
                            },
                            onShowSpaces = { menuShowsSpaces = true },
                            onMenuDismiss = { menuRoomId = null },
                            onSettings = {
                                modal = SidebarModal.Settings(
                                    SettingsTarget(room.id, isSpace = false))
                            },
                            onInvite = { modal = SidebarModal.Invite(room.id, room.name) },
                            onMarkRead = { viewModel.markRead(listOf(room.id)) },
                            onToggleSpace = { spaceId ->
                                coroutineScope.launch {
                                    viewModel.toggleRoom(room.id, inSpace = spaceId)
                                }
                            },
                            onLeave = { leaveTarget = room },
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                                .then(
                                    // Placement animation so rows glide when the sort
                                    // reorders (most-recent-activity first); snap when
                                    // reduce-motion. No-bounce spring at a gentler
                                    // stiffness so the frequent ~100ms re-sorts read as
                                    // a smooth slide rather than a snap.
                                    if (reduceMotion) Modifier
                                    else Modifier.animateItem(
                                        placementSpec = spring(
                                            dampingRatio = 1f,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    ),
                                ),
                        )
                        // Prime the invite- and space-move-permission caches for
                        // visible rows, so the context menu (built synchronously) can
                        // filter without awaiting. Debounced so a fast fling doesn't
                        // fire FFI power-level fetches for every row it passes.
                        LaunchedEffect(room.id) {
                            delay(250)
                            viewModel.refreshInvitePermission(forRoomId = room.id)
                            viewModel.refreshMovePermission(forRoomId = room.id)
                            for (space in viewModel.spaces.value) {
                                viewModel.refreshSpaceManagePermission(spaceId = space.id)
                            }
                        }
                    }

                    // Everything else the space advertises, one click from joining.
                    if (unjoinedSpaceRooms.isNotEmpty()) {
                        item(key = "header-more-rooms") { SectionHeader("More Rooms") }
                        items(
                            count = unjoinedSpaceRooms.size,
                            key = { "more-${unjoinedSpaceRooms[it].id}" },
                        ) { index ->
                            val child = unjoinedSpaceRooms[index]
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                SpaceDirectoryRow(
                                    child = child,
                                    isJoining = joiningRoomIds.contains(child.id),
                                    join = {
                                        coroutineScope.launch {
                                            viewModel.joinSpaceChild(child)
                                            onSelect(child.id)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Only when the list is truly empty: during a name filter the
            // "Search messages…" row is the no-matches affordance, and pending
            // invites must stay visible.
            if (visibleRooms.isEmpty() && trimmedQuery.isEmpty() && invites.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLoaded) {
                        Icon(Icons.Outlined.Inbox, contentDescription = null,
                             tint = colors.textTertiary, modifier = Modifier.size(40.dp))
                        Text("No Rooms", style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text(
                            if (selectedSpaceId == null) "Join a room to get started."
                            else "This space has no rooms you've joined.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    } else {
                        CircularProgressIndicator(color = colors.accent)
                        Text("Syncing…", style = MaterialTheme.typography.bodyMedium,
                             color = colors.textSecondary)
                    }
                }
            }
        }
      }

    }

    if (showsSpaceHome) {
        selectedSpace?.let { space ->
            SpaceHomeView(
                space = space,
                bannerUrl = spaceBannerUrl,
                scope = scope,
                onDismiss = { showsSpaceHome = false },
            )
        }
    }

    when (val presented = modal) {
        is SidebarModal.SearchResults ->
            searchResultsSheet?.invoke(searchQuery) { modal = null }
        is SidebarModal.Settings ->
            roomSettingsSheet?.invoke(presented.target) { modal = null }
        is SidebarModal.Invite ->
            inviteSheet?.invoke(presented.roomId, presented.roomName) { modal = null }
        null -> Unit
    }

    leaveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { leaveTarget = null },
            title = { Text("Leave “${target.name}”?") },
            text = {
                Text(
                    when {
                        target.isSpace ->
                            "Rooms in the space stay joined. You'll need an invite to rejoin a private space."
                        target.isDirect -> "The conversation will be removed from your list."
                        else -> "You'll need an invite to rejoin a private room."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (selection == target.id) onSelect(null)
                    coroutineScope.launch { viewModel.leave(target.id) }
                    leaveTarget = null
                }) {
                    Text(
                        when {
                            target.isSpace -> "Leave Space"
                            target.isDirect -> "Leave Chat"
                            else -> "Leave Room"
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One room row plus its (lazily rendered) long-press context menu. Factored out
 * of the `items` block so the frequent host recompositions — the ~100ms sort
 * republishes while a busy room churns, plus this row's own weight/badge
 * animations — don't rebuild the whole subtree each frame. The click callbacks
 * are captured through `rememberUpdatedState` so the lambdas handed to
 * `combinedClickable` keep a stable identity across recompositions, and the
 * `RoomContextMenu` is only composed for the row that's actually expanded
 * (`menuExpanded`), so the other N-1 rows skip its `spaces.filter { … }` pass.
 */
@Composable
private fun SidebarRoomRow(
    room: RoomSummary,
    isSelected: Boolean,
    reduceMotion: Boolean,
    menuExpanded: Boolean,
    menuShowsSpaces: Boolean,
    canInvite: Boolean,
    canMove: Boolean,
    spaces: List<RoomListViewModel.SpaceItem>,
    manageableSpaceIds: Set<String>,
    spaceChildIds: Map<String, Set<String>>,
    onSelect: (String?) -> Unit,
    onLongPress: () -> Unit,
    onShowSpaces: () -> Unit,
    onMenuDismiss: () -> Unit,
    onSettings: () -> Unit,
    onInvite: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleSpace: (String) -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current

    // Keep the click lambdas stable across recompositions: combinedClickable sees
    // the same lambda identity, so it doesn't re-register on every republish.
    val roomId = room.id
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val onClick = remember(roomId) { { currentOnSelect(roomId) } }
    val onLongClick = remember(roomId) { { currentOnLongPress() } }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isSelected) colors.accent.copy(alpha = 0.85f)
                    else Color.Transparent,
                )
                // No swipe actions: horizontal swipes belong to the chat pager.
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            RoomRow(room = room, isSelected = isSelected, reduceMotion = reduceMotion)
            // Build the menu content only for the expanded row; DropdownMenu still
            // owns the show/hide, but the other rows skip its manageable-spaces
            // filter entirely.
            if (menuExpanded) {
                RoomContextMenu(
                    room = room,
                    expanded = true,
                    showsSpaces = menuShowsSpaces,
                    canInvite = canInvite,
                    canMove = canMove,
                    spaces = spaces,
                    manageableSpaceIds = manageableSpaceIds,
                    spaceChildIds = spaceChildIds,
                    onShowSpaces = onShowSpaces,
                    onDismiss = onMenuDismiss,
                    onSettings = onSettings,
                    onInvite = onInvite,
                    onMarkRead = onMarkRead,
                    onToggleSpace = onToggleSpace,
                    onLeave = onLeave,
                )
            }
        }
    }
}

/**
 * One driver for all sidebar sheets: only one presentation is ever in flight
 * (the iOS fix for stacked `.sheet(item:)` drops). Leave stays a separate dialog.
 */
private sealed class SidebarModal {
    data object SearchResults : SidebarModal()
    data class Settings(val target: SettingsTarget) : SidebarModal()
    data class Invite(val roomId: String, val roomName: String) : SidebarModal()
}

@Composable
private fun SectionHeader(title: String) {
    val colors = LocalDiscourseColors.current
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** Space actions in the header menu (iOS `spaceMenuItems`). */
@Composable
private fun SpaceMenuItems(
    selectedSpace: RoomListViewModel.SpaceItem?,
    canInvite: Boolean,
    dismiss: () -> Unit,
    onJoinRoom: () -> Unit,
    onInvitePeople: (RoomListViewModel.SpaceItem) -> Unit,
    onSpaceSettings: (RoomListViewModel.SpaceItem) -> Unit,
    onRefreshRooms: (RoomListViewModel.SpaceItem) -> Unit,
    onMarkAllRead: () -> Unit,
    onLeaveSpace: (RoomListViewModel.SpaceItem) -> Unit,
) {
    DropdownMenuItem(
        text = { Text("Join Room…") },
        leadingIcon = { Icon(Icons.Outlined.ArrowCircleRight, contentDescription = null) },
        onClick = { dismiss(); onJoinRoom() },
    )
    if (selectedSpace != null) {
        if (canInvite) {
            DropdownMenuItem(
                text = { Text("Invite People…") },
                leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                onClick = { dismiss(); onInvitePeople(selectedSpace) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Space Settings…") },
            leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            onClick = { dismiss(); onSpaceSettings(selectedSpace) },
        )
        DropdownMenuItem(
            text = { Text("Refresh Rooms") },
            leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
            onClick = { dismiss(); onRefreshRooms(selectedSpace) },
        )
        DropdownMenuItem(
            text = { Text("Mark All as Read") },
            leadingIcon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
            onClick = { dismiss(); onMarkAllRead() },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Leave Space…", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null,
                     tint = MaterialTheme.colorScheme.error)
            },
            onClick = { dismiss(); onLeaveSpace(selectedSpace) },
        )
    } else {
        DropdownMenuItem(
            text = { Text("Mark All as Read") },
            leadingIcon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
            onClick = { dismiss(); onMarkAllRead() },
        )
    }
}

/** Shared "new …" menu items (iOS `newMenuItems`). */
@Composable
private fun NewMenuItems(
    selectedSpace: RoomListViewModel.SpaceItem?,
    dismiss: () -> Unit,
    onNewChatSheet: (NewChatSheet) -> Unit,
) {
    if (selectedSpace != null) {
        DropdownMenuItem(
            text = { Text("New Room…") },
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            onClick = { dismiss(); onNewChatSheet(NewChatSheet.Room(selectedSpace.id)) },
        )
        DropdownMenuItem(
            text = { Text("New Video Room…") },
            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
            onClick = { dismiss(); onNewChatSheet(NewChatSheet.VideoRoom(selectedSpace.id)) },
        )
    } else {
        DropdownMenuItem(
            text = { Text("New Message…") },
            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            onClick = { dismiss(); onNewChatSheet(NewChatSheet.DirectMessage) },
        )
        DropdownMenuItem(
            text = { Text("New Room…") },
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            onClick = { dismiss(); onNewChatSheet(NewChatSheet.Room(null)) },
        )
        DropdownMenuItem(
            text = { Text("New Video Room…") },
            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null) },
            onClick = { dismiss(); onNewChatSheet(NewChatSheet.VideoRoom(null)) },
        )
    }
}

/**
 * Long-press menu for a room row. The iOS nested "Spaces" submenu becomes a
 * second page of the same dropdown.
 */
@Composable
private fun RoomContextMenu(
    room: RoomSummary,
    expanded: Boolean,
    showsSpaces: Boolean,
    canInvite: Boolean,
    canMove: Boolean,
    spaces: List<RoomListViewModel.SpaceItem>,
    manageableSpaceIds: Set<String>,
    spaceChildIds: Map<String, Set<String>>,
    onShowSpaces: () -> Unit,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onInvite: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleSpace: (String) -> Unit,
    onLeave: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // ...and only into spaces whose child list you can edit.
        val manageableSpaces = spaces.filter { manageableSpaceIds.contains(it.id) }
        if (showsSpaces) {
            manageableSpaces.forEach { space ->
                val isMember = spaceChildIds[space.id]?.contains(room.id) == true
                DropdownMenuItem(
                    text = { Text(space.name) },
                    leadingIcon = {
                        if (isMember) Icon(Icons.Filled.Check, contentDescription = "In space")
                    },
                    onClick = { onDismiss(); onToggleSpace(space.id) },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text("Room Settings…") },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = { onDismiss(); onSettings() },
            )
            if (canInvite) {
                DropdownMenuItem(
                    text = { Text("Invite People…") },
                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                    onClick = { onDismiss(); onInvite() },
                )
            }
            DropdownMenuItem(
                text = { Text("Mark as Read") },
                leadingIcon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
                onClick = { onDismiss(); onMarkRead() },
            )
            HorizontalDivider()
            // Only offer the move at all for rooms you can actually re-parent
            // (needs `m.space.parent` power in the room itself).
            if (!room.isDirect && canMove) {
                if (manageableSpaces.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(if (spaces.isEmpty()) "No Spaces Yet" else "No Spaces You Can Edit")
                        },
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Spaces") },
                        leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                        trailingIcon = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
                        onClick = onShowSpaces,
                    )
                }
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = {
                    Text(if (room.isDirect) "Leave Chat…" else "Leave Room…",
                         color = MaterialTheme.colorScheme.error)
                },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null,
                         tint = MaterialTheme.colorScheme.error)
                },
                onClick = { onDismiss(); onLeave() },
            )
        }
    }
}

/** A room advertised by the selected space but not yet joined. */
@Composable
fun SpaceDirectoryRow(
    child: RoomListViewModel.SpaceChild,
    isJoining: Boolean,
    join: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatarView(name = child.name, isDirect = false, avatarUrl = child.avatarUrl)
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    child.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (child.isVideoRoom) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = "Video room",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Text(
                child.topic ?: "${child.memberCount} members",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isJoining) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                                      color = colors.accent)
        } else {
            OutlinedButton(onClick = join) {
                Text("Join", style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** A pending invite: who it's from, what it is, accept/decline. */
@Composable
fun InviteRow(
    room: RoomSummary,
    /**
     * Accept in flight: the badge becomes a spinner and both buttons disable
     * so the invite can't be double-actioned.
     */
    isJoining: Boolean = false,
    accept: () -> Unit,
    decline: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatarView(name = room.name, isDirect = room.isDirect, avatarUrl = room.avatarUrl)
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.isSpace) {
                    Text(
                        "Space",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .background(colors.bgElevated2, RoundedCornerShape(50))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                room.inviterName?.let { "$it invited you" } ?: "You've been invited",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        InviteBadge(
            icon = Icons.Filled.Close,
            fill = colors.unreadMention,
            contentDescription = "Decline",
            enabled = !isJoining,
            onClick = decline,
        )
        if (isJoining) {
            // Accept-in-flight spinner in the badge's footprint so the row
            // doesn't shift.
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                                          color = colors.accent)
            }
        } else {
            InviteBadge(
                icon = Icons.Filled.Check,
                fill = colors.presenceOnline,
                contentDescription = "Accept",
                enabled = true,
                onClick = accept,
            )
        }
    }
}

/** 24dp visual circle; the hit area extends to 44dp. */
@Composable
private fun InviteBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fill: Color,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(24.dp).background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White,
                 modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun RoomRow(room: RoomSummary, isSelected: Boolean = false, reduceMotion: Boolean = false) {
    val colors = LocalDiscourseColors.current

    // Bright while there's something new, dimmed once read. Selected rows
    // stay bright.
    val isUnread = isSelected || room.hasAnyUnread

    // Ease the title's weight change (regular ⇄ semibold) instead of snapping.
    // FontWeight isn't directly animatable, so tween its integer weight and
    // rebuild the FontWeight from it. Reduce-motion lands on the target weight.
    val targetWeight = if (isUnread) FontWeight.SemiBold.weight else FontWeight.Normal.weight
    val animatedWeight by animateIntAsState(
        targetValue = targetWeight,
        animationSpec = if (reduceMotion) tween(0) else tween(durationMillis = 150),
        label = "roomTitleWeight",
    )
    val titleWeight = FontWeight(animatedWeight)

    // White text on the accent selection fill; semantic primary would be
    // dark-on-accent in Light Mode.
    val titleColor = when {
        isSelected -> colors.textOnAccent
        isUnread -> colors.textPrimary
        else -> colors.textSecondary
    }
    val subtitleColor = when {
        isSelected -> colors.textOnAccent.copy(alpha = 0.8f)
        isUnread -> colors.textSecondary
        else -> colors.textTertiary
    }
    val detailColor = if (isSelected) colors.textOnAccent.copy(alpha = 0.8f)
                      else colors.textTertiary

    // "You: hi" / "Alice: hi". Previews with no sender (invitations) stay bare.
    val previewText = room.lastMessagePreview?.let { preview ->
        when {
            room.lastMessageIsOwn -> "You: $preview"
            room.lastMessageSenderName != null -> "${room.lastMessageSenderName}: $preview"
            else -> preview
        }
    }

    // Unread state is otherwise conveyed only by font weight, which TalkBack
    // can't see.
    val accessibilityUnreadValue = when {
        room.isMentioned -> "${room.unreadMentions} mentions"
        room.badgeCount > 0u -> "${room.badgeCount} unread"
        room.hasAnyUnread -> "Unread"
        else -> ""
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // Name, glyphs, timestamp, and preview read as one summary.
            .semantics(mergeDescendants = true) {
                if (accessibilityUnreadValue.isNotEmpty()) {
                    stateDescription = accessibilityUnreadValue
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            RoomAvatarView(name = room.name, isDirect = room.isDirect, avatarUrl = room.avatarUrl)
            room.dmUserId?.let { userId ->
                PresenceDot(userId = userId, size = 9.dp,
                            modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = titleWeight,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (room.isVideoRoom) {
                        Icon(
                            Icons.Filled.Videocam,
                            contentDescription = if (room.hasActiveCall)
                                "Video room — call in progress" else "Video room",
                            tint = if (room.hasActiveCall) colors.presenceOnline else detailColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (room.isEncrypted) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "End-to-end encrypted",
                            tint = detailColor,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                room.lastActivity?.let { epochMs ->
                    // Recompute only when lastActivity changes, not on every frame
                    // of the weight/badge animations that recompose this row.
                    val timestamp = remember(epochMs) { RowTimestamp.format(epochMs) }
                    Text(
                        timestamp,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFeatureSettings = "tnum"),
                        color = detailColor,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (room.hasActiveCall && room.callParticipantIds.isNotEmpty()) {
                CallParticipantsStrip(room.callParticipantIds)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (previewText != null) {
                    Text(
                        previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // Badge scales+fades in/out; the count itself cross-fades as a
                // numeric transition. Reduce-motion snaps both via zero-duration
                // specs, keeping the show/hide behavior identical.
                AnimatedVisibility(
                    visible = room.hasUnread,
                    enter = if (reduceMotion) fadeIn(tween(0))
                            else scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f)) +
                                fadeIn(tween(150)),
                    exit = if (reduceMotion) fadeOut(tween(0))
                           else scaleOut(tween(150)) + fadeOut(tween(150)),
                ) {
                    // Hold the last non-zero count during the exit animation so
                    // the capsule doesn't flash "0" as it collapses.
                    var lastCount by remember { mutableStateOf(room.badgeCount) }
                    if (room.hasUnread) lastCount = room.badgeCount
                    val mentioned = room.isMentioned
                    AnimatedContent(
                        targetState = lastCount,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                            } else {
                                fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                            }
                        },
                        label = "roomBadgeCount",
                    ) { count ->
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.labelSmall
                                .copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            modifier = Modifier
                                .background(
                                    // Red for a real mention, otherwise the accent capsule.
                                    if (mentioned) colors.unreadMention else colors.accent,
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// Immutable and reused per render; building formatters per row is measurable
// across a long sidebar.
private object RowTimestamp {
    private val todayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val earlierFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun format(epochMs: Long): String {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(epochMs).atZone(zone)
        return if (dateTime.toLocalDate() == LocalDate.now(zone)) {
            todayFormat.format(dateTime)
        } else {
            earlierFormat.format(dateTime)
        }
    }
}

/**
 * Discord-style overlapping avatars of who's currently in a room's call.
 * Real profile pictures via the shared profile source (falls back to colored
 * initials until each fetch lands).
 */
@Composable
private fun CallParticipantsStrip(userIds: List<String>) {
    val colors = LocalDiscourseColors.current
    val profiles = LocalProfileNames.current
    val maxShown = 5
    Row(
        horizontalArrangement = Arrangement.spacedBy((-6).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        userIds.take(maxShown).forEach { userId ->
            RoomAvatarView(
                name = profiles?.displayName(userId) ?: localpart(userId),
                isDirect = true,
                size = 18.dp,
                avatarUrl = profiles?.avatarUrl(userId),
                modifier = Modifier.border(1.5.dp, colors.bgSidebar, CircleShape),
            )
        }
        if (userIds.size > maxShown) {
            Text(
                "+${userIds.size - maxShown}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * System reduce-motion switch, read from the OS animator-duration scale (0 ⇒
 * animations off). Mirrors SpacesRail; the in-app reduceTimelineMotion toggle
 * isn't available here since SidebarView doesn't receive Preferences.
 */
@Composable
private fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private fun localpart(userId: String): String {
    if (!userId.startsWith("@")) return userId
    return userId.drop(1).takeWhile { it != ':' }
}

/**
 * Circular avatar loaded through the session's media loader, falling back to
 * colored initials. Decorative — the adjacent name carries the info, so no
 * content description.
 */
@Composable
fun RoomAvatarView(
    name: String,
    isDirect: Boolean,
    size: Dp = 28.dp,
    avatarUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val loader = LocalMediaLoader.current
    val density = LocalDensity.current
    val pixelSize = remember(size, density) { with(density) { size.roundToPx() } }

    // Synchronous cache hit so recycled rows show the avatar on their first
    // frame instead of flashing initials.
    val image by produceState(
        initialValue = if (avatarUrl != null) loader?.cachedThumbnail(avatarUrl, pixelSize) else null,
        avatarUrl, loader, pixelSize,
    ) {
        if (avatarUrl == null || loader == null) {
            value = null
            return@produceState
        }
        // On a URL change show the new URL's cached thumbnail (or initials)
        // right away rather than the previous avatar.
        value = loader.cachedThumbnail(avatarUrl, pixelSize)
        loader.avatar(avatarUrl, pixelSize)?.let { value = it }
    }

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            // Decorative: the adjacent name carries the info. Without this the
            // initials fallback is read by TalkBack as a stray fragment
            // ("A B" before every room name).
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(Modifier.matchParentSize().background(initialsGradient(name)))
            Text(
                initials(name),
                fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() },
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

private fun initials(name: String): String {
    val cleaned = name.trim { it in "#@!+ " }
    val letters = cleaned.split(' ').filter { it.isNotEmpty() }.take(2)
        .mapNotNull { it.firstOrNull() }
    return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase()
}

/**
 * Deterministic per-name color, same hash and palette as iOS/web (the palette
 * is the iOS system-color set — a design constant, not a theme token).
 */
private fun initialsGradient(name: String): Brush {
    val palette = listOf(
        Color(0xFF007AFF), // blue
        Color(0xFF5856D6), // indigo
        Color(0xFFAF52DE), // purple
        Color(0xFFFF2D55), // pink
        Color(0xFFFF3B30), // red
        Color(0xFFFF9500), // orange
        Color(0xFF30B0C7), // teal
        Color(0xFF34C759), // green
    )
    // 64-bit wrapping arithmetic over code points, matching Swift's Int
    // `&* 31 &+ scalar` over unicodeScalars.
    var hash = 0L
    var i = 0
    while (i < name.length) {
        val codePoint = name.codePointAt(i)
        hash = hash * 31 + codePoint
        i += Character.charCount(codePoint)
    }
    val color = palette[(abs(hash) % palette.size).toInt()]
    return Brush.verticalGradient(
        listOf(lerp(color, Color.White, 0.12f), lerp(color, Color.Black, 0.08f)),
    )
}

/** A room/space banner image; a quiet placeholder until the fetch lands. */
@Composable
fun BannerImageView(mxcUrl: String, modifier: Modifier = Modifier) {
    val colors = LocalDiscourseColors.current
    val loader = LocalMediaLoader.current
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, mxcUrl, loader,
    ) {
        value = loader?.avatar(mxcUrl, pixelSize = 700)
    }
    Box(modifier.background(colors.bgElevated2)) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}
