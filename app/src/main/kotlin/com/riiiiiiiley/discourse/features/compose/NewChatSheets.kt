package com.riiiiiiiley.discourse.features.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import com.riiiiiiiley.discourse.ui.components.RoomAvatarView
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class NewChatSheet {
    data object DirectMessage : NewChatSheet()

    /** Optionally filed straight into a space. */
    data class Room(val spaceId: String?) : NewChatSheet()

    /** Video room, optionally filed into a space. */
    data class VideoRoom(val spaceId: String?) : NewChatSheet()
    data object Space : NewChatSheet()
    data object Join : NewChatSheet()

    val id: String
        get() = when (this) {
            is DirectMessage -> "dm"
            is Room -> "room-${spaceId ?: "home"}"
            is VideoRoom -> "video-${spaceId ?: "home"}"
            is Space -> "space"
            is Join -> "join"
        }
}

// MARK: - Invite to room / space

/** Search the directory and invite users into a room or space. */
@Composable
fun InviteSheet(
    scope: SessionScope,
    /** iOS reads `scope.roomList`; passed explicitly until RoomListViewModel attaches to SessionScope. */
    roomList: RoomListViewModel,
    roomId: String,
    roomName: String,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<MatrixService.UserHit>()) }
    var isSearching by remember { mutableStateOf(false) }
    var invited by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(setOf<String>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val directEntryUserId = directEntryUserId(query)

    /**
     * Debounced directory search; `immediate` skips the debounce (keyboard
     * Search key), running the query right away.
     */
    fun search(newValue: String, immediate: Boolean = false) {
        searchJob?.cancel()
        val term = newValue.trim()
        if (term.length < 2) {
            results = emptyList()
            isSearching = false
            return
        }
        searchJob = coroutineScope.launch {
            if (!immediate) delay(300)
            isSearching = true
            // searchUsers doesn't observe cancellation; guard before publishing
            // so a superseded query's stale hits don't overwrite newer results.
            val hits = scope.service.searchUsers(term)
            if (!isActive) return@launch
            results = hits
            isSearching = false
        }
    }

    fun invite(userId: String) {
        val room = roomList.ffiRoom(roomId) ?: return
        busy = busy + userId
        errorMessage = null
        coroutineScope.launch {
            try {
                room.inviteUserById(userId)
                invited = invited + userId
            } catch (error: Exception) {
                errorMessage = "Couldn't invite $userId: ${error.message ?: error}"
            } finally {
                busy = busy - userId
            }
        }
    }

    SheetChrome(
        title = "Invite to $roomName",
        // Once invites have gone out, Cancel gives way to a bold Done.
        isCommitted = invited.isNotEmpty(),
        onDismiss = onDismiss,
    ) {
        FormSection(header = "To") {
            FormTextField(
                value = query,
                placeholder = "Name or @user:server",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                // Search key skips the debounce.
                keyboardActions = KeyboardActions(onSearch = { search(query, immediate = true) }),
                onValueChange = {
                    query = it
                    search(it)
                },
            )
        }

        if (isSearching || results.isNotEmpty() || directEntryUserId != null) {
            FormSection(header = "Results") {
                if (isSearching && results.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                results.forEachIndexed { index, user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RoomAvatarView(
                            name = user.name, isDirect = true, size = 40.dp,
                            avatarUrl = user.avatarUrl,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                user.name, fontSize = 16.sp, color = colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                user.id, fontSize = 13.sp, color = colors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        InviteButton(
                            userId = user.id,
                            invited = invited,
                            busy = busy,
                            invite = { invite(it) },
                        )
                    }
                    if (index < results.size - 1 || directEntryUserId != null) FormDivider()
                }
                // Full user IDs directory search may miss.
                if (directEntryUserId != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            query, fontSize = 16.sp, color = colors.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        InviteButton(
                            userId = directEntryUserId,
                            invited = invited,
                            busy = busy,
                            invite = { invite(it) },
                        )
                    }
                }
            }
        }

        errorMessage?.let { ErrorSection(it) }
    }
}

