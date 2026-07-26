@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.riiiiiiiley.discourse.features.timeline

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.LocalCustomEmojiStore
import com.riiiiiiiley.discourse.core.LocalPronounsStore
import com.riiiiiiiley.discourse.core.MessageDensity
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.ReactionUsage
import com.riiiiiiiley.discourse.core.media.LocalMediaLoader
import com.riiiiiiiley.discourse.features.profile.ProfileTarget
import com.riiiiiiiley.discourse.features.timeline.media.MediaExport
import com.riiiiiiiley.discourse.models.AudioItem
import com.riiiiiiiley.discourse.models.ImageItem
import com.riiiiiiiley.discourse.models.MentionRef
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.MessageReaction
import com.riiiiiiiley.discourse.models.PollItem
import com.riiiiiiiley.discourse.models.VideoItem
import com.riiiiiiiley.discourse.ui.theme.DiscourseColors
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ProfileTarget lives in features.profile (ProfileSheet.kt) — one definition
// app-wide; rows import it to hand sender taps to the sheet.

/**
 * Renderers for media message kinds, injected by the media slice
 * (InlineImageView / VideoAttachmentView / PollView / VoiceMessageView ports).
 * The defaults render a labeled chip so this slice stands alone.
 */
class TimelineMediaRenderers(
    val image: @Composable (MessageItem, ImageItem) -> Unit = { _, item ->
        AttachmentChip(
            label = item.caption ?: item.filename.ifEmpty { "Image" },
            icon = { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = LocalDiscourseColors.current.textSecondary) },
        )
    },
    val video: @Composable (MessageItem, VideoItem) -> Unit = { _, item ->
        AttachmentChip(
            label = item.caption ?: item.filename.ifEmpty { "Video" },
            icon = { Icon(Icons.Outlined.Videocam, null, Modifier.size(18.dp), tint = LocalDiscourseColors.current.textSecondary) },
        )
    },
    val poll: @Composable (MessageItem, PollItem) -> Unit = { _, item ->
        AttachmentChip(
            label = item.question,
            icon = { Icon(Icons.Outlined.Poll, null, Modifier.size(18.dp), tint = LocalDiscourseColors.current.textSecondary) },
        )
    },
    val audio: @Composable (MessageItem, AudioItem) -> Unit = { _, item ->
        AttachmentChip(
            label = if (item.isVoiceMessage) "Voice message" else item.filename,
            icon = { Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = LocalDiscourseColors.current.textSecondary) },
        )
    },
)

private val GUTTER_WIDTH = 40.dp
private val REPLY_THRESHOLD = 48.dp
private val REPLY_CAP = 64.dp

/** Generous gap above a new sender group, tight within one (iOS Preferences). */
val MessageDensity.groupTopPadding: Dp get() = if (this == MessageDensity.COMPACT) 8.dp else 14.dp
val MessageDensity.rowVerticalPadding: Dp get() = if (this == MessageDensity.COMPACT) 1.dp else 2.dp

