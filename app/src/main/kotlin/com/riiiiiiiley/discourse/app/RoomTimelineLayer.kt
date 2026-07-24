package com.riiiiiiiley.discourse.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.core.ReactionUsage
import com.riiiiiiiley.discourse.features.compose.InviteSheet
import com.riiiiiiiley.discourse.features.profile.ProfileSheet
import com.riiiiiiiley.discourse.features.search.RoomSearchSheet
import com.riiiiiiiley.discourse.features.settings.SettingsTarget
import com.riiiiiiiley.discourse.features.timeline.ComposerView
import com.riiiiiiiley.discourse.features.timeline.DayDividerView
import com.riiiiiiiley.discourse.features.timeline.EmojiPickerView
import com.riiiiiiiley.discourse.features.timeline.EmoteAssetLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteImageLoader
import com.riiiiiiiley.discourse.features.timeline.MessageRow
import com.riiiiiiiley.discourse.features.profile.ProfileTarget
import com.riiiiiiiley.discourse.features.timeline.ReadMarkerView
import com.riiiiiiiley.discourse.features.timeline.SystemRow
import com.riiiiiiiley.discourse.features.timeline.ThreadScreen
import com.riiiiiiiley.discourse.features.timeline.TimelineMediaRenderers
import com.riiiiiiiley.discourse.features.timeline.TimelineScreen
import com.riiiiiiiley.discourse.features.timeline.TimelineStartView
import com.riiiiiiiley.discourse.features.timeline.TimelineViewModel
import com.riiiiiiiley.discourse.features.timeline.media.InlineImageView
import com.riiiiiiiley.discourse.features.timeline.media.VideoAttachmentView
import com.riiiiiiiley.discourse.features.timeline.media.VoiceMessageView
import com.riiiiiiiley.discourse.features.timeline.PollView
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors

/** A thread sheet target: a FRESH thread view model per open (iOS parity). */
private class ThreadTarget(val id: String, val viewModel: TimelineViewModel)

