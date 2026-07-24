package com.riiiiiiiley.discourse.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mood
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.MediaProcessing
import com.riiiiiiiley.discourse.core.PowerLevelTag
import com.riiiiiiiley.discourse.core.PowerLevelTags
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import com.riiiiiiiley.discourse.features.timeline.EmojiPickerView
import com.riiiiiiiley.discourse.features.timeline.EmoteAssetLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteImageLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteImageView
import com.riiiiiiiley.discourse.features.timeline.TimelineViewModel
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.PollItem
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.AllowRule
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RoomPowerLevelsValues
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate
import uniffi.matrix_sdk.RoomPowerLevelChanges
import uniffi.matrix_sdk_base.EncryptionState
import java.text.DateFormat
import java.util.Date

data class SettingsTarget(
    val roomId: String,
    val isSpace: Boolean,
) {
    val id: String get() = roomId
}

enum class SettingsTab {
    GENERAL, SECURITY, ROLES, EMOTES, NOTIFICATIONS, POLLS, ADVANCED;

    fun title(isSpace: Boolean): String = when (this) {
        GENERAL -> "General"
        SECURITY -> if (isSpace) "Visibility" else "Security & Privacy"
        ROLES -> "Roles & Permissions"
        EMOTES -> "Emoji & Stickers"
        NOTIFICATIONS -> "Notifications"
        POLLS -> "Poll History"
        ADVANCED -> "Advanced"
    }

    fun icon(isSpace: Boolean): ImageVector = when (this) {
        GENERAL -> Icons.Outlined.Settings
        SECURITY -> if (isSpace) Icons.Outlined.Visibility else Icons.Outlined.Lock
        ROLES -> Icons.Outlined.Shield
        EMOTES -> Icons.Outlined.Mood
        NOTIFICATIONS -> Icons.Outlined.Notifications
        POLLS -> Icons.Outlined.BarChart
        ADVANCED -> Icons.Outlined.Build
    }

    companion object {
        /** Spaces get a subset of the tabs plus the shared emote pack. */
        fun cases(isSpace: Boolean): List<SettingsTab> =
            if (isSpace) listOf(GENERAL, SECURITY, ROLES, EMOTES, ADVANCED)
            else entries.toList()
    }
}