/**
 * A flat message row: header rows carry avatar + name + timestamp, grouped
 * rows are text-only with a timestamp in the avatar gutter (touch has no
 * hover, so it shows only with `alwaysShowTimestamps`; the context menu
 * carries it otherwise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: MessageItem,
    viewModel: TimelineViewModel,
    prefs: Preferences.Snapshot,
    lastOwnMessageId: String?,
    shield: MessageItem.ShieldWarning?,
    membersById: Map<String, TimelineViewModel.MemberItem>,
    canRedactOwn: Boolean,
    canRedactOther: Boolean,
    /** Own messages show live profile edits at once (SessionScope values). */
    ownDisplayName: String?,
    ownAvatarUrl: String?,
    emoteLoader: EmoteAssetLoader?,
    mediaRenderers: TimelineMediaRenderers,
    openThread: (String) -> Unit,
    openProfile: (ProfileTarget) -> Unit,
    jumpToEvent: (String) -> Unit,
    /** Opens the full emoji picker (emoji-picker slice); null hides the entry points. */
    onMoreReactions: ((MessageItem) -> Unit)?,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    /** Full-content fetches for the image share/save menu actions. */
    val mediaLoader = LocalMediaLoader.current

    val effectiveName = if (message.isOwn && !ownDisplayName.isNullOrEmpty()) ownDisplayName
        else message.displayName
    val effectiveAvatarUrl = if (message.isOwn) ownAvatarUrl else message.senderAvatarUrl
    val profileTarget = ProfileTarget(
        userId = message.sender,
        displayName = if (message.isOwn) ownDisplayName else message.senderDisplayName,
        avatarUrl = effectiveAvatarUrl,
    )

    var menuExpanded by remember(message.id) { mutableStateOf(false) }
    var showsShieldInfo by remember { mutableStateOf(false) }
    var showsReportPrompt by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var reportResult by remember { mutableStateOf<ReportResult?>(null) }
    // Retry/Delete choices behind the red failure icon.
    var showsFailedSendOptions by remember { mutableStateOf(false) }
    // Gated behind the "Confirm before deleting" preference.
    var showsDeleteConfirm by remember { mutableStateOf(false) }

    // Whether to offer "Delete Message": own messages need redact-own power,
    // others need redact-other (a moderator deleting someone else's message).
    val canDelete = if (message.isOwn) canRedactOwn else canRedactOther
    fun requestDelete() {
        if (prefs.confirmBeforeDeleting) showsDeleteConfirm = true else viewModel.redact(message)
    }

    fun toggleReaction(key: String) {
        if (!key.startsWith("mxc://")) ReactionUsage.record(context, key)
        viewModel.toggleReaction(key, message)
    }

    // Keyed on event ID so local echoes fetch their shield once the real ID
    // lands via a `.set` diff.
    LaunchedEffect(message.eventId) { viewModel.loadShieldIfNeeded(message) }

    // Swipe-to-reply: leftward drag offset of the row content. Leftward on
    // purpose: the phone pager owns rightward drags for closing the chat layer.
    val replyDragOffset = remember { Animatable(0f) }
    var replyDragTriggered by remember { mutableStateOf(false) }
    val thresholdPx = with(density) { REPLY_THRESHOLD.toPx() }
    val capPx = with(density) { REPLY_CAP.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (message.canBeRepliedTo) {
                    // Engages only for leftward drags (iOS simultaneousGesture
                    // with translation.width < 0 and the |w| > |h|·1.5 axis
                    // gate): the phone pager owns rightward drags for closing
                    // the chat layer, and consuming them here — the child's
                    // Main-pass consumption beats MainShell's outer detector —
                    // would kill swipe-to-close over every replyable row.
                    Modifier.pointerInput(message.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var accumulated = 0f
                            val slopChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                // Rightward slop stays unconsumed so the pager
                                // (and vertical scrolling) win the gesture.
                                if (overSlop < 0f) {
                                    accumulated = overSlop
                                    change.consume()
                                }
                            } ?: return@awaitEachGesture
                            if (accumulated >= 0f) return@awaitEachGesture
                            // A cancelled earlier drag may have left this latched.
                            replyDragTriggered = false
                            fun applyDrag() {
                                val magnitude = (-accumulated).coerceAtLeast(0f)
                                // Rubber-band past the threshold, hard cap at replyCap.
                                val resisted = if (magnitude <= thresholdPx) magnitude
                                    else thresholdPx + (magnitude - thresholdPx) * 0.25f
                                scope.launch { replyDragOffset.snapTo(-min(resisted, capPx)) }
                                val past = magnitude >= thresholdPx
                                if (past != replyDragTriggered) {
                                    replyDragTriggered = past
                                    // Fires only on the false→true edge.
                                    if (past) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            applyDrag()
                            val completed = horizontalDrag(slopChange.id) { change ->
                                accumulated += change.positionChange().x
                                change.consume()
                                applyDrag()
                            }
                            if (completed && replyDragTriggered) {
                                viewModel.replyTarget.value = message
                            }
                            replyDragTriggered = false
                            scope.launch {
                                if (prefs.reduceMotion) replyDragOffset.snapTo(0f)
                                else replyDragOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    }
                } else Modifier,
            ),
    ) {
        // The offset moves only the rendered content; the glyph overlay stays
        // pinned to the row's layout frame.
        if (replyDragOffset.value < 0f) {
            // Accent snaps in when the drag crosses the reply threshold; a short
            // tween makes that state flip read as a deliberate confirm cue.
            val replyIconTint by animateColorAsState(
                targetValue = if (replyDragTriggered) colors.accent else colors.textSecondary,
                animationSpec = if (prefs.reduceMotion) tween(0) else tween(150),
                label = "replySwipeTint",
            )
            Icon(
                Icons.AutoMirrored.Outlined.Reply,
                contentDescription = null,
                tint = replyIconTint,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .alpha(min(1f, -replyDragOffset.value / thresholdPx)),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .offset { IntOffset(replyDragOffset.value.roundToInt(), 0) }
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                )
                .padding(
                    top = if (message.showsHeader) prefs.messageDensity.groupTopPadding
                        else prefs.messageDensity.rowVerticalPadding,
                    bottom = prefs.messageDensity.rowVerticalPadding,
                )
                .padding(horizontal = 8.dp)
                .alpha(if (message.sendState == MessageItem.SendState.SENDING) 0.55f else 1f),
        ) {
            RowGutter(
                message = message,
                prefs = prefs,
                effectiveName = effectiveName,
                effectiveAvatarUrl = effectiveAvatarUrl,
                emoteLoader = emoteLoader,
                onOpenProfile = { openProfile(profileTarget) },
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (message.showsHeader) {
                    RowHeader(
                        message = message,
                        prefs = prefs,
                        effectiveName = effectiveName,
                        onOpenProfile = { openProfile(profileTarget) },
                    )
                }
                message.replyPreview?.let { reply ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(quaternaryFill(colors))
                            .combinedClickable(onClick = { jumpToEvent(reply.eventId) })
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        Box(
                            Modifier
                                .size(width = 2.dp, height = 15.dp)
                                .background(colors.accent.copy(alpha = 0.85f), RoundedCornerShape(1.dp)),
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.Reply,
                            contentDescription = "Jump to original message",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                        if (reply.senderName.isNotEmpty()) {
                            Text(
                                reply.senderName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 1,
                            )
                        }
                        Text(
                            RenderedBody.rendered(reply.snippet, accent = colors.accent),
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (shield != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                        // Level can flip (secondary → red) when a later shield
                        // fetch lands; fade the tint so the warning doesn't pop.
                        val shieldTint by animateColorAsState(
                            targetValue = if (shield.level == MessageItem.ShieldWarning.Level.RED) Color(0xFFFF453A)
                                else colors.textSecondary,
                            animationSpec = if (prefs.reduceMotion) tween(0) else tween(200),
                            label = "shieldTint",
                        )
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = shield.text,
                            tint = shieldTint,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = false, radius = 14.dp),
                                    onClick = { showsShieldInfo = true },
                                ),
                        )
                        MessageContent(
                            message, viewModel, prefs, colors, emoteLoader, mediaRenderers,
                            openProfile, Modifier.weight(1f),
                        )
                    }
                } else {
                    MessageContent(message, viewModel, prefs, colors, emoteLoader, mediaRenderers, openProfile)
                }
                if (prefs.showEventIds && message.eventId != null) {
                    Text(
                        message.eventId!!,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (message.reactions.isNotEmpty()) {
                    ReactionChips(
                        reactions = message.reactions,
                        ownUserId = viewModel.ownUserId,
                        loader = emoteLoader,
                        largerTapTargets = prefs.largerTapTargets,
                        reduceMotion = prefs.reduceMotion,
                        // CustomEmojiStore resolves mxc keys to :shortcodes: (emoji phase).
                        emoteLabel = { null },
                        nameFor = { userId ->
                            membersById[userId]?.name
                                ?: userId.drop(1).takeWhile { it != ':' }
                        },
                        toggle = ::toggleReaction,
                        onAddReaction = onMoreReactions?.let { { it(message) } },
                    )
                }
                val threadInfo = message.threadInfo
                if (threadInfo != null && message.eventId != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(quaternaryFill(colors))
                            .combinedClickable(onClick = { openThread(message.eventId!!) })
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Icon(Icons.Outlined.Forum, null, Modifier.size(15.dp), tint = colors.textPrimary)
                        Text(
                            "${threadInfo.replyCount} ${if (threadInfo.replyCount == 1UL) "reply" else "replies"}",
                            fontSize = 15.sp,
                            color = colors.textPrimary,
                        )
                        Text("›", fontSize = 12.sp, color = colors.textSecondary)
                    }
                }
            }
            // Trailing accessory: failure icon / read receipts / sent check.
            Column(
                verticalArrangement = Arrangement.Bottom,
                modifier = Modifier.align(Alignment.Bottom),
            ) {
                if (message.sendState == MessageItem.SendState.FAILED) {
                    Icon(
                        Icons.Filled.Error,
                        contentDescription = "Failed to send. Retry or delete.",
                        tint = Color(0xFFFF453A),
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = false, radius = 15.dp),
                                onClick = { showsFailedSendOptions = true },
                            ),
                    )
                } else if (prefs.showReadReceipts && message.readReceiptUserIds.isNotEmpty()) {
                    // Readers' avatars sit on the last row they've read.
                    ReadReceiptStack(
                        userIds = message.readReceiptUserIds,
                        membersById = membersById,
                        loader = emoteLoader,
                    )
                } else if (message.isOwn && message.eventId != null &&
                    message.sendState == null && message.id == lastOwnMessageId
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "Sent",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        fun toast(text: String) =
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

        MessageContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            message = message,
            viewModel = viewModel,
            prefs = prefs,
            canDelete = canDelete,
            toggleReaction = ::toggleReaction,
            onMoreReactions = onMoreReactions,
            openProfile = { openProfile(profileTarget) },
            openThread = openThread,
            requestDelete = ::requestDelete,
            requestReport = { showsReportPrompt = true },
            copyText = { text ->
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
            },
            shareText = { text ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
            // Temp-file → share sheet / MediaStore, the iOS shareImage /
            // saveImageToPhotos pair (MessageRow.swift). Every branch reports:
            // silence here reads as "nothing happened", and users re-tap and
            // write duplicates. Toast, not Snackbar — the row also renders
            // inside the thread sheet, where a host wouldn't be on screen.
            shareImage = { item ->
                val loader = mediaLoader
                if (loader != null) scope.launch {
                    val data = loader.fullContent(item.source)
                    if (data == null) {
                        toast("Couldn't download that image")
                        return@launch
                    }
                    // Success is only "the chooser opened", so don't announce it.
                    if (!MediaExport.share(context, data, item.filename.ifEmpty { "image" })) {
                        toast("Couldn't share that image")
                    }
                }
            },
            saveImage = { item ->
                val loader = mediaLoader
                if (loader != null) scope.launch {
                    val data = loader.fullContent(item.source)
                    if (data == null) {
                        toast("Couldn't download that image")
                        return@launch
                    }
                    val saved =
                        MediaExport.saveToGallery(context, data, item.filename.ifEmpty { "image" })
                    toast(if (saved) "Saved to gallery" else "Couldn't save")
                }
            },
        )
    }

    if (showsShieldInfo && shield != null) {
        AlertDialog(
            onDismissRequest = { showsShieldInfo = false },
            icon = { Icon(Icons.Outlined.Lock, null) },
            text = { Text(shield.text) },
            confirmButton = { TextButton(onClick = { showsShieldInfo = false }) { Text("OK") } },
        )
    }

    if (showsFailedSendOptions) {
        AlertDialog(
            onDismissRequest = { showsFailedSendOptions = false },
            title = { Text("This message failed to send.") },
            text = {
                Column {
                    TextButton(onClick = {
                        showsFailedSendOptions = false
                        viewModel.retrySend(message)
                    }) { Text("Retry Send") }
                    TextButton(onClick = {
                        showsFailedSendOptions = false
                        viewModel.redact(message)
                    }) { Text("Delete Message", color = Color(0xFFFF453A)) }
                }
            },
            confirmButton = { TextButton(onClick = { showsFailedSendOptions = false }) { Text("Cancel") } },
        )
    }

    if (showsDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showsDeleteConfirm = false },
            title = { Text("Delete this message?") },
            text = { Text("This removes the message for everyone.") },
            confirmButton = {
                TextButton(onClick = {
                    showsDeleteConfirm = false
                    viewModel.redact(message)
                }) { Text("Delete Message", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { showsDeleteConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showsReportPrompt) {
        AlertDialog(
            onDismissRequest = { showsReportPrompt = false },
            title = { Text("Report Message") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Reports this message to your homeserver administrators.")
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it },
                        placeholder = { Text("Reason (optional)") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showsReportPrompt = false
                    val eventId = message.eventId ?: return@TextButton
                    val reason = reportReason.trim()
                    reportReason = ""
                    scope.launch {
                        val error = viewModel.report(eventId, reason.ifEmpty { null })
                        reportResult = if (error != null) ReportResult(error, isSuccess = false)
                            else ReportResult("Your report was sent to the homeserver administrators.", isSuccess = true)
                    }
                }) { Text("Report", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { showsReportPrompt = false }) { Text("Cancel") } },
        )
    }

    reportResult?.let { result ->
        AlertDialog(
            onDismissRequest = { reportResult = null },
            title = { Text(if (result.isSuccess) "Report Sent" else "Couldn't Report") },
            text = { Text(result.message) },
            confirmButton = { TextButton(onClick = { reportResult = null }) { Text("OK") } },
        )
    }
}

private data class ReportResult(val message: String, val isSuccess: Boolean)

@Composable
private fun RowGutter(
    message: MessageItem,
    prefs: Preferences.Snapshot,
    effectiveName: String,
    effectiveAvatarUrl: String?,
    emoteLoader: EmoteAssetLoader?,
    onOpenProfile: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    if (message.showsHeader) {
        if (prefs.showAvatarsInTimeline) {
            TimelineAvatarView(
                name = effectiveName,
                size = GUTTER_WIDTH,
                avatarUrl = effectiveAvatarUrl,
                loader = emoteLoader,
                modifier = Modifier.combinedClickable(
                    onClick = onOpenProfile,
                    onClickLabel = "View profile of $effectiveName",
                ),
            )
        } else {
            // Avatars hidden: keep the gutter width so rows stay aligned.
            Box(Modifier.size(GUTTER_WIDTH, 1.dp))
        }
    } else {
        // Touch has no hover; `alwaysShowTimestamps` is the only way the time
        // shows inline (it's in the context menu otherwise).
        Text(
            if (prefs.alwaysShowTimestamps) hourMinuteText(message.timestamp, prefs.use24HourTime) else "",
            fontSize = 11.sp,
            color = colors.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(GUTTER_WIDTH),
        )
    }
}

@Composable
private fun RowHeader(
    message: MessageItem,
    prefs: Preferences.Snapshot,
    effectiveName: String,
    onOpenProfile: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            effectiveName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = senderColor(message.sender, prefs.coloredSenderNames, colors),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenProfile,
                    onClickLabel = "View profile",
                ),
        )
        // Pronouns tag between the name and timestamp (iOS MessageRow.header).
        val pronounsStore = LocalPronounsStore.current
        // The cache emits once per landed fetch for ANY user; without a
        // derivedStateOf gate that emission recomposes every visible header.
        // Resolving this sender's pronouns inside derivedStateOf means only a
        // change to THIS sender's value re-runs the tag — an unrelated user's
        // fetch is dropped by the equality check on the derived String?.
        val cache = pronounsStore?.cache?.collectAsStateWithLifecycle()
        val pronouns = remember(pronounsStore, message.sender) {
            derivedStateOf {
                // Touch the collected value so the fetch-on-miss still triggers
                // and the derivation re-evaluates when the cache map changes.
                cache?.value
                pronounsStore?.pronouns(message.sender)
            }
        }.value
        pronouns?.let { pronouns ->
            Text(
                pronouns,
                fontSize = 12.sp,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Text(
            timestampText(message.timestamp, prefs.use24HourTime),
            fontSize = 12.sp,
            color = colors.textTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageContent(
    message: MessageItem,
    viewModel: TimelineViewModel,
    prefs: Preferences.Snapshot,
    colors: DiscourseColors,
    emoteLoader: EmoteAssetLoader?,
    mediaRenderers: TimelineMediaRenderers,
    openProfile: (ProfileTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Intercepts taps on `matrix.to` user links (mention pills) to open the
    // member's profile in-app instead of launching the browser. Other links
    // fall through to the system (iOS OpenURLAction parity).
    val defaultHandler = LocalUriHandler.current
    val mentionHandler = remember(message.mentions, defaultHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val userId = MentionParser.userIdFromMatrixTo(uri)
                if (userId != null) {
                    val name = message.mentions.firstOrNull { it.userId == userId }?.text
                    openProfile(ProfileTarget(userId = userId, displayName = name))
                } else {
                    runCatching { defaultHandler.openUri(uri) }
                }
            }
        }
    }

    // Clamped copy of the preference; guards out-of-range persisted values.
    val fontScale = prefs.chatFontScale.coerceIn(0.8f, 1.4f)
    // Hoisted out of the per-recompose path: the base body style only changes
    // when the font scale or theme colors change, not on every store emission.
    val bodyStyle = remember(fontScale, colors.textPrimary) {
        TextStyle(fontSize = (17f * fontScale).sp, color = colors.textPrimary)
    }
    // The " (edited)" run (or the empty-string placeholder) is allocated once
    // per message-edit state, not per recompose — every visible row rebuilt a
    // fresh AnnotatedString on each shared-store emission otherwise.
    val editedSuffix = remember(message.isEdited, colors.textTertiary) {
        editedSuffix(message, colors)
    }

    // iOS effectiveEmotes: merge CustomEmojiStore.knownEmotes(body) under the
    // declared inline emotes (declared URLs win) so custom emotes still render
    // for messages whose HTML never arrived — stripped formatted bodies, sends
    // before the pack loaded, plain-only clients.
    val customEmoji = LocalCustomEmojiStore.current
    // Observed so plain-body tokens resolve once the packs land.
    val shortcodes = customEmoji?.byShortcode?.collectAsStateWithLifecycle()?.value
    val rawBody = when (val k = message.kind) {
        is MessageItem.Kind.Text -> k.body
        is MessageItem.Kind.Notice -> k.body
        is MessageItem.Kind.Emote -> "${message.displayName} ${k.body}"
        else -> null
    }
    val emotes = remember(message.id, message.inlineEmotes, rawBody, shortcodes) {
        if (customEmoji == null || rawBody == null) message.inlineEmotes
        else customEmoji.knownEmotes(rawBody) + message.inlineEmotes
    }

    CompositionLocalProvider(LocalUriHandler provides mentionHandler) {
        Box(modifier) {
            when (val kind = message.kind) {
                is MessageItem.Kind.Text -> {
                    val body = kind.body
                    // ICU BreakIterator scan is O(body) — cache it per body so a
                    // shared-store emission doesn't re-scan every visible row.
                    val jumbo = remember(body, prefs.jumboEmoji) {
                        prefs.jumboEmoji && isJumboEmoji(body)
                    }
                    if (body.startsWith(">") || body.contains("\n>")) {
                        // Markdown blockquotes: `>`-prefixed lines render as a quote.
                        QuotedBodyView(
                            rawBody = body,
                            emotes = emotes,
                            loader = emoteLoader,
                            jumboEmoji = prefs.jumboEmoji,
                            fontScale = fontScale,
                            style = bodyStyle,
                        )
                    } else if (emotes.isEmpty()) {
                        if (jumbo) {
                            SelectionContainer {
                                Text(
                                    buildAnnotatedString {
                                        withSpan(SpanStyle(fontSize = (44f * fontScale).sp)) { append(body) }
                                        append(editedSuffix)
                                    },
                                    color = colors.textPrimary,
                                )
                            }
                        } else {
                            SelectionContainer {
                                Text(
                                    RenderedBody.rendered(body, message.mentions, viewModel.ownUserId, colors.accent)
                                        + editedSuffix,
                                    style = bodyStyle,
                                )
                            }
                        }
                    } else {
                        EmoteBodyText(
                            body = body,
                            emotes = emotes,
                            loader = emoteLoader,
                            style = bodyStyle,
                            suffix = editedSuffix,
                            jumboEmoji = prefs.jumboEmoji,
                            fontScale = fontScale,
                        )
                    }
                }
                is MessageItem.Kind.Notice -> {
                    val noticeStyle = bodyStyle.copy(color = colors.textSecondary)
                    if (emotes.isEmpty()) {
                        SelectionContainer {
                            Text(
                                RenderedBody.rendered(kind.body, message.mentions, viewModel.ownUserId, colors.accent)
                                    + editedSuffix,
                                style = noticeStyle,
                            )
                        }
                    } else {
                        EmoteBodyText(
                            body = kind.body,
                            emotes = emotes,
                            loader = emoteLoader,
                            style = noticeStyle,
                            suffix = editedSuffix,
                            jumboEmoji = prefs.jumboEmoji,
                            fontScale = fontScale,
                        )
                    }
                }
                is MessageItem.Kind.Emote -> {
                    val emoteStyle = bodyStyle.copy(fontStyle = FontStyle.Italic)
                    val body = "${message.displayName} ${kind.body}"
                    if (emotes.isEmpty()) {
                        SelectionContainer {
                            Text(RenderedBody.rendered(body, accent = colors.accent), style = emoteStyle)
                        }
                    } else {
                        EmoteBodyText(
                            body = body,
                            emotes = emotes,
                            loader = emoteLoader,
                            style = emoteStyle,
                            jumboEmoji = prefs.jumboEmoji,
                            fontScale = fontScale,
                        )
                    }
                }
                is MessageItem.Kind.Image -> mediaRenderers.image(message, kind.item)
                is MessageItem.Kind.Video -> mediaRenderers.video(message, kind.item)
                is MessageItem.Kind.Poll -> mediaRenderers.poll(message, kind.item)
                is MessageItem.Kind.Audio -> mediaRenderers.audio(message, kind.item)
                is MessageItem.Kind.Location -> {
                    AttachmentChip(
                        label = kind.body.ifEmpty { "Shared location" },
                        icon = { Icon(Icons.Outlined.Place, null, Modifier.size(18.dp), tint = colors.textSecondary) },
                        onClick = {
                            // geo: on Android where iOS opens Apple Maps.
                            val coords = kind.geoUri.removePrefix("geo:").split(";").first()
                            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords"))
                            try {
                                context.startActivity(geo)
                            } catch (_: ActivityNotFoundException) {
                                runCatching {
                                    context.startActivity(Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://maps.google.com/?q=$coords"),
                                    ))
                                }
                            }
                        },
                    )
                }
                is MessageItem.Kind.Media -> {
                    AttachmentChip(
                        label = kind.label,
                        icon = {
                            Icon(
                                materialIconFor(kind.systemImage),
                                null,
                                Modifier.size(18.dp),
                                tint = colors.textSecondary,
                            )
                        },
                    )
                }
                MessageItem.Kind.Redacted -> Text(
                    "Message deleted",
                    style = bodyStyle.copy(color = colors.textTertiary, fontStyle = FontStyle.Italic),
                )
                MessageItem.Kind.UnableToDecrypt -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(15.dp), tint = colors.textSecondary)
                    Text("Waiting for this message to decrypt…", style = bodyStyle.copy(color = colors.textSecondary))
                }
                is MessageItem.Kind.Unsupported -> Text(kind.text, style = bodyStyle.copy(color = colors.textSecondary))
            }
        }
    }
}

@Composable
private fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    message: MessageItem,
    viewModel: TimelineViewModel,
    prefs: Preferences.Snapshot,
    canDelete: Boolean,
    toggleReaction: (String) -> Unit,
    onMoreReactions: ((MessageItem) -> Unit)?,
    openProfile: () -> Unit,
    openThread: (String) -> Unit,
    requestDelete: () -> Unit,
    requestReport: () -> Unit,
    copyText: (String) -> Unit,
    shareText: (String) -> Unit,
    shareImage: (ImageItem) -> Unit,
    saveImage: (ImageItem) -> Unit,
) {
    val destructive = Color(0xFFFF453A)
    val context = LocalContext.current
    // Learned from usage. Custom-emote keys are excluded at record time, but
    // filter defensively — the palette only draws unicode emoji. Computed here,
    // behind the menu's own composition, so scroll-time rows never read
    // SharedPreferences: the menu subtree only composes once expanded.
    val quickReactions = remember(expanded) {
        if (!expanded) emptyList()
        else ReactionUsage.top(context, 5).filter { !it.startsWith("mxc://") }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Most-used reactions as a palette row.
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            for (emoji in quickReactions) {
                Text(
                    emoji,
                    fontSize = 22.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .combinedClickable(onClick = {
                            onDismiss()
                            toggleReaction(emoji)
                        })
                        .padding(6.dp),
                )
            }
        }
        // The gutter timestamp is header-rows-only; this is how touch gets it.
        DropdownMenuItem(
            text = { Text("Sent at ${timestampText(message.timestamp, prefs.use24HourTime)}") },
            leadingIcon = { Icon(Icons.Outlined.AccessTime, null) },
            enabled = false,
            onClick = {},
        )
        if (onMoreReactions != null) {
            DropdownMenuItem(
                text = { Text("More Reactions…") },
                leadingIcon = { Icon(Icons.Outlined.AddReaction, null) },
                onClick = {
                    onDismiss()
                    onMoreReactions(message)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("View Profile") },
            leadingIcon = { Icon(Icons.Outlined.Person, null) },
            onClick = {
                onDismiss()
                openProfile()
            },
        )
        if (message.canBeRepliedTo) {
            DropdownMenuItem(
                text = { Text("Reply") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Reply, null) },
                onClick = {
                    onDismiss()
                    viewModel.replyTarget.value = message
                },
            )
        }
        if (message.isOwn && message.eventId != null && message.kind is MessageItem.Kind.Text) {
            DropdownMenuItem(
                text = { Text("Edit Message") },
                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                onClick = {
                    onDismiss()
                    viewModel.replyTarget.value = null
                    viewModel.editTarget.value = message
                },
            )
        }
        if (viewModel.mode == TimelineViewModel.Mode.Live && message.eventId != null) {
            DropdownMenuItem(
                text = { Text("Reply in Thread") },
                leadingIcon = { Icon(Icons.Outlined.Forum, null) },
                onClick = {
                    onDismiss()
                    openThread(message.eventId!!)
                },
            )
        }
        HorizontalDivider()
        (message.kind as? MessageItem.Kind.Text)?.let { kind ->
            DropdownMenuItem(
                text = { Text("Copy Text") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = {
                    onDismiss()
                    copyText(kind.body)
                },
            )
            DropdownMenuItem(
                text = { Text("Share…") },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = {
                    onDismiss()
                    shareText(kind.body)
                },
            )
        }
        (message.kind as? MessageItem.Kind.Image)?.let { kind ->
            DropdownMenuItem(
                text = { Text("Share Image…") },
                leadingIcon = { Icon(Icons.Outlined.Share, null) },
                onClick = {
                    onDismiss()
                    shareImage(kind.item)
                },
            )
            DropdownMenuItem(
                text = { Text("Save Image") },
                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                onClick = {
                    onDismiss()
                    saveImage(kind.item)
                },
            )
        }
        if (message.eventId != null) {
            DropdownMenuItem(
                text = { Text("Copy Event ID") },
                leadingIcon = { Icon(Icons.Outlined.Tag, null) },
                onClick = {
                    onDismiss()
                    copyText(message.eventId!!)
                },
            )
        }
        if (message.isOwn) {
            HorizontalDivider()
            if (message.sendState == MessageItem.SendState.FAILED) {
                DropdownMenuItem(
                    text = { Text("Retry Send") },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                    onClick = {
                        onDismiss()
                        viewModel.retrySend(message)
                    },
                )
            }
            if (viewModel.canCancelSend(message)) {
                DropdownMenuItem(
                    text = { Text("Cancel Upload") },
                    leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                    onClick = {
                        onDismiss()
                        viewModel.cancelSend(message)
                    },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text("Delete Message", color = destructive) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = destructive) },
                    onClick = {
                        onDismiss()
                        requestDelete()
                    },
                )
            }
        } else if (message.eventId != null) {
            HorizontalDivider()
            // Moderators can delete other people's messages too.
            if (canDelete) {
                DropdownMenuItem(
                    text = { Text("Delete Message", color = destructive) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = destructive) },
                    onClick = {
                        onDismiss()
                        requestDelete()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Report Message…", color = destructive) },
                leadingIcon = { Icon(Icons.Outlined.Flag, null, tint = destructive) },
                onClick = {
                    onDismiss()
                    requestReport()
                },
            )
        }
    }
}