/**
 * Resolves a room id to its timeline and mounts the chat screen with every
 * cross-slice hook (media renderers, threads, profiles, reactions, search,
 * settings, invites, calls). The lookup retries as the room list updates — a
 * cold-launch-restored room has no FFI backing until the first sync batch
 * (iOS RoomTimelineDestination).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomTimelineLayer(
    appState: AppState,
    scope: SessionScope,
    roomId: String,
    closeChat: () -> Unit,
    onStartCall: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val rooms by scope.roomList.rooms.collectAsStateWithLifecycle()

    var timelineVm by remember(roomId) { mutableStateOf(scope.timeline(roomId)) }
    LaunchedEffect(roomId, rooms) {
        if (timelineVm == null) timelineVm = scope.timeline(roomId)
    }

    val viewModel = timelineVm
    if (viewModel == null) {
        Box(
            Modifier.fillMaxSize().background(colors.bgApp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.textSecondary)
        }
        return
    }

    // One fetch per room per session: room emote packs for reaction labels.
    LaunchedEffect(roomId) {
        runCatching {
            scope.customEmoji.ensureRoomPack(roomId, viewModel.roomName.value)
        }
    }

    val emoteLoader = remember(scope) {
        object : EmoteAssetLoader {
            override fun cachedImage(mxcUrl: String, pixelSize: Float) =
                scope.mediaLoader.cachedImage(mxcUrl, pixelSize)

            override suspend fun avatar(mxcUrl: String, pixelSize: Float) =
                scope.mediaLoader.avatar(mxcUrl, pixelSize)
        }
    }
    val pickerEmoteLoader = remember(scope) {
        EmoteImageLoader { url -> scope.mediaLoader.avatar(url, 64f) }
    }

    fun mediaRenderers(forViewModel: TimelineViewModel) = TimelineMediaRenderers(
        image = { _, item ->
            InlineImageView(item, loader = scope.mediaLoader,
                            preferences = appState.preferences)
        },
        video = { _, item -> VideoAttachmentView(item, loader = scope.mediaLoader) },
        poll = { message, item ->
            PollView(poll = item, message = message, viewModel = forViewModel)
        },
        audio = { message, item ->
            VoiceMessageView(itemId = message.id, audio = item,
                             loader = scope.mediaLoader,
                             controller = forViewModel.audioPlayback)
        },
    )

    val videoRoomIds by scope.roomList.videoRoomIds.collectAsStateWithLifecycle()

    var threadTarget by remember(roomId) { mutableStateOf<ThreadTarget?>(null) }
    var profileTarget by remember { mutableStateOf<ProfileTarget?>(null) }
    var reactionTarget by remember(roomId) { mutableStateOf<MessageItem?>(null) }
    var showsRoomSearch by remember(roomId) { mutableStateOf(false) }
    var showsRoomSettings by remember(roomId) { mutableStateOf(false) }
    var showsInvite by remember(roomId) { mutableStateOf(false) }

    TimelineScreen(
        viewModel = viewModel,
        appState = appState,
        closeChat = closeChat,
        emoteLoader = emoteLoader,
        mediaRenderers = mediaRenderers(viewModel),
        videoRoomIds = videoRoomIds,
        onStartCall = onStartCall,
        onOpenSearch = { showsRoomSearch = true },
        onOpenThread = { rootEventId ->
            threadTarget = ThreadTarget(
                id = rootEventId,
                viewModel = viewModel.threadViewModel(rootEventId),
            )
        },
        onOpenProfile = { profileTarget = it },
        onMoreReactions = { reactionTarget = it },
        onInvitePeople = { showsInvite = true },
        onOpenRoomSettings = { showsRoomSettings = true },
        composer = {
            ComposerView(
                viewModel = viewModel,
                preferences = appState.preferences,
                sessionScope = scope,
            )
        },
    )

    // MARK: Thread sheet (near-full height, keyboard handled by ThreadScreen).

    threadTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { threadTarget = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.bgApp,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            val threadVm = target.viewModel
            val prefs by appState.preferences.state.collectAsStateWithLifecycle()
            val lastOwnMessageId by threadVm.lastOwnMessageId.collectAsStateWithLifecycle()
            val membersById by threadVm.membersById.collectAsStateWithLifecycle()
            val shields by threadVm.shields.collectAsStateWithLifecycle()
            val canRedactOwn by threadVm.canRedactOwn.collectAsStateWithLifecycle()
            val canRedactOther by threadVm.canRedactOther.collectAsStateWithLifecycle()
            val ownDisplayName by scope.ownDisplayName.collectAsStateWithLifecycle()
            val ownAvatarUrl by scope.ownAvatarUrl.collectAsStateWithLifecycle()

            ThreadScreen(
                viewModel = threadVm,
                onDismiss = { threadTarget = null },
                entryRow = { entry ->
                    when (entry) {
                        is TimelineEntry.Message -> MessageRow(
                            message = entry.item,
                            viewModel = threadVm,
                            prefs = prefs,
                            lastOwnMessageId = lastOwnMessageId,
                            shield = entry.item.eventId?.let { shields[it] },
                            membersById = membersById,
                            canRedactOwn = canRedactOwn,
                            canRedactOther = canRedactOther,
                            ownDisplayName = ownDisplayName,
                            ownAvatarUrl = ownAvatarUrl,
                            emoteLoader = emoteLoader,
                            mediaRenderers = mediaRenderers(threadVm),
                            // Thread rows don't nest further (iOS `{ _ in }`).
                            openThread = {},
                            openProfile = { profileTarget = it },
                            jumpToEvent = {},
                            onMoreReactions = { reactionTarget = it },
                        )
                        is TimelineEntry.System -> SystemRow(entry.text)
                        is TimelineEntry.DayDivider -> DayDividerView(entry.date)
                        is TimelineEntry.ReadMarker -> ReadMarkerView()
                        is TimelineEntry.TimelineStart -> TimelineStartView()
                        is TimelineEntry.Hidden -> {}
                    }
                },
                composer = {
                    // The sheet ignores the keyboard; ThreadScreen lifts the
                    // bar itself, so the composer must NOT double-pad.
                    ComposerView(
                        viewModel = threadVm,
                        preferences = appState.preferences,
                        sessionScope = scope,
                        applyImePadding = false,
                    )
                },
            )
        }
    }

    // MARK: Profile sheet (self-hosts its modal).

    profileTarget?.let { target ->
        ProfileSheet(
            target = target,
            ownUserId = scope.userId,
            appState = appState,
            roomList = scope.roomList,
            message = { userId ->
                runCatching { scope.service.startDm(userId) }.getOrNull()
                    ?.let { dmRoomId ->
                        appState.pendingRoomNavigation.value = dmRoomId
                        true
                    } ?: false
            },
            onDismiss = { profileTarget = null },
        )
    }

    // MARK: Full reaction picker.

    reactionTarget?.let { message ->
        val customPacks by scope.customEmoji.packs.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = { reactionTarget = null },
            containerColor = colors.bgApp,
        ) {
            EmojiPickerView(
                customPacks = customPacks,
                loader = pickerEmoteLoader,
                insertCustom = { emote ->
                    viewModel.toggleReaction(emote.url, message)
                    reactionTarget = null
                },
                insert = { key ->
                    // Unicode keys feed the learned quick-reaction palette.
                    ReactionUsage.record(context, key)
                    viewModel.toggleReaction(key, message)
                    reactionTarget = null
                },
            )
        }
    }

    // MARK: In-room search.

    if (showsRoomSearch) {
        // Full height (iOS presents this as a full sheet): half-expanded, the
        // coverage footer / "Search Older Messages" lever / result count all
        // lay out below the screen edge behind the keyboard.
        ModalBottomSheet(
            onDismissRequest = { showsRoomSearch = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.bgApp,
        ) {
            RoomSearchSheet(
                viewModel = viewModel,
                // Fires AFTER dismiss (iOS ordering); the timeline consumes it.
                onSelect = { eventId ->
                    appState.pendingEventNavigation.value =
                        AppState.EventNavigation(roomId, eventId)
                },
                onDismiss = { showsRoomSearch = false },
            )
        }
    }

    // MARK: Room settings + invite (details-sheet hooks).

    if (showsRoomSettings) {
        RoomSettingsLayer(
            scope = scope,
            target = SettingsTarget(roomId = roomId, isSpace = false),
            onDismiss = { showsRoomSettings = false },
        )
    }

    if (showsInvite) {
        val roomName by viewModel.roomName.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = { showsInvite = false },
            containerColor = colors.bgApp,
        ) {
            InviteSheet(
                scope = scope,
                roomList = scope.roomList,
                roomId = roomId,
                roomName = roomName,
                onDismiss = { showsInvite = false },
            )
        }
    }
}