@Composable
private fun InviteButton(
    userId: String,
    invited: Set<String>,
    busy: Set<String>,
    invite: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    if (invited.contains(userId)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Invited $userId",
                tint = colors.presenceOnline,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Invited", fontSize = 15.sp, color = colors.presenceOnline)
        }
    } else {
        TextButton(
            onClick = { invite(userId) },
            enabled = !busy.contains(userId),
        ) {
            Text(
                "Invite",
                color = if (busy.contains(userId)) colors.textTertiary else colors.accent,
                fontSize = 15.sp,
            )
        }
    }
}

// MARK: - New DM

@Composable
fun NewDirectMessageSheet(
    scope: SessionScope,
    open: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<MatrixService.UserHit>()) }
    var isSearching by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val directEntryUserId = directEntryUserId(query)

    /**
     * Debounced directory search; `immediate` skips the debounce (keyboard
     * Search key), running the query right away.
     */
    fun search(newValue: String, immediate: Boolean = false) {
        searchJob?.cancel()
        val term = newValue.trim()
        if (term.length < 2) {
            results = emptyList()
            isSearching = false
            return
        }
        searchJob = coroutineScope.launch {
            if (!immediate) delay(300)
            isSearching = true
            // searchUsers doesn't observe cancellation; guard before publishing
            // so a superseded query's stale hits don't overwrite newer results.
            val hits = scope.service.searchUsers(term)
            if (!isActive) return@launch
            results = hits
            isSearching = false
        }
    }

    fun startDm(userId: String) {
        if (isCreating) return
        isCreating = true
        coroutineScope.launch {
            try {
                val roomId = scope.service.startDm(userId)
                open(roomId)
                onDismiss()
            } catch (error: Exception) {
                errorMessage = "Couldn't start the conversation: ${error.message ?: error}"
                isCreating = false
            }
        }
    }

    SheetChrome(title = "New Message", onDismiss = onDismiss) {
        FormSection(header = "To") {
            FormTextField(
                value = query,
                placeholder = "Name or @user:server",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                // Search key skips the debounce.
                keyboardActions = KeyboardActions(onSearch = { search(query, immediate = true) }),
                onValueChange = {
                    query = it
                    search(it)
                },
            )
        }

        if (isSearching || results.isNotEmpty() || directEntryUserId != null) {
            FormSection(header = "Results") {
                if (isSearching && results.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                results.forEachIndexed { index, user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCreating) { startDm(user.id) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RoomAvatarView(
                            name = user.name, isDirect = true, size = 40.dp,
                            avatarUrl = user.avatarUrl,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                user.name, fontSize = 16.sp, color = colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                user.id, fontSize = 13.sp, color = colors.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (index < results.size - 1 || directEntryUserId != null) FormDivider()
                }
                // Full user IDs directory search may miss.
                if (directEntryUserId != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isCreating) { startDm(directEntryUserId) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Text(
                            "Message $query",
                            fontSize = 16.sp,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (isCreating) {
            FormSection {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Text("Starting chat…", fontSize = 16.sp, color = colors.textSecondary)
                }
            }
        }

        errorMessage?.let { ErrorSection(it) }
    }
}

// MARK: - New room / space

private enum class NewRoomVisibilityOption {
    SPACE_MEMBERS, PRIVATE_ROOM, PUBLIC_ROOM,
}

@Composable
fun NewRoomSheet(
    scope: SessionScope,
    /** iOS reads `scope.roomList`; passed explicitly until RoomListViewModel attaches to SessionScope. */
    roomList: RoomListViewModel,
    isSpace: Boolean,
    /** When set, the created room is filed into this space. */
    destinationSpaceId: String? = null,
    /** A sub-space acting as a category; changes labels only. */
    isSection: Boolean = false,
    isVideoRoom: Boolean = false,
    open: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    // Inside a space, default to "visible to space members".
    var visibility by remember {
        mutableStateOf(
            if (destinationSpaceId != null) NewRoomVisibilityOption.SPACE_MEMBERS
            else NewRoomVisibilityOption.PRIVATE_ROOM,
        )
    }
    var isEncrypted by remember { mutableStateOf(true) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun create() {
        isCreating = true
        coroutineScope.launch {
            try {
                val serviceVisibility = when (visibility) {
                    NewRoomVisibilityOption.SPACE_MEMBERS ->
                        MatrixService.NewRoomVisibility.SpaceMembers(destinationSpaceId ?: "")
                    NewRoomVisibilityOption.PRIVATE_ROOM ->
                        MatrixService.NewRoomVisibility.PrivateRoom
                    NewRoomVisibilityOption.PUBLIC_ROOM ->
                        MatrixService.NewRoomVisibility.PublicRoom
                }
                val roomId: String
                if (isVideoRoom) {
                    roomId = scope.service.createVideoRoom(
                        name = name.trim(),
                        topic = topic,
                        visibility = serviceVisibility,
                    )
                    // Flag it locally now; space listings only refresh later.
                    roomList.noteVideoRoom(roomId)
                } else {
                    roomId = scope.service.createRoom(
                        name = name.trim(),
                        topic = topic,
                        visibility = serviceVisibility,
                        isEncrypted = !isSpace &&
                            visibility != NewRoomVisibilityOption.PUBLIC_ROOM && isEncrypted,
                        isSpace = isSpace,
                    )
                }
                if (destinationSpaceId != null) {
                    roomList.fileRoom(roomId, destinationSpaceId)
                }
                // A new space has no timeline of its own — opening its "room"
                // shows a confusing empty chat. Select the space so the sidebar
                // shows its (empty) room list instead; real rooms still open.
                if (isSpace) {
                    roomList.selectSpace(roomId)
                } else {
                    open(roomId)
                }
                onDismiss()
            } catch (error: Exception) {
                errorMessage = "Couldn't create: ${error.message ?: error}"
                isCreating = false
            }
        }
    }

    SheetChrome(
        title = if (isSection) "New Section"
                else if (isSpace) "New Space"
                else if (isVideoRoom) "New Video Room"
                else "New Room",
        primaryTitle = if (isCreating) "Creating…" else "Create",
        primaryDisabled = name.trim().isEmpty() || isCreating,
        primaryAction = { create() },
        onDismiss = onDismiss,
    ) {
        FormSection {
            FormTextField(value = name, placeholder = "Name", onValueChange = { name = it })
            FormDivider()
            FormTextField(
                value = topic, placeholder = "Topic (optional)",
                onValueChange = { topic = it },
            )
        }

        FormSection(header = "Visibility") {
            if (destinationSpaceId != null) {
                VisibilityRow(
                    icon = Icons.Outlined.Group,
                    label = "Visible to space members",
                    selected = visibility == NewRoomVisibilityOption.SPACE_MEMBERS,
                ) { visibility = NewRoomVisibilityOption.SPACE_MEMBERS }
                FormDivider()
            }
            VisibilityRow(
                icon = Icons.Outlined.Lock,
                label = if (isSpace) "Private (invite only)" else "Private room (invite only)",
                selected = visibility == NewRoomVisibilityOption.PRIVATE_ROOM,
            ) { visibility = NewRoomVisibilityOption.PRIVATE_ROOM }
            FormDivider()
            VisibilityRow(
                icon = Icons.Outlined.Public,
                label = if (isSpace) "Public" else "Public room",
                selected = visibility == NewRoomVisibilityOption.PUBLIC_ROOM,
            ) { visibility = NewRoomVisibilityOption.PUBLIC_ROOM }
        }

        if (!isSpace && !isVideoRoom) {
            FormSection(
                footer = "Encryption can't be turned off later. Public rooms can't be encrypted.",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "End-to-end encrypted",
                        fontSize = 16.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    // Public rooms can't be encrypted, but `isEncrypted` intent is
                    // preserved so Public→Private restores it. Shown off + disabled
                    // while public; effective encryption is derived at create time.
                    Switch(
                        checked = visibility != NewRoomVisibilityOption.PUBLIC_ROOM && isEncrypted,
                        onCheckedChange = { isEncrypted = it },
                        enabled = visibility != NewRoomVisibilityOption.PUBLIC_ROOM,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = colors.accent,
                            checkedThumbColor = colors.textOnAccent,
                        ),
                    )
                }
            }
        }

        errorMessage?.let { ErrorSection(it) }
    }
}

@Composable
private fun VisibilityRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            label,
            fontSize = 16.sp,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// MARK: - Join by address

@Composable
fun JoinRoomSheet(
    scope: SessionScope,
    open: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun join() {
        if (isJoining) return
        isJoining = true
        coroutineScope.launch {
            try {
                val roomId = scope.service.joinRoom(address.trim())
                open(roomId)
                onDismiss()
            } catch (error: Exception) {
                errorMessage = "Couldn't join: ${error.message ?: error}"
                isJoining = false
            }
        }
    }

    SheetChrome(
        title = "Join Room",
        primaryTitle = if (isJoining) "Joining…" else "Join",
        primaryDisabled = address.trim().isEmpty() || isJoining,
        primaryAction = { join() },
        onDismiss = onDismiss,
    ) {
        FormSection(
            footer = "Enter a room address like #room:server, or an internal room ID like !roomid:server.",
        ) {
            FormTextField(
                value = address,
                placeholder = "#room:server",
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { join() }),
                onValueChange = { address = it },
            )
        }

        errorMessage?.let { ErrorSection(it) }
    }
}

// MARK: - Shared helpers

/**
 * A typed user id, normalized: `user:server` is accepted and the leading
 * `@` added, so you don't have to type it. null if it doesn't look like one.
 */
private fun directEntryUserId(query: String): String? {
    val raw = query.trim()
    if (!raw.contains(":") || raw.contains(" ")) return null
    return if (raw.startsWith("@")) raw else "@$raw"
}

// MARK: - Shared chrome

/**
 * Shared sheet scaffolding (the iOS NavigationStack + grouped Form): a nav bar
 * with Cancel and the optional primary action, above scrolling form sections.
 * Once committed, Cancel is replaced by a bold "Done".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetChrome(
    title: String,
    primaryTitle: String? = null,
    primaryDisabled: Boolean = false,
    primaryAction: (() -> Unit)? = null,
    isCommitted: Boolean = false,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp),
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            },
            actions = {
                // The X handles dismiss; keep a trailing button only for a distinct
                // confirm that commits (Create/Join). The `isCommitted` Done merely
                // dismissed, so the Close icon replaces it.
                if (!isCommitted && primaryTitle != null && primaryAction != null) {
                    TextButton(
                        onClick = primaryAction,
                        enabled = !primaryDisabled,
                    ) {
                        Text(
                            primaryTitle,
                            color = if (primaryDisabled) colors.textTertiary else colors.accent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = colors.bgApp,
                titleContentColor = colors.textPrimary,
                navigationIconContentColor = colors.textPrimary,
                actionIconContentColor = colors.textPrimary,
            ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content()
        }
    }
}

/** One grouped-Form section: uppercase header, rounded card, caption footer. */
@Composable
private fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column {
        if (header != null) {
            Text(
                text = header.uppercase(),
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated),
        ) {
            content()
        }
        if (footer != null) {
            Text(
                text = footer,
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun FormDivider() {
    HorizontalDivider(
        color = LocalDiscourseColors.current.separator,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun ErrorSection(message: String) {
    val colors = LocalDiscourseColors.current
    FormSection {
        Text(
            text = message,
            fontSize = 15.sp,
            color = colors.unreadMention,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun FormTextField(
    value: String,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    onValueChange: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        decorationBox = { innerField ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 16.sp, color = colors.textTertiary, maxLines = 1)
                }
                innerField()
            }
        },
    )
}