/** Labeled chip used for non-inline attachments and locations. */
@Composable
fun AttachmentChip(
    label: String,
    icon: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(quaternaryFill(colors))
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        icon()
        Text(label, fontSize = 15.sp, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

/** SF Symbol name (kept in the model for iOS parity) → Material icon. */
fun materialIconFor(systemImage: String) = when (systemImage) {
    "photo.on.rectangle" -> Icons.Outlined.Image
    "location.fill" -> Icons.Outlined.Place
    "waveform" -> Icons.Outlined.GraphicEq
    "music.note" -> Icons.Outlined.MusicNote
    "doc.text" -> Icons.Outlined.Article
    else -> Icons.Outlined.Description // "doc" and anything unknown
}

/**
 * Renders a message body with markdown blockquotes: consecutive `>`-prefixed
 * lines become an indented, bar-accented, secondary block; other lines render
 * normally (with custom emotes). Splits into blocks so quotes and regular
 * text can interleave.
 */
@Composable
fun QuotedBodyView(
    rawBody: String,
    emotes: Map<String, String>,
    loader: EmoteAssetLoader?,
    jumboEmoji: Boolean,
    fontScale: Float,
    style: TextStyle,
) {
    val colors = LocalDiscourseColors.current
    val blocks = remember(rawBody) { quoteBlocks(rawBody) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            if (block.isQuote) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.accent.copy(alpha = 0.6f), RoundedCornerShape(1.5.dp)),
                    )
                    QuoteSegment(block.text, emotes, loader, jumboEmoji, fontScale,
                        style.copy(color = colors.textSecondary))
                }
            } else {
                QuoteSegment(block.text, emotes, loader, jumboEmoji, fontScale, style)
            }
        }
    }
}