/**
 * Room/space settings, matching the iOS phone layout: a root grouped form
 * (avatar, name/topic, addresses, tab links, leave) that pushes per-tab detail
 * screens. Back navigation pops the detail; Done dismisses the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSettingsSheet(
    scope: SessionScope,
    roomList: RoomListViewModel,
    target: SettingsTarget,
    /**
     * `scope.timeline(forRoomId:)` on iOS — the session's timeline LRU cache.
     * Wire once that cache lands on SessionScope; until then Poll History
     * shows the empty state.
     */
    timelineForRoom: ((String) -> TimelineViewModel?)? = null,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val model = remember { RoomSettingsModel(scope, roomList, target, timelineForRoom) }
    val state by model.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<SettingsTab?>(null) }

    DisposableEffect(Unit) { onDispose { model.dispose() } }
    LaunchedEffect(Unit) { model.load() }
    BackHandler(enabled = detail != null) { detail = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp),
    ) {
        // Title bar: back arrow navigates within the sheet on detail screens;
        // the X dismisses the whole sheet on the root.
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = detail?.title(target.isSpace)
                        ?: if (target.isSpace) "Space Settings" else "Room Settings",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                if (detail != null) {
                    IconButton(onClick = { detail = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
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

        if (!state.isLoaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
        } else {
            when (detail) {
                null -> RootSettingsForm(model, state, onDismiss = onDismiss,
                                         openDetail = { detail = it })
                SettingsTab.GENERAL -> Unit // never pushed; general lives on the root
                SettingsTab.SECURITY -> SecurityForm(model, state)
                SettingsTab.ROLES -> RolesForm(model, state)
                SettingsTab.EMOTES -> EmotePackEditor(model)
                SettingsTab.NOTIFICATIONS -> NotificationsForm(model, state)
                SettingsTab.POLLS -> PollHistoryForm(model)
                SettingsTab.ADVANCED -> AdvancedForm(model, state)
            }
        }
    }
}

// MARK: - Model

class RoomSettingsModel(
    val scope: SessionScope,
    val roomList: RoomListViewModel,
    val target: SettingsTarget,
    val timelineForRoom: ((String) -> TimelineViewModel?)? = null,
) {
    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /**
         * Setting writes run detached, like the iOS unstructured Tasks that
         * survive dismissal: tapping a toggle/radio and immediately hitting
         * Done/back must not silently drop the write (dispose() cancels only
         * [modelScope]).
         */
        val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    enum class JoinRuleChoice { INVITE, SPACE_MEMBERS, ANYONE }
    enum class HistoryChoice { INVITED, JOINED, SHARED, WORLD_READABLE }
    enum class NotificationChoice { DEFAULT, ALL, MENTIONS, MUTE }

    data class PrivilegedUser(val userId: String, val level: Long)

    data class State(
        val name: String = "",
        val topic: String = "",
        val avatarUrl: String? = null,
        /** Space banner (custom state event); null for rooms and unset spaces. */
        val bannerUrl: String? = null,
        val canEditBanner: Boolean = false,   // page.codeberg.everypizza.room.banner
        val canonicalAlias: String? = null,
        val newAlias: String = "",
        val isInDirectory: Boolean = false,
        val isEncrypted: Boolean = false,
        val joinRule: JoinRuleChoice = JoinRuleChoice.INVITE,
        val historyVisibility: HistoryChoice = HistoryChoice.SHARED,
        val notificationMode: NotificationChoice = NotificationChoice.DEFAULT,
        val memberCount: ULong = 0u,
        val roomVersion: String = "?",
        val privilegedUsers: List<PrivilegedUser> = emptyList(),
        val permissionValues: RoomPowerLevelsValues? = null,
        /** Named roles (in.cinny.room.power_level_tags), power level → tag. */
        val powerLevelTags: Map<Int, PowerLevelTag> = emptyMap(),
        val errorMessage: String? = null,
        val infoMessage: String? = null,

        // Resolved from the room's power levels in load(); stay false until then
        // so controls never flash editable (the sheet shows a spinner meanwhile).
        // Notification mode is a personal push rule and is intentionally not gated.
        val canEditBasics: Boolean = false,       // m.room.name / m.room.topic / m.room.avatar
        val canEnableEncryption: Boolean = false, // m.room.encryption
        val canEditAccess: Boolean = false,       // m.room.join_rules / m.room.history_visibility
        val canEditAddresses: Boolean = false,    // m.room.canonical_alias (+ directory listing)
        val canEditRoles: Boolean = false,        // m.room.power_levels

        val isLoaded: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Joined spaces containing this room — restricted-rule targets. */
    val parentSpaceIds: List<String>
        get() = roomList.spaceChildIds.value.mapNotNull { (spaceId, children) ->
            if (target.roomId in children) spaceId else null
        }

    val room: Room? get() = roomList.ffiRoom(withId = target.roomId)

    // Field edits from the form (iOS two-way bindings).
    fun updateName(value: String) = _state.update { it.copy(name = value) }
    fun updateTopic(value: String) = _state.update { it.copy(topic = value) }
    fun updateNewAlias(value: String) = _state.update { it.copy(newAlias = value) }
    fun setRoleTag(level: Int, tag: PowerLevelTag) =
        _state.update { it.copy(powerLevelTags = it.powerLevelTags + (level to tag)) }

    fun dispose() = modelScope.cancel()

    suspend fun load() {
        val room = room
        val info = room?.let { runCatching { it.roomInfo() }.getOrNull() }
        if (room == null || info == null) {
            _state.update { it.copy(errorMessage = "Couldn't load room details.", isLoaded = true) }
            return
        }
        var s = _state.value.copy(
            name = info.displayName ?: info.rawName ?: "",
            topic = info.topic ?: "",
            avatarUrl = info.avatarUrl,
            canonicalAlias = info.canonicalAlias,
            isEncrypted = info.encryptionState == EncryptionState.ENCRYPTED,
            memberCount = info.joinedMembersCount,
            roomVersion = info.roomVersion ?: "?",
            joinRule = when (info.joinRule) {
                is JoinRule.Public -> JoinRuleChoice.ANYONE
                is JoinRule.Restricted, is JoinRule.KnockRestricted -> JoinRuleChoice.SPACE_MEMBERS
                else -> JoinRuleChoice.INVITE
            },
            historyVisibility = when (info.historyVisibility) {
                is RoomHistoryVisibility.Invited -> HistoryChoice.INVITED
                is RoomHistoryVisibility.Joined -> HistoryChoice.JOINED
                is RoomHistoryVisibility.WorldReadable -> HistoryChoice.WORLD_READABLE
                else -> HistoryChoice.SHARED
            },
        )

        runCatching { room.getRoomVisibility() }.getOrNull()?.let { visibility ->
            s = s.copy(isInDirectory = visibility is RoomVisibility.Public)
        }
        val settings = scope.service.client.getNotificationSettings()
        val mode = runCatching {
            settings.getUserDefinedRoomNotificationMode(target.roomId)
        }.getOrNull()
        s = s.copy(notificationMode = when (mode) {
            RoomNotificationMode.ALL_MESSAGES -> NotificationChoice.ALL
            RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> NotificationChoice.MENTIONS
            RoomNotificationMode.MUTE -> NotificationChoice.MUTE
            null -> NotificationChoice.DEFAULT
        })

        runCatching { room.getPowerLevels() }.getOrNull()?.let { levels ->
            s = s.copy(
                permissionValues = levels.values(),
                privilegedUsers = levels.userPowerLevels()
                    .filterValues { it != 0L }
                    .map { PrivilegedUser(userId = it.key, level = it.value) }
                    .sortedWith(compareByDescending<PrivilegedUser> { it.level }
                        .thenBy { it.userId }),
                // Require every state event a grouped control covers, so an editable
                // control never fails on save with a partial grant.
                canEditBasics = levels.canOwnUserSendState(StateEventType.RoomName)
                    && levels.canOwnUserSendState(StateEventType.RoomTopic)
                    && levels.canOwnUserSendState(StateEventType.RoomAvatar),
                canEnableEncryption = levels.canOwnUserSendState(StateEventType.RoomEncryption),
                canEditAccess = levels.canOwnUserSendState(StateEventType.RoomJoinRules)
                    && levels.canOwnUserSendState(StateEventType.RoomHistoryVisibility),
                canEditAddresses = levels.canOwnUserSendState(StateEventType.RoomCanonicalAlias),
                canEditRoles = levels.canOwnUserSendState(StateEventType.RoomPowerLevels),
                canEditBanner = target.isSpace && levels.canOwnUserSendState(
                    StateEventType.Custom(value = SessionScope.spaceBannerEventType)),
            )
        }

        if (target.isSpace) {
            s = s.copy(bannerUrl = roomList.spaceBannerUrl(forSpace = target.roomId))
        }

        scope.service.stateEventContent(
            roomId = target.roomId, type = PowerLevelTags.eventType)?.let { content ->
            s = s.copy(powerLevelTags = PowerLevelTags.parse(content))
        }

        _state.value = s.copy(isLoaded = true)
    }

    fun roleTag(forLevel: Int): PowerLevelTag =
        _state.value.powerLevelTags[forLevel] ?: PowerLevelTags.defaultTag(forLevel)

    /** Persists the named-role labels (in.cinny.room.power_level_tags). */
    fun savePowerLevelTags() {
        // Drop tags equal to their default so the event only carries edits.
        val tags = _state.value.powerLevelTags
            .filter { it.value != PowerLevelTags.defaultTag(it.key) }
        run {
            val room = room ?: return@run
            room.sendStateEventRaw(
                PowerLevelTags.eventType, "", PowerLevelTags.content(tags).toString())
        }
    }

    // MARK: Actions (each surfaces errors and refreshes)

    private fun run(operation: suspend () -> Unit) {
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
        writeScope.launch {
            try {
                operation()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: e.toString()) }
            }
        }
    }

    fun saveNameAndTopic() {
        val room = room ?: return
        val newName = _state.value.name.trim()
        val newTopic = _state.value.topic.trim()
        run {
            if (newName != (room.displayName() ?: "")) room.setName(newName)
            if (newTopic != (room.topic() ?: "")) room.setTopic(newTopic)
        }
    }

    fun setAvatar(data: ByteArray) {
        val room = room ?: return
        run {
            val attrs = MediaProcessing.imageAttributes(data)
                ?: throw SettingsException("That image couldn't be read.")
            room.uploadAvatar(attrs.mimetype, data, null)
        }
    }

    fun removeAvatar() {
        val room = room ?: return
        run { room.removeAvatar() }
    }

    fun setBanner(data: ByteArray) {
        if (!target.isSpace) return
        run {
            val mime = MediaProcessing.imageAttributes(data)?.mimetype ?: "image/png"
            scope.setSpaceBanner(spaceId = target.roomId, data = data, mimeType = mime)
                ?: throw SettingsException("You don't have permission to change this banner.")
        }
    }

    fun removeBanner() {
        if (!target.isSpace) return
        run {
            if (!scope.removeSpaceBanner(spaceId = target.roomId)) {
                throw SettingsException("You don't have permission to change this banner.")
            }
        }
    }

    fun setMainAddress() {
        val room = room ?: return
        var alias = _state.value.newAlias.trim()
        if (alias.isEmpty()) return
        if (!alias.startsWith("#")) alias = "#$alias"
        if (!alias.contains(":")) {
            val server = scope.userId.substringAfterLast(':', "")
            alias += ":$server"
        }
        val finalAlias = alias
        run {
            // The server rejects a canonical alias that doesn't already map to the
            // room, so publish it first. A false result just means it already exists;
            // a truly conflicting alias fails at updateCanonicalAlias below.
            room.publishRoomAliasInRoomDirectory(finalAlias)
            room.updateCanonicalAlias(finalAlias, room.alternativeAliases())
            _state.update {
                it.copy(infoMessage = "Main address set to $finalAlias", newAlias = "")
            }
        }
    }

    fun setDirectoryVisibility(visible: Boolean) {
        val room = room ?: return
        run {
            room.updateRoomVisibility(
                if (visible) RoomVisibility.Public else RoomVisibility.Private)
            if (visible) {
                _state.value.canonicalAlias?.let { alias ->
                    runCatching { room.publishRoomAliasInRoomDirectory(alias) }
                }
            }
        }
    }

    fun enableEncryption() {
        val room = room ?: return
        run { room.enableEncryption() }
    }

    fun setJoinRule(choice: JoinRuleChoice) {
        val room = room ?: return
        val parents = parentSpaceIds
        run {
            val rule: JoinRule = when (choice) {
                JoinRuleChoice.ANYONE -> JoinRule.Public
                JoinRuleChoice.INVITE -> JoinRule.Invite
                JoinRuleChoice.SPACE_MEMBERS ->
                    JoinRule.Restricted(rules = parents.map { AllowRule.RoomMembership(it) })
            }
            room.updateJoinRules(rule)
        }
    }

    fun setHistoryVisibility(choice: HistoryChoice) {
        val room = room ?: return
        run {
            val visibility: RoomHistoryVisibility = when (choice) {
                HistoryChoice.INVITED -> RoomHistoryVisibility.Invited
                HistoryChoice.JOINED -> RoomHistoryVisibility.Joined
                HistoryChoice.SHARED -> RoomHistoryVisibility.Shared
                HistoryChoice.WORLD_READABLE -> RoomHistoryVisibility.WorldReadable
            }
            room.updateHistoryVisibility(visibility)
        }
    }

    fun setNotificationMode(choice: NotificationChoice) {
        run {
            val settings = scope.service.client.getNotificationSettings()
            when (choice) {
                NotificationChoice.DEFAULT ->
                    settings.restoreDefaultRoomNotificationMode(target.roomId)
                NotificationChoice.ALL ->
                    settings.setRoomNotificationMode(target.roomId, RoomNotificationMode.ALL_MESSAGES)
                NotificationChoice.MENTIONS ->
                    settings.setRoomNotificationMode(
                        target.roomId, RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
                NotificationChoice.MUTE ->
                    settings.setRoomNotificationMode(target.roomId, RoomNotificationMode.MUTE)
            }
        }
    }

    fun setUserLevel(userId: String, level: Long) {
        val room = room ?: return
        run {
            room.updatePowerLevelsForUsers(listOf(
                UserPowerLevelUpdate(userId, level)
            ))
        }
    }

    fun applyPermissions(changes: RoomPowerLevelChanges) {
        val room = room ?: return
        run { room.applyPowerLevelChanges(changes) }
    }

    /**
     * Returns true on success. Not routed through run{}: its reload-on-success
     * would fail once we've left the room.
     */
    suspend fun leaveRoom(): Boolean {
        val room = room ?: return false
        _state.update { it.copy(errorMessage = null, infoMessage = null) }
        return try {
            room.leave()
            if (target.isSpace) {
                roomList.selectSpace(null)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = e.message ?: e.toString()) }
            false
        }
    }
}

private class SettingsException(message: String) : Exception(message)

// MARK: - Root form

@Composable
private fun RootSettingsForm(
    model: RoomSettingsModel,
    state: RoomSettingsModel.State,
    onDismiss: () -> Unit,
    openDetail: (SettingsTab) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()
    val isSpace = model.target.isSpace
    var confirmingLeave by remember { mutableStateOf(false) }

    val avatarPicker = rememberImagePicker { data, _ -> model.setAvatar(data) }

    FormScreen {
        // Avatar
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRoomAvatar(name = state.name, size = 72.dp,
                               avatarUrl = state.avatarUrl, client = model.scope.service.client)
            if (state.canEditBasics) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    TextButton(onClick = { avatarPicker() }) {
                        Text("Choose Photo", color = colors.accent, fontSize = 15.sp)
                    }
                    if (state.avatarUrl != null) {
                        TextButton(onClick = { model.removeAvatar() }) {
                            Text("Remove Photo", color = colors.unreadMention, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        FormSection(
            footer = if (state.canEditBasics)
                "Name and topic changes apply when you save. All other settings apply immediately."
            else null,
        ) {
            if (state.canEditBasics) {
                FormTextField(
                    value = state.name,
                    onValueChange = model::updateName,
                    placeholder = if (isSpace) "Space name" else "Room name",
                )
                FormRowDivider()
                FormTextField(
                    value = state.topic,
                    onValueChange = model::updateTopic,
                    placeholder = if (isSpace) "Description" else "Room topic",
                    singleLine = false,
                )
                FormRowDivider()
                FormButtonRow(
                    title = "Save Changes",
                    enabled = state.name.trim().isNotEmpty(),
                    onClick = { model.saveNameAndTopic() },
                )
            } else {
                FormLabeledRow(if (isSpace) "Space name" else "Room name") {
                    Text(state.name, color = colors.textSecondary, fontSize = 15.sp,
                         maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                if (state.topic.isNotEmpty()) {
                    FormRowDivider()
                    FormLabeledRow(if (isSpace) "Description" else "Room topic") {
                        Text(state.topic, color = colors.textSecondary, fontSize = 15.sp)
                    }
                }
            }
        }

        if (isSpace) {
            SpaceBannerSection(model, state)
        } else {
            AddressSection(model, state)
        }

        FormSection {
            val tabs = SettingsTab.cases(isSpace).filter { it != SettingsTab.GENERAL }
            tabs.forEachIndexed { index, tab ->
                if (index > 0) FormRowDivider()
                FormNavigationRow(
                    title = tab.title(isSpace),
                    icon = tab.icon(isSpace),
                    onClick = { openDetail(tab) },
                )
            }
        }

        StatusSection(state)

        FormSection {
            FormButtonRow(
                title = if (isSpace) "Leave Space" else "Leave Room",
                destructive = true,
                centered = true,
                onClick = { confirmingLeave = true },
            )
        }
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            title = { Text(if (isSpace) "Leave this space?" else "Leave this room?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLeave = false
                    coroutineScope.launch { if (model.leaveRoom()) onDismiss() }
                }) {
                    Text(if (isSpace) "Leave Space" else "Leave Room",
                         color = colors.unreadMention)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) { Text("Cancel") }
            },
        )
    }
}

/** Space banner controls on the root form (iOS keeps them in the General tab). */
@Composable
private fun SpaceBannerSection(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val bannerPicker = rememberImagePicker { data, _ -> model.setBanner(data) }

    FormSection(
        header = "Banner",
        footer = if (state.canEditBanner) "Shown at the top of your space's home page."
                 else "Only space admins can change the banner.",
    ) {
        state.bannerUrl?.let { banner ->
            SettingsMxcImage(
                url = banner,
                client = model.scope.service.client,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
        if (state.canEditBanner) {
            if (state.bannerUrl != null) FormRowDivider()
            FormButtonRow(
                title = if (state.bannerUrl == null) "Add Banner…" else "Change Banner…",
                onClick = { bannerPicker() },
            )
            if (state.bannerUrl != null) {
                FormRowDivider()
                FormButtonRow(title = "Remove", destructive = true,
                              onClick = { model.removeBanner() })
            }
        }
    }
}

/** Main-address + public-directory controls, shared by the room root form and space Visibility. */
@Composable
private fun AddressSection(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    val isSpace = model.target.isSpace

    // With no edit rights and no main address there is nothing to show.
    if (!state.canEditAddresses && state.canonicalAlias == null) return

    FormSection(
        header = if (isSpace) "Space Address" else "Room Address",
        footer = if (state.canonicalAlias == null) {
            if (isSpace) "This space has no main address."
            else "This room has no main address."
        } else null,
    ) {
        state.canonicalAlias?.let { alias ->
            FormLabeledRow("Main address") {
                Text(alias, color = colors.textSecondary, fontSize = 15.sp,
                     maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        if (state.canEditAddresses) {
            if (state.canonicalAlias != null) FormRowDivider()
            FormTextField(
                value = state.newAlias,
                onValueChange = model::updateNewAlias,
                placeholder = if (isSpace) "#my-space" else "#my-room",
                autoCorrect = false,
            )
            FormRowDivider()
            FormButtonRow(
                title = "Set Main Address",
                enabled = state.newAlias.trim().isNotEmpty(),
                onClick = { model.setMainAddress() },
            )
            FormRowDivider()
            FormToggleRow(
                title = if (isSpace) "Include this space in the public directory"
                        else "Include this room in the public room directory",
                checked = state.isInDirectory,
                onCheckedChange = { model.setDirectoryVisibility(it) },
            )
        }
    }
}

// MARK: - Security / Visibility

@Composable
private fun SecurityForm(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    val isSpace = model.target.isSpace
    var confirmingEncryption by remember { mutableStateOf(false) }

    FormScreen {
        if (!isSpace) {
            FormSection(
                header = "Encryption",
                footer = if (!state.isEncrypted && state.canEnableEncryption)
                    "Once enabled, encryption cannot be disabled." else null,
            ) {
                when {
                    state.isEncrypted -> FormIconLabelRow(
                        icon = Icons.Outlined.Lock, title = "End-to-end encrypted",
                        tint = colors.presenceOnline)
                    state.canEnableEncryption -> FormButtonRow(
                        title = "Enable Encryption…",
                        onClick = { confirmingEncryption = true })
                    else -> FormFootnoteRow("This room is not encrypted.")
                }
            }
        }

        FormSection(
            header = "Access",
            footer = if (isSpace) "Decide who can view and join this space."
                     else "Decide who can join this room.",
        ) {
            if (state.canEditAccess) {
                FormRadioRow(
                    title = "Private (invite only)", icon = Icons.Outlined.Lock,
                    selected = state.joinRule == RoomSettingsModel.JoinRuleChoice.INVITE,
                    onClick = { model.setJoinRule(RoomSettingsModel.JoinRuleChoice.INVITE) })
                if (model.parentSpaceIds.isNotEmpty()) {
                    FormRowDivider()
                    FormRadioRow(
                        title = "Space members", icon = Icons.Outlined.Group,
                        selected = state.joinRule == RoomSettingsModel.JoinRuleChoice.SPACE_MEMBERS,
                        onClick = { model.setJoinRule(RoomSettingsModel.JoinRuleChoice.SPACE_MEMBERS) })
                }
                FormRowDivider()
                FormRadioRow(
                    title = "Anyone", icon = Icons.Outlined.Public,
                    selected = state.joinRule == RoomSettingsModel.JoinRuleChoice.ANYONE,
                    onClick = { model.setJoinRule(RoomSettingsModel.JoinRuleChoice.ANYONE) })
            } else {
                val (title, icon) = joinRuleDisplay(state.joinRule)
                FormIconLabelRow(icon = icon, title = title, tint = colors.textPrimary)
            }
        }

        if (isSpace) {
            // "Preview space" == world-readable history.
            FormSection(
                footer = "Allow people to preview the space before joining. Recommended for public spaces.",
            ) {
                if (state.canEditAccess) {
                    FormToggleRow(
                        title = "Preview space",
                        checked = state.historyVisibility == RoomSettingsModel.HistoryChoice.WORLD_READABLE,
                        onCheckedChange = {
                            model.setHistoryVisibility(
                                if (it) RoomSettingsModel.HistoryChoice.WORLD_READABLE
                                else RoomSettingsModel.HistoryChoice.SHARED)
                        },
                    )
                } else {
                    FormLabeledRow("Preview space") {
                        Text(
                            if (state.historyVisibility == RoomSettingsModel.HistoryChoice.WORLD_READABLE)
                                "On" else "Off",
                            color = colors.textSecondary, fontSize = 15.sp)
                    }
                }
            }

            AddressSection(model, state)
        } else {
            FormSection(
                header = "Who Can Read History",
                footer = if (state.canEditAccess) "Changes only apply to new messages." else null,
            ) {
                if (state.canEditAccess) {
                    val options = listOf(
                        "Members only (since they were invited)" to RoomSettingsModel.HistoryChoice.INVITED,
                        "Members only (since they joined)" to RoomSettingsModel.HistoryChoice.JOINED,
                        "Members only (since this option was selected)" to RoomSettingsModel.HistoryChoice.SHARED,
                        "Anyone" to RoomSettingsModel.HistoryChoice.WORLD_READABLE,
                    )
                    options.forEachIndexed { index, (title, choice) ->
                        if (index > 0) FormRowDivider()
                        FormRadioRow(
                            title = title,
                            selected = state.historyVisibility == choice,
                            onClick = { model.setHistoryVisibility(choice) })
                    }
                } else {
                    FormFootnoteRow(historyDisplay(state.historyVisibility))
                }
            }
        }

        StatusSection(state)
    }

    if (confirmingEncryption) {
        AlertDialog(
            onDismissRequest = { confirmingEncryption = false },
            title = { Text("Enable end-to-end encryption?") },
            text = { Text("This can't be undone — once enabled, encryption stays on for this room permanently.") },
            confirmButton = {
                TextButton(onClick = { confirmingEncryption = false; model.enableEncryption() }) {
                    Text("Enable Encryption")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEncryption = false }) { Text("Cancel") }
            },
        )
    }
}

// MARK: - Roles & Permissions

private val roleOptions: List<Pair<String, Long>> = listOf(
    "Default" to 0L, "Moderator" to 50L, "Administrator" to 100L,
)

@Composable
private fun RolesForm(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    var newUserId by remember { mutableStateOf("") }
    var newUserLevel by remember { mutableStateOf(50L) }

    FormScreen {
        FormSection(header = "Privileged Users") {
            if (state.privilegedUsers.isEmpty()) {
                FormFootnoteRow("No privileged users.")
            }
            state.privilegedUsers.forEachIndexed { index, user ->
                if (index > 0) FormRowDivider()
                FormLabeledRow(user.userId) {
                    if (state.canEditRoles) {
                        LevelPicker(
                            level = user.level,
                            onSelect = { model.setUserLevel(userId = user.userId, level = it) },
                        )
                    } else {
                        Text(roleName(user.level), color = colors.textSecondary, fontSize = 15.sp)
                    }
                }
            }
        }

        if (state.canEditRoles) {
            FormSection(header = "Add Privileged User") {
                FormTextField(
                    value = newUserId,
                    onValueChange = { newUserId = it },
                    placeholder = "@user:server",
                    autoCorrect = false,
                )
                FormRowDivider()
                FormLabeledRow("Role") {
                    LevelPicker(level = newUserLevel, onSelect = { newUserLevel = it })
                }
                FormRowDivider()
                FormButtonRow(
                    title = "Add",
                    enabled = newUserId.startsWith("@"),
                    onClick = {
                        model.setUserLevel(userId = newUserId.trim(), level = newUserLevel)
                        newUserId = ""
                    },
                )
            }

            RoleLabelsEditor(model, state)
        }

        FormSection(
            header = "Permissions",
            footer = if (state.canEditRoles) "Choose the role required for each action."
                     else "The role required for each action.",
        ) {
            state.permissionValues?.let { values ->
                PermissionRows(model, state, values)
            }
        }

        StatusSection(state)
    }
}

@Composable
private fun PermissionRows(
    model: RoomSettingsModel,
    state: RoomSettingsModel.State,
    values: RoomPowerLevelsValues,
) {
    val colors = LocalDiscourseColors.current
    val isSpace = model.target.isSpace
    val rows: List<Triple<String, Long, (Long) -> RoomPowerLevelChanges>> = listOf(
        Triple("Default role", values.usersDefault) { l: Long -> RoomPowerLevelChanges(usersDefault = l) },
        Triple("Send messages", values.eventsDefault) { l: Long -> RoomPowerLevelChanges(eventsDefault = l) },
        Triple("Invite users", values.invite) { l: Long -> RoomPowerLevelChanges(invite = l) },
        Triple("Change settings", values.stateDefault) { l: Long -> RoomPowerLevelChanges(stateDefault = l) },
        Triple("Remove users", values.kick) { l: Long -> RoomPowerLevelChanges(kick = l) },
        Triple("Ban users", values.ban) { l: Long -> RoomPowerLevelChanges(ban = l) },
        Triple("Remove messages sent by others", values.redact) { l: Long -> RoomPowerLevelChanges(redact = l) },
        Triple(if (isSpace) "Change space name" else "Change room name",
               values.roomName) { l: Long -> RoomPowerLevelChanges(roomName = l) },
        Triple(if (isSpace) "Change space avatar" else "Change room avatar",
               values.roomAvatar) { l: Long -> RoomPowerLevelChanges(roomAvatar = l) },
        Triple(if (isSpace) "Change description" else "Change topic",
               values.roomTopic) { l: Long -> RoomPowerLevelChanges(roomTopic = l) },
    )

    rows.forEachIndexed { index, (title, level, changes) ->
        if (index > 0) FormRowDivider()
        FormLabeledRow(title) {
            if (state.canEditRoles) {
                LevelPicker(level = level, onSelect = { model.applyPermissions(changes(it)) })
            } else {
                Text(roleName(level), color = colors.textSecondary, fontSize = 15.sp)
            }
        }
    }
}

/**
 * Names, colors, and emoji for each power level — writes the Cinny-compatible
 * `in.cinny.room.power_level_tags` event.
 */
@Composable
private fun RoleLabelsEditor(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    var emojiLevel by remember { mutableStateOf<Int?>(null) }

    val customPacks by model.scope.customEmoji.packs.collectAsStateWithLifecycle()
    val emoteLoader = remember(model.scope) {
        object : EmoteAssetLoader {
            override fun cachedImage(mxcUrl: String, pixelSize: Float) =
                model.scope.mediaLoader.cachedImage(mxcUrl, pixelSize)

            override suspend fun avatar(mxcUrl: String, pixelSize: Float) =
                model.scope.mediaLoader.avatar(mxcUrl, pixelSize)
        }
    }
    val pickerLoader = remember(model.scope) {
        EmoteImageLoader { url -> model.scope.mediaLoader.avatar(url, 64f) }
    }

    val palette = remember {
        listOf("#e64980", "#f76707", "#f59f00", "#37b24d",
               "#1c7ed6", "#7048e8", "#ae3ec9", "#868e96")
    }

    val levels: List<Int> = remember(state.privilegedUsers, state.powerLevelTags) {
        val set = mutableSetOf(0, 50, 100)
        set.addAll(state.privilegedUsers.map { it.level.toInt() })
        set.addAll(state.powerLevelTags.keys)
        set.sortedDescending()
    }

    FormSection(
        header = "Role Labels",
        footer = "Name, color, and emoji per power level. Names and colors interop with Cinny.",
    ) {
        levels.forEachIndexed { index, level ->
            if (index > 0) FormRowDivider()
            val tag = state.powerLevelTags[level] ?: PowerLevelTags.defaultTag(level)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.bgElevated2)
                            .clickable { emojiLevel = level },
                        contentAlignment = Alignment.Center,
                    ) {
                        RoleIconPreview(tag, loader = emoteLoader)
                    }
                    TextField(
                        value = tag.name,
                        onValueChange = { model.setRoleTag(level, tag.copy(name = it)) },
                        placeholder = { Text("Level $level", color = colors.textTertiary) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = formFieldColors(),
                    )
                    Text("$level", color = colors.textTertiary, fontSize = 14.sp,
                         fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    palette.forEach { hex ->
                        val selected = tag.color == hex
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(colorFromHex(hex) ?: colors.textTertiary)
                                .then(
                                    if (selected) Modifier.border(2.dp, colors.textPrimary, CircleShape)
                                    else Modifier
                                )
                                .clickable { model.setRoleTag(level, tag.copy(color = hex)) },
                        )
                    }
                    IconButton(
                        onClick = { model.setRoleTag(level, tag.copy(color = null)) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(Icons.Outlined.Block, contentDescription = "No color",
                             tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        FormRowDivider()
        FormButtonRow(title = "Save labels", onClick = { model.savePowerLevelTags() })
    }

    // iOS shows the full EmojiPickerView (unicode + custom emote packs) in a
    // 320×360 popover; a Dialog is the phone equivalent.
    emojiLevel?.let { level ->
        val tag = state.powerLevelTags[level] ?: PowerLevelTags.defaultTag(level)
        Dialog(onDismissRequest = { emojiLevel = null }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .height(360.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.bgElevated),
            ) {
                EmojiPickerView(
                    customPacks = customPacks,
                    loader = pickerLoader,
                    insertCustom = { emote ->
                        model.setRoleTag(level, tag.copy(iconKey = emote.url))
                        emojiLevel = null
                    },
                    insert = { emoji ->
                        model.setRoleTag(level, tag.copy(iconKey = emoji))
                        emojiLevel = null
                    },
                )
            }
        }
    }
}

@Composable
private fun RoleIconPreview(tag: PowerLevelTag, loader: EmoteAssetLoader?) {
    val colors = LocalDiscourseColors.current
    val key = tag.iconKey
    when {
        key.isNullOrEmpty() -> Icon(Icons.Outlined.Mood, contentDescription = null,
                                    tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        tag.iconIsMxc -> EmoteImageView(url = key, size = 22.dp, loader = loader)
        else -> Text(key, fontSize = 16.sp)
    }
}

// MARK: - Notifications

@Composable
private fun NotificationsForm(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    FormScreen {
        FormSection {
            val options = listOf(
                "Default — follow your global settings" to RoomSettingsModel.NotificationChoice.DEFAULT,
                "All messages" to RoomSettingsModel.NotificationChoice.ALL,
                "@mentions and keywords only" to RoomSettingsModel.NotificationChoice.MENTIONS,
                "Off" to RoomSettingsModel.NotificationChoice.MUTE,
            )
            options.forEachIndexed { index, (title, choice) ->
                if (index > 0) FormRowDivider()
                FormRadioRow(
                    title = title,
                    selected = state.notificationMode == choice,
                    onClick = { model.setNotificationMode(choice) })
            }
        }

        StatusSection(state)
    }
}

// MARK: - Poll history

@Composable
private fun PollHistoryForm(model: RoomSettingsModel) {
    val colors = LocalDiscourseColors.current
    val timeline = remember { model.timelineForRoom?.invoke(model.target.roomId) }
    val entriesFlow = remember(timeline) {
        timeline?.entries ?: MutableStateFlow<List<TimelineEntry>>(emptyList())
    }
    val entries by entriesFlow.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose { parkPollTimelineIfInactive(model) }
    }

    val polls: List<Pair<MessageItem, PollItem>> = remember(entries) {
        entries.mapNotNull { entry ->
            val message = (entry as? TimelineEntry.Message)?.item ?: return@mapNotNull null
            val poll = (message.kind as? MessageItem.Kind.Poll)?.item ?: return@mapNotNull null
            message to poll
        }.reversed()
    }

    FormScreen {
        FormSection(footer = "Polls from the loaded timeline history.") {
            if (polls.isEmpty()) {
                FormFootnoteRow("No polls found in the loaded history.")
            } else {
                val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
                polls.forEachIndexed { index, (message, poll) ->
                    if (index > 0) FormRowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Outlined.BarChart, contentDescription = null,
                             tint = if (poll.isEnded) colors.textSecondary else colors.accent)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(poll.question, color = colors.textPrimary, fontSize = 15.sp)
                            val status = if (poll.isEnded) "Ended" else "Active"
                            val votes = if (poll.totalVotes == 1) "1 vote" else "${poll.totalVotes} votes"
                            Text(
                                "${dateFormat.format(Date(message.timestamp))} — $status — $votes",
                                color = colors.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Poll history materializes the room's timeline view model just to read entries.
 * Park it on the way out so LRU eviction can reclaim it; the actively open room
 * is exempt (navigation owns its parking).
 */
private fun parkPollTimelineIfInactive(model: RoomSettingsModel) {
    if (model.roomList.activeRoomId.value == model.target.roomId) return
    model.timelineForRoom?.invoke(model.target.roomId)?.isParked = true
}

// MARK: - Advanced

@Composable
private fun AdvancedForm(model: RoomSettingsModel, state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val isSpace = model.target.isSpace

    FormScreen {
        FormSection(header = if (isSpace) "Space Information" else "Room Information") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (isSpace) "Internal space ID" else "Internal room ID",
                         color = colors.textPrimary, fontSize = 15.sp)
                    Text(model.target.roomId, color = colors.textSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("room id", model.target.roomId))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy",
                         tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            FormRowDivider()
            FormLabeledRow("Room version") {
                Text(state.roomVersion, color = colors.textSecondary, fontSize = 15.sp)
            }
            FormRowDivider()
            FormLabeledRow("Members") {
                Text(state.memberCount.toString(), color = colors.textSecondary, fontSize = 15.sp)
            }
        }
    }
}

// MARK: - Read-only display helpers

/** Title + icon for a join-rule choice, for the read-only (no-permission) case. */
private fun joinRuleDisplay(choice: RoomSettingsModel.JoinRuleChoice): Pair<String, ImageVector> =
    when (choice) {
        RoomSettingsModel.JoinRuleChoice.INVITE -> "Private (invite only)" to Icons.Outlined.Lock
        RoomSettingsModel.JoinRuleChoice.SPACE_MEMBERS -> "Space members" to Icons.Outlined.Group
        RoomSettingsModel.JoinRuleChoice.ANYONE -> "Anyone" to Icons.Outlined.Public
    }

private fun historyDisplay(choice: RoomSettingsModel.HistoryChoice): String = when (choice) {
    RoomSettingsModel.HistoryChoice.INVITED -> "Members only (since they were invited)"
    RoomSettingsModel.HistoryChoice.JOINED -> "Members only (since they joined)"
    RoomSettingsModel.HistoryChoice.SHARED -> "Members only (since this option was selected)"
    RoomSettingsModel.HistoryChoice.WORLD_READABLE -> "Anyone"
}

private fun roleName(level: Long): String = when (level) {
    0L -> "Default"
    50L -> "Moderator"
    100L -> "Administrator"
    else -> "Custom ($level)"
}

/** Error/info feedback as its own section, only when present. */
@Composable
private fun StatusSection(state: RoomSettingsModel.State) {
    val colors = LocalDiscourseColors.current
    if (state.errorMessage == null && state.infoMessage == null) return
    FormSection {
        state.errorMessage?.let {
            Text(it, color = colors.unreadMention, fontSize = 14.sp,
                 modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
        state.infoMessage?.let {
            Text(it, color = colors.presenceOnline, fontSize = 14.sp,
                 modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

// MARK: - Grouped-form primitives (iOS Form parity)

@Composable
internal fun FormScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        content()
    }
}

@Composable
internal fun FormSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column {
        header?.let {
            Text(it.uppercase(), color = colors.textSecondary, fontSize = 12.sp,
                 modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated),
        ) {
            content()
        }
        footer?.let {
            Text(it, color = colors.textTertiary, fontSize = 12.sp,
                 modifier = Modifier.padding(start = 12.dp, top = 6.dp))
        }
    }
}

@Composable
internal fun FormRowDivider() {
    val colors = LocalDiscourseColors.current
    HorizontalDivider(color = colors.separator, modifier = Modifier.padding(start = 12.dp))
}

@Composable
internal fun FormLabeledRow(label: String, trailing: @Composable () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textPrimary, fontSize = 15.sp,
             maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
             modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
internal fun FormButtonRow(
    title: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    centered: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val color = when {
        !enabled -> colors.textTertiary
        destructive -> colors.unreadMention
        else -> colors.accent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(title, color = color, fontSize = 15.sp)
    }
}

@Composable
internal fun FormToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun FormRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = colors.textSecondary,
                 modifier = Modifier.size(20.dp))
        }
        Text(title, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
internal fun FormNavigationRow(title: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent,
             modifier = Modifier.size(20.dp))
        Text(title, color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textTertiary)
    }
}

@Composable
internal fun FormIconLabelRow(icon: ImageVector, title: String, tint: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(title, color = tint, fontSize = 15.sp)
    }
}

@Composable
internal fun FormFootnoteRow(text: String) {
    val colors = LocalDiscourseColors.current
    Text(text, color = colors.textSecondary, fontSize = 15.sp,
         modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
}

@Composable
internal fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    autoCorrect: Boolean = true,
) {
    val colors = LocalDiscourseColors.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = colors.textTertiary) },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 4,
        modifier = Modifier.fillMaxWidth(),
        // Aliases/user IDs: no auto-capitalize, no auto-correct (iOS parity).
        keyboardOptions = if (autoCorrect) KeyboardOptions.Default
            else KeyboardOptions(capitalization = KeyboardCapitalization.None,
                                 autoCorrectEnabled = false),
        colors = formFieldColors(),
    )
}

@Composable
internal fun formFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)

/** Role dropdown: Default / Moderator / Administrator (+ Custom passthrough). */
@Composable
private fun LevelPicker(level: Long, onSelect: (Long) -> Unit) {
    val colors = LocalDiscourseColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(roleName(level), color = colors.accent, fontSize = 15.sp)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            roleOptions.forEach { (name, value) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelect(value) },
                )
            }
            if (roleOptions.none { it.second == level }) {
                DropdownMenuItem(
                    text = { Text("Custom ($level)") },
                    onClick = { expanded = false },
                )
            }
        }
    }
}

// MARK: - Shared media helpers
// Interim mxc rendering until the MediaLoader port lands; swap these for the
// shared avatar/banner/emote views then.

/** Loads and renders an mxc URL through the SDK's media store. */
@Composable
internal fun SettingsMxcImage(
    url: String,
    client: Client,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = LocalDiscourseColors.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = MediaSource.fromUrl(url).use { client.getMediaContent(it) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let {
        androidx.compose.foundation.Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } ?: Box(modifier.background(colors.bgElevated2))
}

/** Room avatar: image when set, else an accent-tinted initial. */
@Composable
internal fun SettingsRoomAvatar(name: String, size: Dp, avatarUrl: String?, client: Client) {
    val colors = LocalDiscourseColors.current
    if (avatarUrl != null) {
        SettingsMxcImage(
            url = avatarUrl, client = client,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "#",
                color = colors.accent,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One image picker callback: reads the picked document's bytes off-main. */
@Composable
internal fun rememberImagePicker(onPicked: (data: ByteArray, displayName: String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    val name = context.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    data?.let { it to name }
                }.getOrNull()
            } ?: return@launch
            onPicked(result.first, result.second)
        }
    }
    return { launcher.launch("image/*") }
}

private fun colorFromHex(hex: String): Color? {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or value)
}