@Composable
private fun QuoteSegment(
    text: String,
    emotes: Map<String, String>,
    loader: EmoteAssetLoader?,
    jumboEmoji: Boolean,
    fontScale: Float,
    style: TextStyle,
) {
    val colors = LocalDiscourseColors.current
    val present = emotes.filterKeys { text.contains(it) }
    if (present.isEmpty()) {
        SelectionContainer {
            Text(RenderedBody.rendered(text, accent = colors.accent), style = style)
        }
    } else {
        EmoteBodyText(
            body = text,
            emotes = present,
            loader = loader,
            style = style,
            jumboEmoji = jumboEmoji,
            fontScale = fontScale,
        )
    }
}

private data class QuoteBlock(val text: String, val isQuote: Boolean)

private fun quoteBlocks(rawBody: String): List<QuoteBlock> {
    val result = mutableListOf<QuoteBlock>()
    for (line in rawBody.split("\n")) {
        val isQuote = line.startsWith(">")
        val text = if (isQuote) line.dropWhile { it == '>' || it == ' ' } else line
        if (result.isNotEmpty() && result.last().isQuote == isQuote) {
            result[result.size - 1] = result.last().copy(text = result.last().text + "\n" + text)
        } else {
            result.add(QuoteBlock(text, isQuote))
        }
    }
    return result
}

/**
 * Up to three overlapping reader avatars (plus an overflow count), pinned to
 * the trailing edge of the last row each user has read. Tap opens the reader
 * list as a sheet (it overflows a popover once a few people have read).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadReceiptStack(
    userIds: List<String>,
    membersById: Map<String, TimelineViewModel.MemberItem>,
    loader: EmoteAssetLoader?,
) {
    val colors = LocalDiscourseColors.current
    var showsReaders by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy((-5).dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showsReaders = true },
                onClickLabel = "Read by ${userIds.size} ${if (userIds.size == 1) "person" else "people"}",
            )
            // ≥28dp tap area biased downward so it doesn't sit over the text above.
            .padding(top = 4.dp, bottom = 10.dp),
    ) {
        for (userId in userIds.take(3)) {
            val member = membersById[userId]
            TimelineAvatarView(
                name = member?.name ?: userId.drop(1),
                size = 15.dp,
                avatarUrl = member?.avatarUrl,
                loader = loader,
                modifier = Modifier.border(1.dp, colors.bgApp, CircleShape),
            )
        }
        if (userIds.size > 3) {
            Text(
                "+${userIds.size - 3}",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
    if (showsReaders) {
        ModalBottomSheet(onDismissRequest = { showsReaders = false }) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    "Read up to here by",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                for (userId in userIds) {
                    val member = membersById[userId]
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(44.dp),
                    ) {
                        TimelineAvatarView(
                            name = member?.name ?: userId.drop(1),
                            size = 32.dp,
                            avatarUrl = member?.avatarUrl,
                            loader = loader,
                        )
                        Text(
                            member?.name ?: userId,
                            fontSize = 16.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Reaction chips under a message; tap toggles your reaction. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ReactionChips(
    reactions: List<MessageReaction>,
    ownUserId: String,
    /** Renders `mxc://` reaction keys (custom emoji) as images. */
    loader: EmoteAssetLoader?,
    /** Accessibility preference: pad the hit target a little more. */
    largerTapTargets: Boolean = false,
    /** Gates the chip enter/count animations (iOS `prefs.reduceMotion`). */
    reduceMotion: Boolean = false,
    /** Resolves an `mxc://` key to its `:shortcode:` for labels. */
    emoteLabel: (String) -> String? = { null },
    /** Resolves a user ID to a display name for the sender list. */
    nameFor: (String) -> String = { it },
    toggle: (String) -> Unit,
    /** Opens the reaction picker from the trailing "+" chip. */
    onAddReaction: (() -> Unit)? = null,
) {
    val colors = LocalDiscourseColors.current

    // The human-readable form of a reaction key — the key itself for unicode
    // emoji, the `:shortcode:` for custom-emote keys.
    fun label(key: String): String =
        if (key.startsWith("mxc://")) (emoteLabel(key) ?: "custom emoji") else key

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(if (largerTapTargets) 10.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 2.dp),
    ) {
        for (reaction in reactions) {
            // Keyed so per-chip animation state survives list reordering, and so
            // a chip that leaves the list is torn down (its slot collapses)
            // rather than reused for the next reaction's identity.
            key(reaction.key) {
            val mine = reaction.includesOwn(ownUserId)
            var sendersExpanded by remember(reaction.key) { mutableStateOf(false) }
            // Scale-from-leading + fade when a chip first appears (iOS
            // `.transition(.scale(anchor: .leading).combined(with: .opacity))`).
            // Starts hidden, flips visible on first composition.
            val enterState = remember {
                MutableTransitionState(reduceMotion).apply { targetState = true }
            }
            AnimatedVisibility(
                visibleState = enterState,
                enter = if (reduceMotion) fadeIn(tween(0)) else
                    scaleIn(
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                        transformOrigin = TransformOrigin(0f, 0.5f),
                        initialScale = 0.6f,
                    ) + fadeIn(tween(220)),
                exit = fadeOut(tween(0)),
            ) {
            Box {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (mine) colors.accent.copy(alpha = 0.2f) else quaternaryFill(colors))
                        .then(if (mine) Modifier.border(1.dp, colors.accent, CircleShape) else Modifier)
                        .combinedClickable(
                            onClick = { toggle(reaction.key) },
                            // Touch can't hover: long-press surfaces the sender list.
                            onLongClick = { sendersExpanded = true },
                            onClickLabel = "${reaction.count} ${if (reaction.count == 1) "reaction" else "reactions"}, ${label(reaction.key)}",
                        )
                        .padding(
                            horizontal = if (largerTapTargets) 11.dp else 8.dp,
                            vertical = if (largerTapTargets) 6.dp else 3.dp,
                        ),
                ) {
                    if (reaction.key.startsWith("mxc://")) {
                        EmoteImageView(url = reaction.key, size = 17.dp, loader = loader,
                            contentDescription = label(reaction.key))
                    } else {
                        Text(reaction.key, fontSize = 14.sp)
                    }
                    // Numeric roll when the count changes: new value slides up on
                    // an increment, down on a decrement (iOS `.numericText()`).
                    AnimatedContent(
                        targetState = reaction.count,
                        transitionSpec = {
                            if (reduceMotion) {
                                (fadeIn(tween(0)) togetherWith fadeOut(tween(0)))
                                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                            } else {
                                val up = targetState > initialState
                                val dir = if (up) 1 else -1
                                (slideInVertically(tween(200)) { h -> dir * h } + fadeIn(tween(200)))
                                    .togetherWith(
                                        slideOutVertically(tween(200)) { h -> -dir * h } + fadeOut(tween(200)),
                                    )
                                    .using(SizeTransform(clip = false))
                            }
                        },
                        label = "reactionCount",
                    ) { count ->
                        Text(
                            count.toString(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                        )
                    }
                }
                DropdownMenu(expanded = sendersExpanded, onDismissRequest = { sendersExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Reacted with ${label(reaction.key)}", fontWeight = FontWeight.SemiBold) },
                        enabled = false,
                        onClick = {},
                    )
                    for (userId in reaction.senders) {
                        DropdownMenuItem(text = { Text(nameFor(userId)) }, enabled = false, onClick = {})
                    }
                    DropdownMenuItem(
                        text = { Text(if (mine) "Remove Your Reaction" else "Add Reaction") },
                        onClick = {
                            sendersExpanded = false
                            toggle(reaction.key)
                        },
                    )
                }
            }
            } // AnimatedVisibility
            } // key(reaction.key)
        }
        if (onAddReaction != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(23.dp)
                    .clip(CircleShape)
                    .background(quaternaryFill(colors))
                    .combinedClickable(onClick = onAddReaction, onClickLabel = "Add reaction")
                    .padding(horizontal = 8.dp),
            ) {
                Text("+", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textSecondary)
            }
        }
    }
}

/**
 * Avatar circle: image when the loader has one, initials on the sender-hash
 * color otherwise. Timeline-local port of iOS RoomAvatarView; swap for the
 * shared component when the room-list slice's version lands.
 */
@Composable
fun TimelineAvatarView(
    name: String,
    size: Dp,
    avatarUrl: String?,
    loader: EmoteAssetLoader?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pixelSize = with(density) { size.toPx() }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(avatarUrl, loader) {
        if (avatarUrl == null || loader == null) {
            image = null
            return@LaunchedEffect
        }
        image = loader.cachedImage(mxcUrl = avatarUrl, pixelSize = pixelSize)
            ?: loader.avatar(mxcUrl = avatarUrl, pixelSize = pixelSize)
    }
    val display = image
        ?: avatarUrl?.let { loader?.cachedImage(mxcUrl = it, pixelSize = pixelSize) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape),
    ) {
        if (display != null) {
            Image(
                bitmap = display.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            val base = hashColor(name)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .background(Brush.verticalGradient(listOf(base, darken(base, 0.15f)))),
            ) {
                Text(
                    initials(name),
                    color = Color.White,
                    fontSize = (size.value * 0.42f).sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun initials(name: String): String {
    val cleaned = name.trim { it in "#@!+ " }
    val letters = cleaned.split(" ").filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull() }
    return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase()
}

private fun darken(color: Color, amount: Float): Color = Color(
    red = color.red * (1 - amount),
    green = color.green * (1 - amount),
    blue = color.blue * (1 - amount),
    alpha = color.alpha,
)

/** iOS palette [.blue,.indigo,.purple,.pink,.red,.orange,.teal,.green]. */
private val senderPalette = listOf(
    Color(0xFF3B82F6), Color(0xFF6366F1), Color(0xFFA855F7), Color(0xFFEC4899),
    Color(0xFFEF4444), Color(0xFFF97316), Color(0xFF14B8A6), Color(0xFF22C55E),
)

private fun hashColor(seed: String): Color {
    var hash = 0
    for (codePoint in seed.codePoints()) hash = hash * 31 + codePoint
    return senderPalette[Math.floorMod(hash, senderPalette.size)]
}

fun senderColor(sender: String, coloredSenderNames: Boolean, colors: DiscourseColors): Color =
    if (coloredSenderNames) hashColor(sender) else colors.textPrimary

/** `.quaternary.opacity(0.5)`-style neutral fill, theme-aware. */
fun quaternaryFill(colors: DiscourseColors): Color =
    colors.textPrimary.copy(alpha = if (colors.isDark) 0.09f else 0.07f)

private fun editedSuffix(message: MessageItem, colors: DiscourseColors): AnnotatedString =
    if (message.isEdited) {
        buildAnnotatedString {
            withSpan(SpanStyle(fontSize = 12.sp, color = colors.textTertiary)) { append(" (edited)") }
        }
    } else AnnotatedString("")

private fun AnnotatedString.Builder.withSpan(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val index = pushStyle(style)
    block()
    pop(index)
}

/**
 * True for short messages that are nothing but emoji (and whitespace).
 * Excludes digits, `#` and `*` (emoji-adjacent scalars below 0x2380) so "123" stays
 * text. Uses android.icu emoji properties (the analogue of the iOS Unicode
 * scalar properties).
 */
fun isJumboEmoji(body: String): Boolean {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return false
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    var clusters = 0
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        val cluster = trimmed.substring(start, end)
        if (!cluster.all { it.isWhitespace() }) {
            clusters++
            if (clusters > 8) return false
            val first = cluster.codePointAt(0)
            val hasVariation = cluster.codePoints().anyMatch { it == 0xFE0F }
            val isEmoji = UCharacter.hasBinaryProperty(first, UProperty.EMOJI_PRESENTATION) ||
                hasVariation ||
                (UCharacter.hasBinaryProperty(first, UProperty.EMOJI) && first > 0x2380)
            if (!isEmoji) return false
        }
        start = end
        end = iterator.next()
    }
    return clusters > 0
}

// MARK: Timestamps

/** Built once; a formatter per row render hits locale lookup (iOS parity). */
private object TimeFormats {
    val hourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    val hourMinute24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    val earlier: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault())
    val earlier24: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
    val dayDivider: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
}

fun hourMinuteText(epochMillis: Long, use24Hour: Boolean): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return time.format(if (use24Hour) TimeFormats.hourMinute24 else TimeFormats.hourMinute)
}

/** Time-only for today; month + day + time for earlier days. */
fun timestampText(epochMillis: Long, use24Hour: Boolean): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now()) {
        time.format(if (use24Hour) TimeFormats.hourMinute24 else TimeFormats.hourMinute)
    } else {
        time.format(if (use24Hour) TimeFormats.earlier24 else TimeFormats.earlier)
    }
}

fun dayDividerText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TimeFormats.dayDivider)

// MARK: Rendered body

/**
 * Renders inline markdown plus bare-URL detection; links come out
 * accent-tinted, underlined, and clickable (through LocalUriHandler, which
 * MessageContent overrides to intercept mention links). Cached per raw
 * body — parsing + link detection ran per row per render otherwise.
 */
object RenderedBody {
    private val cache = LruCache<String, AnnotatedString>(500)

    private data class StyleRun(val start: Int, val end: Int, val style: SpanStyle)
    private data class LinkRun(val start: Int, val end: Int, val url: String, val isMention: Boolean, val isSelf: Boolean)

    fun rendered(
        body: String,
        mentions: List<MentionRef> = emptyList(),
        ownUserId: String? = null,
        accent: Color,
    ): AnnotatedString {
        // Mentions/self-highlight/accent vary the styling, so they're in the key.
        val key = buildString {
            append(body)
            append('\u0001').append(ownUserId ?: "")
            append('\u0001').append(accent.value.toString())
            for (mention in mentions) append('\u0002').append(mention.userId).append('=').append(mention.text)
        }
        synchronized(cache) { cache.get(key) }?.let { return it }

        val plain = StringBuilder()
        val styles = mutableListOf<StyleRun>()
        val links = mutableListOf<LinkRun>()
        parseInline(body, plain, styles, links)
        val text = plain.toString()

        // Turn mention display text (carried plainly in the body by clients
        // that put the matrix.to link only in the HTML) into tappable pills.
        for (mention in mentions) {
            val index = text.indexOf(mention.text)
            if (index < 0) continue
            val end = index + mention.text.length
            if (links.any { it.start < end && index < it.end }) continue
            links.add(LinkRun(index, end, "https://matrix.to/#/${mention.userId}",
                isMention = true, isSelf = mention.userId == ownUserId))
        }

        // Bare URLs the markdown pass missed. The rendered characters differ
        // from `body` (syntax stripped), so detect over the rendered text.
        val matcher = android.util.Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            if (links.any { it.start < end && start < it.end }) continue
            val raw = text.substring(start, end)
            // Patterns.WEB_URL also matches bare hostnames ("foo.bar" prose);
            // require a scheme or www. like the iOS data detector's output.
            if (!raw.startsWith("http://") && !raw.startsWith("https://") && !raw.startsWith("www.")) continue
            val url = if (raw.startsWith("www.")) "https://$raw" else raw
            links.add(LinkRun(start, end, url, isMention = false, isSelf = false))
        }

        val result = buildAnnotatedString {
            append(text)
            for (run in styles) addStyle(run.style, run.start, run.end)
            // One styling pass over every link, authored or detected. Mentions
            // render as tinted pills, not underlined links; a mention of the
            // current user gets a stronger highlight.
            for (link in links) {
                val style = if (link.isMention) {
                    SpanStyle(
                        color = accent,
                        background = accent.copy(alpha = if (link.isSelf) 0.30f else 0.15f),
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                }
                addLink(LinkAnnotation.Url(link.url, TextLinkStyles(style = style)), link.start, link.end)
            }
        }
        synchronized(cache) { cache.put(key, result) }
        return result
    }

    /**
     * Minimal inline-markdown pass matching the iOS
     * `inlineOnlyPreservingWhitespace` behavior: `code`, **bold**, __bold__,
     * *italic*, _italic_, ~~strike~~, [text](url), backslash escapes.
     * Unmatched markers fall through as literal text.
     */
    private fun parseInline(
        source: String,
        out: StringBuilder,
        styles: MutableList<StyleRun>,
        links: MutableList<LinkRun>,
    ) {
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' && i + 1 < source.length && source[i + 1] in "\\`*_~[]()#>!" -> {
                    out.append(source[i + 1])
                    i += 2
                }
                c == '`' -> {
                    val close = source.indexOf('`', i + 1)
                    if (close > i + 1) {
                        val start = out.length
                        out.append(source, i + 1, close)
                        styles.add(StyleRun(start, out.length, SpanStyle(fontFamily = FontFamily.Monospace)))
                        i = close + 1
                    } else {
                        out.append(c); i++
                    }
                }
                matchesDelimiter(source, i, "**") -> i = emphasis(source, i, "**",
                    SpanStyle(fontWeight = FontWeight.Bold), out, styles, links)
                matchesDelimiter(source, i, "__") -> i = emphasis(source, i, "__",
                    SpanStyle(fontWeight = FontWeight.Bold), out, styles, links)
                matchesDelimiter(source, i, "~~") -> i = emphasis(source, i, "~~",
                    SpanStyle(textDecoration = TextDecoration.LineThrough), out, styles, links)
                matchesDelimiter(source, i, "*") -> i = emphasis(source, i, "*",
                    SpanStyle(fontStyle = FontStyle.Italic), out, styles, links)
                matchesDelimiter(source, i, "_") -> i = emphasis(source, i, "_",
                    SpanStyle(fontStyle = FontStyle.Italic), out, styles, links)
                c == '[' -> {
                    val closeBracket = source.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < source.length && source[closeBracket + 1] == '(') {
                        val closeParen = source.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket + 1) {
                            val label = source.substring(i + 1, closeBracket)
                            val url = source.substring(closeBracket + 2, closeParen).trim()
                            val start = out.length
                            parseInline(label, out, styles, links)
                            if (url.isNotEmpty()) {
                                links.add(LinkRun(start, out.length, url, isMention = false, isSelf = false))
                            }
                            i = closeParen + 1
                            continue
                        }
                    }
                    out.append(c); i++
                }
                else -> {
                    out.append(c); i++
                }
            }
        }
    }

    /** A delimiter opens only before non-space content (so "2 * 3" stays text). */
    private fun matchesDelimiter(source: String, index: Int, delimiter: String): Boolean {
        if (!source.startsWith(delimiter, index)) return false
        val next = index + delimiter.length
        return next < source.length && !source[next].isWhitespace() && !source.startsWith(delimiter, next)
    }

    private fun emphasis(
        source: String,
        index: Int,
        delimiter: String,
        style: SpanStyle,
        out: StringBuilder,
        styles: MutableList<StyleRun>,
        links: MutableList<LinkRun>,
    ): Int {
        val close = source.indexOf(delimiter, index + delimiter.length)
        if (close < 0 || source[close - 1].isWhitespace()) {
            out.append(source, index, index + delimiter.length)
            return index + delimiter.length
        }
        val start = out.length
        parseInline(source.substring(index + delimiter.length, close), out, styles, links)
        styles.add(StyleRun(start, out.length, style))
        return close + delimiter.length
    }
}
