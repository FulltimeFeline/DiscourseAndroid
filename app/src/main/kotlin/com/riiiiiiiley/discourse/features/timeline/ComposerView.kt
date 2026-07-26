package com.riiiiiiiley.discourse.features.timeline

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.roomlist.RoomAvatarView
import com.riiiiiiiley.discourse.features.stickers.StickerPickerView
import com.riiiiiiiley.discourse.features.timeline.media.WaveformBars
import com.riiiiiiiley.discourse.models.MentionRef
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.RoomSummary
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// Port of ComposerView.swift (the iOS touchBar branch). Same bar anatomy —
// attach bubble, field bubble, expression toggle, mic/send bubble — plus the
// mention/`:token:` autocomplete popups, appendix banners, hold-to-record
// voice gesture, and the keyboard-replacement expression panel.

/** A picked mention: the `@user:server` token shown in the field, which is
 *  also the id it resolves to. `send()` turns the token into a real mention. */
private data class ChosenMention(
    val token: String,
    val userId: String,
)

/** One row of the `:token:` autocomplete: a custom emote or a unicode emoji
 *  matched by its derived shortcode. */
private sealed class EmojiSuggestion {
    data class Custom(val emote: CustomEmojiStore.Emote) : EmojiSuggestion()
    data class Unicode(val emoji: String, val shortcode: String) : EmojiSuggestion()

    val id: String
        get() = when (this) {
            is Custom -> "custom/${emote.id}"
            is Unicode -> "unicode/$shortcode"
        }

    val label: String
        get() = when (this) {
            is Custom -> emote.token
            is Unicode -> ":$shortcode:"
        }
}

/**
 * Typing the closing colon of an exact unicode shortcode (":pleading_face:")
 * swaps it for the emoji. Custom emotes stay as tokens. Returns null when the
 * tail isn't a complete known token.
 */
internal fun autoReplacingTrailingShortcode(text: String): String? {
    if (!text.endsWith(":") || text.length < 4) return null
    val closing = text.length - 1
    var start = closing
    while (start > 0 && CustomEmojiStore.isShortcodeCharacter(text[start - 1])) start--
    if (start >= closing || start <= 0 || text[start - 1] != ':') return null
    val opening = start - 1
    // Word start only — "10:30:" must survive.
    if (opening > 0 && !text[opening - 1].isWhitespace()) return null
    val emoji = EmojiShortcodes.byShortcode[text.substring(start, closing).lowercase()]
        ?: return null
    return text.substring(0, opening) + emoji
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Touch-down feedback; plain Compose clickables give none (iOS PressFeedbackStyle). */
@Composable
private fun Modifier.pressFeedback(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(100), label = "pressScale")
    val alpha by animateFloatAsState(if (pressed) 0.5f else 1f, tween(100), label = "pressAlpha")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

/** Slide-to-cancel discard threshold (leftward). */
private val voiceCancelThreshold = 80.dp

/** Slide-to-lock threshold (upward). */
private val voiceLockThreshold = 60.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
       ExperimentalFoundationApi::class)
@Composable
fun ComposerView(
    viewModel: TimelineViewModel,
    preferences: Preferences,
    sessionScope: SessionScope? = null,
    /**
     * Owns its ime/navigation-bar lift when true (iOS manualBottomPadding).
     * ThreadScreen applies its own keyboard inset, so it passes false — the
     * composer must not double-lift the bar there.
     */
    applyImePadding: Boolean = true,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val uiScope = rememberCoroutineScope()

    val prefs by preferences.state.collectAsStateWithLifecycle()
    val reduceMotion = prefs.reduceTimelineMotion

    val replyTarget by viewModel.replyTarget.collectAsStateWithLifecycle()
    val editTarget by viewModel.editTarget.collectAsStateWithLifecycle()
    val pendingAttachments by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val composerError by viewModel.composerError.collectAsStateWithLifecycle()
    val isEncrypted by viewModel.isEncrypted.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val typingUsers by viewModel.typingUsers.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val membersById by viewModel.membersById.collectAsStateWithLifecycle()

    // Empty-flow stand-ins so the collect call count is stable when there's no
    // session scope (reaction previews, previews-only surfaces).
    val emptyPacks = remember { MutableStateFlow(emptyList<CustomEmojiStore.Pack>()) }
    val emptyEmotes = remember { MutableStateFlow(emptyList<CustomEmojiStore.Emote>()) }
    val customPacks by (sessionScope?.customEmoji?.packs ?: emptyPacks)
        .collectAsStateWithLifecycle()
    val sortedEmoticons by (sessionScope?.customEmoji?.sortedEmoticons ?: emptyEmotes)
        .collectAsStateWithLifecycle()
    // Lowercase each shortcode once per pack change, not per keystroke: the
    // emote-autocomplete scan runs on every character typed and lowercasing
    // inside its hot loop was per-keystroke main-thread work on big packs.
    val loweredEmoticons = remember(sortedEmoticons) {
        sortedEmoticons.map { it to it.shortcode.lowercase() }
    }

    val emoteLoader = remember(sessionScope) {
        sessionScope?.let { s ->
            object : EmoteAssetLoader {
                override fun cachedImage(mxcUrl: String, pixelSize: Float) =
                    s.mediaLoader.cachedImage(mxcUrl, pixelSize)

                override suspend fun avatar(mxcUrl: String, pixelSize: Float) =
                    s.mediaLoader.avatar(mxcUrl, pixelSize)
            }
        }
    }
    val pickerLoader = remember(sessionScope) {
        sessionScope?.let { s -> EmoteImageLoader { url -> s.mediaLoader.avatar(url, 64f) } }
    }

    // MARK: Composer state

    // Draft survives room switches on the VM (the composable is torn down).
    var textField by remember(viewModel) {
        mutableStateOf(TextFieldValue(viewModel.draftText, TextRange(viewModel.draftText.length)))
    }
    /** In-progress draft, stashed while an edit occupies the field so a
     *  cancelled edit restores it. */
    var stashedDraft by remember(viewModel) { mutableStateOf("") }
    var attachMenuOpen by remember { mutableStateOf(false) }
    var showsPollSheet by remember { mutableStateOf(false) }
    var showsLocationShareConfirm by remember { mutableStateOf(false) }
    var showsExpressionPanel by remember { mutableStateOf(false) }
    /** Panel's search field is focused: the keyboard is up FOR the panel, so
     *  the ime-shown handler must not retire it; bar+panel lift together. */
    var panelSearchActive by remember { mutableStateOf(false) }

    /** Active "@partial" token at the end of the field, sans the @. */
    var mentionQuery by remember(viewModel) { mutableStateOf<String?>(null) }
    var mentionSuggestions by remember(viewModel) {
        mutableStateOf<List<TimelineViewModel.MemberItem>>(emptyList())
    }
    /** Mentions the user picked from the autocomplete, so the plain `@Name`
     *  tokens shown in the field can be turned into real matrix.to links + an
     *  intentional-mention list at send time. */
    var chosenMentions by remember(viewModel) { mutableStateOf<List<ChosenMention>>(emptyList()) }
    /** Matches for a trailing ":partial" token. */
    var emoteSuggestions by remember(viewModel) {
        mutableStateOf<List<EmojiSuggestion>>(emptyList())
    }
    var selectedSuggestion by remember(viewModel) { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }

    // MARK: Voice recording state

    val recorder = remember(viewModel) { VoiceRecorder(context) }
    val isRecording by recorder.isRecording.collectAsStateWithLifecycle()
    val recordingDuration by recorder.duration.collectAsStateWithLifecycle()
    val recordingLevels by recorder.levels.collectAsStateWithLifecycle()

    /** True while the finger is down on the mic. */
    var voiceHoldActive by remember { mutableStateOf(false) }
    /** Slid up to the lock; recording continues hands-free until sent or
     *  discarded from the bar. */
    var isVoiceLocked by remember { mutableStateOf(false) }
    /** Live hold translation, for the slide-to-cancel/lock feedback. */
    var voiceDrag by remember { mutableStateOf(Offset.Zero) }
    /** Mic bubble's window origin, so the hold drag tracks in window space. */
    var voiceMicOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    /** Set once a slide-to-cancel discards; swallows the rest of the gesture. */
    var voiceDragCancelled by remember { mutableStateOf(false) }
    var voiceHint by remember { mutableStateOf<String?>(null) }
    var voiceHintTick by remember { mutableIntStateOf(0) }

    // MARK: Keyboard / expression-panel tracking

    // iOS tracks keyboard frames manually so the keyboard lift and the panel
    // swap on one animation clock; on Android the animated ime inset is read
    // directly and the same padding math applies frame-by-frame.
    //
    // The animated ime inset changes every frame while the keyboard slides; if
    // it were read here in the body, the WHOLE composer (bar, banners, popups,
    // field, panel) would recompose dozens of times per keyboard animation.
    // Only the bottom Spacer genuinely needs the per-frame value, so its inset
    // read lives in the ComposerBottomSpacer leaf below. The body keeps only the
    // settled reads: navBottom (stable during a keyboard animation) and the
    // imeVisible boolean (flips once at start/end, not per frame).
    val navBottomDp = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val imeVisible = WindowInsets.isImeVisible
    /** Last real keyboard height; sizes the panel to the same space. Latched off
     *  the composition via snapshotFlow so the per-frame inset read doesn't
     *  invalidate the body — only crossings of the 200dp mark touch state. */
    var lastKeyboardHeight by remember { mutableStateOf(330.dp) }
    val imeInsets = WindowInsets.ime
    LaunchedEffect(density, imeInsets) {
        snapshotFlow { with(density) { imeInsets.getBottom(density).toDp() } }
            .collect { if (it > 200.dp) lastKeyboardHeight = it }
    }
    // Keyboard frames include the gesture-nav band the panel background
    // already covers. (No latch needed: Android insets don't flash 0 the way
    // the iOS safe-area read did.)
    val expressionPanelHeight = maxOf(240.dp, lastKeyboardHeight - navBottomDp)

    /** Extra panel height pulled out via the grabber (0 = keyboard-sized). The
     *  live drag writes this float directly — launching a coroutine to snapTo an
     *  Animatable on every pointer-move dropped frames under a fast drag. */
    var panelExtraPx by remember { mutableFloatStateOf(0f) }
    /** Only the release fling / reset run through the Animatable; its value is
     *  mirrored into `panelExtraPx` so the spring animates the panel height. */
    val panelExtra = remember { Animatable(0f) }
    LaunchedEffect(panelExtra) {
        snapshotFlow { panelExtra.value }.collect { panelExtraPx = it }
    }
    /** Panel can grow to ~three quarters of the window. */
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxPanelExtraPx = with(density) {
        (windowHeight * 0.75f - expressionPanelHeight).coerceAtLeast(0.dp).toPx()
    }
    var panelDragBase by remember { mutableFloatStateOf(0f) }
    var panelDragTotal by remember { mutableFloatStateOf(0f) }
    var isDraggingPanel by remember { mutableStateOf(false) }

    // (Bar lift lives in ComposerBottomSpacer, a leaf that reads the animated
    // ime inset itself so the frame-by-frame change re-lays-out only the spacer
    // rather than recomposing the whole composer.)

    // Keyboard reclaims the panel's space — unless it was raised by the
    // panel's own search field, which rides above it (iOS keyboardWillShow).
    LaunchedEffect(imeVisible) {
        if (imeVisible && showsExpressionPanel && !panelSearchActive) {
            showsExpressionPanel = false
        }
    }
    // The panel is a keyboard replacement: system back closes it first (like
    // the IME closes itself on back) instead of popping the room screen.
    BackHandler(enabled = showsExpressionPanel) { showsExpressionPanel = false }
    LaunchedEffect(showsExpressionPanel) {
        if (!showsExpressionPanel) {
            panelSearchActive = false
            // Reopen keyboard-sized, not at the last dragged height.
            panelExtra.snapTo(0f)
        }
    }

    // MARK: Pickers / permissions

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { viewModel.stageAttachment(it) }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { viewModel.stageAttachment(it) }
    }
    // The mic gesture asks before recording; a grant lands after the finger
    // lifted, so just arm the next hold (iOS recorder.start() prompts inline).
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            voiceHint = "Hold to record, release to send"
            voiceHintTick++
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) uiScope.launch { viewModel.shareCurrentLocation() }
    }

    // MARK: Autocomplete

    /** A trailing "@token" (@ at a word start) opens the list. Refreshes
     *  `mentionSuggestions`, cached so key handlers don't refilter. */
    fun updateMentionQuery() {
        selectedSuggestion = 0
        val text = textField.text
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) {
            mentionQuery = null
            mentionSuggestions = emptyList()
            return
        }
        val token = text.substring(atIndex + 1)
        val wordStart = atIndex == 0 || text[atIndex - 1].isWhitespace()
        if (token.any { it.isWhitespace() } || !wordStart) {
            mentionQuery = null
            mentionSuggestions = emptyList()
            return
        }
        mentionQuery = token
        // Fold once and compare against members' precomputed foldedName;
        // per-member locale-aware contains per keystroke was the hot path on
        // iOS. Only 6 are shown, so stop at 6.
        val foldedQuery = RoomSummary.foldedForSearch(token)
        val loweredQuery = token.lowercase()
        val matches = mutableListOf<TimelineViewModel.MemberItem>()
        for (member in members) {
            if (member.id == viewModel.ownUserId) continue
            if (token.isEmpty()
                || member.foldedName.contains(foldedQuery)
                || member.id.lowercase().contains(loweredQuery)
            ) {
                matches.add(member)
                if (matches.size == 6) break
            }
        }
        mentionSuggestions = matches
    }

    /** A trailing ":token" (colon at a word start, ≥2 shortcode chars)
     *  suggests custom emotes and unicode emoji. Suppressed while a mention
     *  query is active so "@user:server" doesn't read as an emote query. */
    fun updateEmoteQuery() {
        emoteSuggestions = emptyList()
        if (mentionQuery != null) return
        val text = textField.text
        val colonIndex = text.lastIndexOf(':')
        if (colonIndex < 0) return
        val token = text.substring(colonIndex + 1)
        if (token.length < 2 || !token.all { CustomEmojiStore.isShortcodeCharacter(it) }) return
        if (colonIndex > 0 && !text[colonIndex - 1].isWhitespace()) return
        val needle = token.lowercase()
        // Custom emotes first (prefix, then contains), then unicode emoji.
        val prefix = mutableListOf<EmojiSuggestion>()
        val contains = mutableListOf<EmojiSuggestion>()
        for ((emote, shortcode) in loweredEmoticons) {
            if (shortcode.startsWith(needle)) {
                prefix.add(EmojiSuggestion.Custom(emote))
                if (prefix.size == 6) break
            } else if (contains.size < 6 && shortcode.contains(needle)) {
                contains.add(EmojiSuggestion.Custom(emote))
            }
        }
        val unicode = EmojiShortcodes.matches(needle, limit = 6)
            .map { EmojiSuggestion.Unicode(emoji = it.first, shortcode = it.second) }
        emoteSuggestions = (prefix + unicode + contains).take(8)
        selectedSuggestion = 0
    }

    /** Programmatic text set (inserts, sends); mirrors the field's onChange
     *  tail like SwiftUI's onChange firing for assignments. Caret to end. */
    fun applyText(new: String) {
        textField = TextFieldValue(new, TextRange(new.length))
        if (viewModel.editTarget.value == null) viewModel.draftText = new
        if (new.isNotEmpty()) viewModel.composerIsTyping()
        updateMentionQuery()
        updateEmoteQuery()
    }

    /** Replaces the trailing "@token" with the member's full `@user:server`
     *  and records it; the send path turns the token into a mention anchor. */
    fun insertMention(member: TimelineViewModel.MemberItem) {
        val text = textField.text
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0) return
        chosenMentions = chosenMentions + ChosenMention(token = member.id, userId = member.id)
        applyText(text.substring(0, atIndex) + member.id + " ")
        mentionQuery = null
        mentionSuggestions = emptyList()
        focusRequester.requestFocus()
    }

    /** Replaces the trailing ":token": custom emotes keep their `:shortcode:`
     *  (converted at send time); unicode becomes the character. */
    fun insertEmote(suggestion: EmojiSuggestion) {
        val text = textField.text
        val colonIndex = text.lastIndexOf(':')
        if (colonIndex < 0) return
        val replacement = when (suggestion) {
            is EmojiSuggestion.Custom -> suggestion.emote.token + " "
            is EmojiSuggestion.Unicode -> suggestion.emoji
        }
        applyText(text.substring(0, colonIndex) + replacement)
        emoteSuggestions = emptyList()
        focusRequester.requestFocus()
    }

    /** Commits the highlighted autocomplete row, if any list is open. */
    fun acceptSuggestion(): Boolean {
        if (mentionSuggestions.isNotEmpty()) {
            insertMention(mentionSuggestions[min(selectedSuggestion, mentionSuggestions.size - 1)])
            return true
        }
        if (emoteSuggestions.isNotEmpty()) {
            insertEmote(emoteSuggestions[min(selectedSuggestion, emoteSuggestions.size - 1)])
            return true
        }
        return false
    }

    /** Mentions whose `@user:server` token is still present in the composed
     *  text (some may have been edited away), as `MentionRef`s for the send path. */
    fun resolvedMentions(text: String): List<MentionRef> {
        val result = mutableListOf<MentionRef>()
        for (mention in chosenMentions) {
            if (!text.contains(mention.token)) continue
            if (result.none { it.userId == mention.userId }) {
                result.add(MentionRef(userId = mention.userId, text = mention.token))
            }
        }
        return result
    }

    fun onTextChange(new: TextFieldValue) {
        val old = textField.text
        var value = new
        // (iOS also stages file paths pasted as text here; Android pastes and
        // drops arrive as content URIs via the drop target instead.)
        // Closing colon of ":pleading_face:" → 🥺 in place. Single-character
        // growth only, so pastes aren't rewritten.
        if (value.text.length == old.length + 1 && value.text.endsWith(":")) {
            autoReplacingTrailingShortcode(value.text)?.let { replaced ->
                value = TextFieldValue(replaced, TextRange(replaced.length))
            }
        }
        textField = value
        // Persist the draft, but not while an edit occupies the field (the
        // real draft is stashed in `stashedDraft`).
        if (viewModel.editTarget.value == null) viewModel.draftText = value.text
        if (value.text.isNotEmpty()) viewModel.composerIsTyping()
        updateMentionQuery()
        updateEmoteQuery()
    }

    val canSend = textField.text.trim().isNotEmpty() || pendingAttachments.isNotEmpty()

    fun send() {
        // Live reads, not the composition's `canSend`: callbacks can outlive
        // the frame they were built in.
        if (textField.text.trim().isEmpty() && !viewModel.hasPendingAttachments()) return
        val message = textField.text.trim()
        val mentions = resolvedMentions(message)
        chosenMentions = emptyList()
        applyText("")
        if (prefs.sendMessageHaptic) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        uiScope.launch { viewModel.sendComposed(message, mentions) }
    }

    // MARK: Voice helpers

    fun showVoiceHint(text: String) {
        voiceHint = text
        voiceHintTick++
    }

    fun discardActiveRecording() {
        isVoiceLocked = false
        recorder.stop(cancelled = true)
    }

    fun sendActiveRecording() {
        isVoiceLocked = false
        voiceHoldActive = false
        val recording = recorder.stop() ?: return
        if (prefs.sendMessageHaptic) haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        uiScope.launch { viewModel.sendVoiceMessage(recording) }
    }

    LaunchedEffect(voiceHintTick) {
        if (voiceHint == null) return@LaunchedEffect
        delay(2_000)
        voiceHint = null
    }

    // System stopped the recording: drop the stuck UI rather than freezing a
    // dead bar with a live red dot.
    LaunchedEffect(recorder) {
        recorder.interrupted.collect { interrupted ->
            if (!interrupted) return@collect
            voiceHoldActive = false
            isVoiceLocked = false
            voiceDragCancelled = false
            voiceDrag = Offset.Zero
            showVoiceHint("Recording interrupted")
        }
    }

    // Members load async; refresh an open mention query when they land.
    LaunchedEffect(members) { updateMentionQuery() }

    // Entering edit mode: stash the draft, then load the original text.
    LaunchedEffect(viewModel) {
        var previous: MessageItem? = null
        viewModel.editTarget.collect { target ->
            if (target == null) {
                // Leaving edit mode: restore the stashed draft.
                if (previous != null) {
                    textField = TextFieldValue(stashedDraft, TextRange(stashedDraft.length))
                    stashedDraft = ""
                }
            } else {
                // Stash only on the draft→edit transition; switching between
                // two edit targets keeps the original pre-edit draft.
                if (previous == null) stashedDraft = textField.text
                (target.kind as? MessageItem.Kind.Text)?.let { kind ->
                    textField = TextFieldValue(kind.body, TextRange(kind.body.length))
                }
                // The field isn't composed while a recording bar is up; the
                // prefill still lands, focus just can't.
                runCatching { focusRequester.requestFocus() }
            }
            previous = target
        }
    }

    // Composer (and its recorder) is torn down on room switch / back-nav while
    // a locked recording may be live; stop it to release the recorder and temp
    // file. Persist the draft (the pre-edit stash if an edit is open).
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.draftText =
                if (viewModel.editTarget.value != null) stashedDraft else textField.text
            if (recorder.isRecording.value) recorder.stop(cancelled = true)
        }
    }

    // Files/images dropped onto the bar stage as attachment chips (the iOS
    // dropDestination).
    val dropTarget = remember(viewModel) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val activity = context.findActivity() ?: return false
                // Null when the drop carries no permission-guarded URIs; the
                // clip data may still hold readable ones.
                ActivityCompat.requestDragAndDropPermissions(activity, dragEvent)
                val clip = dragEvent.clipData ?: return false
                var staged = false
                for (index in 0 until clip.itemCount) {
                    val uri = clip.getItemAt(index).uri ?: continue
                    viewModel.stageAttachment(uri)
                    staged = true
                }
                return staged
            }
        }
    }

    // MARK: Layout

    Column(Modifier.fillMaxWidth().background(colors.bgApp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 4.dp),
        ) {
            if (mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsPopup(
                    suggestions = mentionSuggestions,
                    selected = selectedSuggestion,
                    onPick = { insertMention(it) },
                )
            } else if (emoteSuggestions.isNotEmpty()) {
                EmoteSuggestionsPopup(
                    suggestions = emoteSuggestions,
                    selected = selectedSuggestion,
                    loader = emoteLoader,
                    onPick = { insertEmote(it) },
                )
            }

            // Tags that spring out of the composer's top edge for
            // typing/reply/edit/error states.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
            ) {
                if (!isEncrypted && prefs.warnUnencrypted) {
                    UnencryptedBanner()
                }
                AppendixVisibility(
                    visible = prefs.showTypingIndicators && typingUsers.isNotEmpty(),
                    reduceMotion = reduceMotion,
                ) {
                    TypingIndicator(typingText(typingUsers) { userId ->
                        membersById[userId]?.name
                            ?: if (userId.startsWith("@")) {
                                userId.drop(1).substringBefore(':')
                            } else {
                                userId
                            }
                    })
                }
                // Latched copies keep content stable through the exit animation.
                var lastError by remember { mutableStateOf("") }
                composerError?.let { lastError = it }
                AppendixVisibility(visible = composerError != null, reduceMotion = reduceMotion) {
                    ErrorBanner(lastError)
                }
                var lastReplyName by remember { mutableStateOf("") }
                replyTarget?.let { lastReplyName = it.displayName }
                AppendixVisibility(visible = editTarget != null, reduceMotion = reduceMotion) {
                    EditBanner(onCancel = { viewModel.editTarget.value = null })
                }
                AppendixVisibility(
                    visible = editTarget == null && replyTarget != null,
                    reduceMotion = reduceMotion,
                ) {
                    ReplyBanner(lastReplyName, onCancel = { viewModel.replyTarget.value = null })
                }
            }

            // Symmetric enter/exit spring so the strip shrinks the way it grew.
            val stripSpring = spring<Float>(
                dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            val stripSizeSpring = spring<androidx.compose.ui.unit.IntSize>(
                dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            AnimatedVisibility(
                visible = pendingAttachments.isNotEmpty(),
                enter = if (reduceMotion) EnterTransition.None
                        else fadeIn(stripSpring) + expandVertically(stripSizeSpring),
                exit = if (reduceMotion) ExitTransition.None
                       else fadeOut(stripSpring) + shrinkVertically(stripSizeSpring),
            ) {
                AttachmentStrip(
                    attachments = pendingAttachments,
                    onRemove = { viewModel.removeAttachment(it) },
                )
            }

            // MARK: The bar

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { event -> event.mimeTypes().isNotEmpty() },
                        target = dropTarget,
                    ),
            ) {
                // Leading bubble: attach menu, or the discard trash while
                // recording (slide-to-cancel target / locked-mode delete).
                if (isRecording) {
                    val cancelHalfPx = with(density) { -(voiceCancelThreshold.toPx()) * 0.5f }
                    BarBubble(
                        contentDescription = "Delete recording",
                        onClick = { discardActiveRecording() },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = if (voiceDrag.x < cancelHalfPx) colors.unreadMention
                                   else colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Box {
                        BarBubble(contentDescription = "Attach", onClick = { attachMenuOpen = true }) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = attachMenuOpen,
                            onDismissRequest = { attachMenuOpen = false },
                            containerColor = colors.bgElevated2,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Photo Library", color = colors.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.PhotoLibrary, null, tint = colors.textSecondary)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    photoPicker.launch(PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Attach File…", color = colors.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.AttachFile, null, tint = colors.textSecondary)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    filePicker.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Create Poll…", color = colors.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.BarChart, null, tint = colors.textSecondary)
                                },
                                onClick = { attachMenuOpen = false; showsPollSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Share Location", color = colors.textPrimary) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.LocationOn, null, tint = colors.textSecondary)
                                },
                                onClick = {
                                    attachMenuOpen = false
                                    // Confirm before broadcasting a location.
                                    showsLocationShareConfirm = true
                                },
                            )
                        }
                    }
                }

                // Field bubble: message field / recording states, hint above.
                Box(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.bgInput),
                    ) {
                        if (isRecording && voiceHoldActive && !isVoiceLocked) {
                            SlideToCancelBar(
                                duration = recordingDuration,
                                dragX = voiceDrag.x,
                                cancelThresholdPx = with(density) { -voiceCancelThreshold.toPx() },
                            )
                        } else if (isRecording) {
                            RecordingBar(duration = recordingDuration, levels = recordingLevels)
                        } else {
                            MessageField(
                                value = textField,
                                onValueChange = { onTextChange(it) },
                                placeholder = "Message $roomName",
                                focusRequester = focusRequester,
                                onFocusChanged = { focused ->
                                    // Focus moving to the field while the panel
                                    // rides above the keyboard (panel search):
                                    // the keyboard stays up so no ime-show
                                    // fires — retire the panel here.
                                    if (focused && showsExpressionPanel && panelSearchActive) {
                                        showsExpressionPanel = false
                                    }
                                },
                                suggestionCount = if (mentionSuggestions.isNotEmpty()) {
                                    mentionSuggestions.size
                                } else {
                                    emoteSuggestions.size
                                },
                                selectedSuggestion = selectedSuggestion,
                                onSelectSuggestion = { selectedSuggestion = it },
                                onAcceptSuggestion = { acceptSuggestion() },
                                onSend = { send() },
                            )
                        }
                    }
                    // Latched copy keeps the label readable through the fade-out
                    // (iOS `.transition(.opacity)` on the hint).
                    var lastVoiceHint by remember { mutableStateOf("") }
                    voiceHint?.let { lastVoiceHint = it }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = voiceHint != null,
                        enter = if (reduceMotion) EnterTransition.None
                                else fadeIn(tween(150)) +
                                    slideInVertically(tween(150)) { it / 4 },
                        exit = if (reduceMotion) ExitTransition.None
                               else fadeOut(tween(150)) +
                                   slideOutVertically(tween(150)) { it / 4 },
                        modifier = Modifier.align(Alignment.TopCenter),
                    ) {
                        Text(
                            lastVoiceHint,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .offset(y = (-34).dp)
                                .clip(CircleShape)
                                .background(colors.bgElevated2)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                // Expression picker toggle.
                if (!isRecording) {
                    BarBubble(
                        contentDescription = if (showsExpressionPanel) "Show keyboard"
                                             else "Emoji and stickers",
                        onClick = {
                            if (showsExpressionPanel) {
                                // Focusing raises the keyboard; the ime-shown
                                // handler retires the panel in the same motion.
                                focusRequester.requestFocus()
                            } else {
                                showsExpressionPanel = true
                                // The bar's lift is already 0 with the panel
                                // up, so the keyboard's departure doesn't move it.
                                focusManager.clearFocus()
                            }
                        },
                    ) {
                        Icon(
                            if (showsExpressionPanel) Icons.Outlined.Keyboard
                            else Icons.Outlined.EmojiEmotions,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Trailing bubble: send (locked recording or composed text),
                // else the hold-to-record mic.
                Box {
                    if (isRecording && isVoiceLocked) {
                        // Locked recording: tap to send.
                        BarBubble(
                            contentDescription = "Send voice message",
                            onClick = { sendActiveRecording() },
                        ) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else if (canSend) {
                        BarBubble(contentDescription = "Send", onClick = { send() }) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Hold to record; slide left to discard, up to lock.
                        // Raw pointer input: the zero-distance drag needs the
                        // press itself, not a click.
                        val micScale by animateFloatAsState(
                            if (voiceHoldActive) 1.25f else 1f, tween(150), label = "micScale")
                        BarBubble(
                            contentDescription = null,
                            onClick = null,
                            modifier = Modifier
                                // Window coordinates on purpose (iOS uses
                                // DragGesture(coordinateSpace: .global)):
                                // starting a recording dismisses the keyboard
                                // and slides the bar down, which in local
                                // space reads as the finger moving up and
                                // falsely triggers the lock.
                                .onGloballyPositioned {
                                    voiceMicOriginInWindow = it.positionInWindow()
                                }
                                .pointerInput(recorder) {
                                    val cancelPx = -voiceCancelThreshold.toPx()
                                    val lockPx = -voiceLockThreshold.toPx()
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        down.consume()
                                        if (ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.RECORD_AUDIO)
                                            != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            return@awaitEachGesture
                                        }
                                        voiceDragCancelled = false
                                        isVoiceLocked = false
                                        voiceHoldActive = true
                                        if (!recorder.start()) {
                                            voiceHoldActive = false
                                            return@awaitEachGesture
                                        }
                                        val downGlobal = voiceMicOriginInWindow + down.position
                                        var translation = Offset.Zero
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                change.consume()
                                                val wasCancelled = voiceDragCancelled
                                                voiceHoldActive = false
                                                voiceDrag = Offset.Zero
                                                voiceDragCancelled = false
                                                if (!wasCancelled && !isVoiceLocked) {
                                                    if (recorder.duration.value < 0.5) {
                                                        // A tap, not a hold.
                                                        discardActiveRecording()
                                                        showVoiceHint(
                                                            "Hold to record, release to send")
                                                    } else {
                                                        sendActiveRecording()
                                                    }
                                                }
                                                break
                                            }
                                            translation = (voiceMicOriginInWindow +
                                                change.position) - downGlobal
                                            change.consume()
                                            // Swallow the rest after a cancel.
                                            if (voiceDragCancelled || isVoiceLocked) continue
                                            voiceDrag = translation
                                            if (translation.x < cancelPx) {
                                                voiceDragCancelled = true
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.VirtualKey)
                                                discardActiveRecording()
                                            } else if (translation.y < lockPx) {
                                                isVoiceLocked = true
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress)
                                                voiceDrag = Offset.Zero
                                            }
                                        }
                                    }
                                }
                                // TalkBack can't drive the drag, so its tap
                                // toggles a locked recording instead.
                                .semantics {
                                    onClick(label = "Record voice message") {
                                        if (recorder.isRecording.value) {
                                            sendActiveRecording()
                                        } else if (recorder.start()) {
                                            isVoiceLocked = true
                                        }
                                        true
                                    }
                                },
                        ) {
                            Icon(
                                if (voiceHoldActive) Icons.Filled.Mic else Icons.Outlined.Mic,
                                contentDescription = "Record voice message",
                                tint = if (voiceHoldActive) colors.unreadMention
                                       else colors.textSecondary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer { scaleX = micScale; scaleY = micScale },
                            )
                        }
                    }
                    // Lock target floats above the mic while holding.
                    if (voiceHoldActive && !isVoiceLocked) {
                        VoiceLockPill(
                            progress = with(density) {
                                min(1f, max(0f, -voiceDrag.y / voiceLockThreshold.toPx()))
                            },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-56).dp),
                        )
                    }
                }
            }
        }

        // Keyboard-replacement expression panel: sits where the keyboard
        // would. The grabber expands it past keyboard height; dragging down at
        // rest closes it.
        AnimatedVisibility(
            visible = showsExpressionPanel,
            // Reveal height + fade in together so the panel slides up from the
            // keyboard region instead of squashing open (iOS `.move(edge:
            // .bottom)` on the panel). One duration + easing for the height on
            // both enter and exit so grow and shrink are symmetric, and closer
            // to the system keyboard inset's own animation clock so the panel
            // and the departing keyboard settle together instead of the panel's
            // tween finishing first and the bar visibly re-settling after it.
            enter = if (reduceMotion) EnterTransition.None
                    else expandVertically(tween(280, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(180)),
            exit = if (reduceMotion) ExitTransition.None
                   else shrinkVertically(tween(280, easing = FastOutSlowInEasing)) +
                       fadeOut(tween(180)),
        ) {
            val panelExtraDp = with(density) { panelExtraPx.toDp() }
            Column(
                Modifier
                    .fillMaxWidth()
                    // Matches the 8dp keyboard gap so the bar's resting height
                    // is identical in both states.
                    .padding(top = 8.dp)
                    .height(expressionPanelHeight + panelExtraDp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.bgElevated),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                if (!isDraggingPanel) {
                                    isDraggingPanel = true
                                    panelDragBase = panelExtraPx
                                    panelDragTotal = 0f
                                    // Freeze the fling animator so it stops
                                    // writing back into the live height mid-drag.
                                    uiScope.launch { panelExtra.stop() }
                                }
                                panelDragTotal += delta
                                // Direct assignment, no coroutine per delta: the
                                // height re-lays-out this frame without a launch.
                                panelExtraPx = (panelDragBase - panelDragTotal)
                                    .coerceIn(0f, maxPanelExtraPx)
                            },
                            onDragStopped = { velocity ->
                                isDraggingPanel = false
                                if (panelDragBase == 0f && panelExtraPx == 0f &&
                                    panelDragTotal > with(density) { 60.dp.toPx() }
                                ) {
                                    // Downward fling at rest closes the panel.
                                    showsExpressionPanel = false
                                } else {
                                    // Two detents: keyboard-sized or full.
                                    // Release snaps by projected position.
                                    val projected = panelExtraPx - velocity * 0.15f
                                    val target = if (projected > maxPanelExtraPx / 2) {
                                        maxPanelExtraPx
                                    } else {
                                        0f
                                    }
                                    // Hand the live dragged height back to the
                                    // animator so the fling springs from where
                                    // the finger left it, not the stale value.
                                    panelExtra.snapTo(panelExtraPx)
                                    panelExtra.animateTo(target, spring(
                                        dampingRatio = 0.85f,
                                        stiffness = Spring.StiffnessMediumLow))
                                }
                            },
                        ),
                ) {
                    Box(
                        Modifier
                            .padding(top = 6.dp, bottom = 2.dp)
                            .size(width = 36.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(colors.textTertiary.copy(alpha = 0.5f)),
                    )
                }
                Box(Modifier.weight(1f)) {
                    EmojiStickerPicker(
                        customPacks = customPacks,
                        loader = pickerLoader,
                        insertEmoji = { emoji ->
                            // No refocus: raising the keyboard would retire the
                            // panel.
                            applyText(textField.text + emoji)
                        },
                        insertCustomEmoji = if (sessionScope != null) {
                            { emote ->
                                // The `:shortcode:` token; the send path converts it.
                                applyText(textField.text + emote.token + " ")
                            }
                        } else {
                            null
                        },
                        onSearchFocusChange = { panelSearchActive = it },
                        refreshCustomEmoji = sessionScope?.let { s ->
                            { s.customEmoji.refreshIfStale() }
                        },
                        stickerPicker = sessionScope?.let { s ->
                            {
                                StickerPickerView(
                                    store = s.stickers,
                                    loader = s.mediaLoader,
                                    customEmoji = s.customEmoji,
                                    send = { sticker ->
                                        // Panel stays up so stickers can be chained.
                                        uiScope.launch { viewModel.sendSticker(sticker) }
                                    },
                                    sendPackSticker = { emote ->
                                        uiScope.launch {
                                            viewModel.sendSticker(emote, s.mediaLoader)
                                        }
                                    },
                                    onSearchFocusChange = { panelSearchActive = it },
                                )
                            }
                        },
                    )
                }
            }
        }

        if (applyImePadding) {
            ComposerBottomSpacer(
                navBottomDp = navBottomDp,
                showsExpressionPanel = showsExpressionPanel,
                panelSearchActive = panelSearchActive,
            )
            // The panel's background stretches through the gesture-nav band to
            // the bottom edge (the iOS -latchedBottomInset background pad).
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(if (showsExpressionPanel) colors.bgElevated else Color.Transparent),
            )
        }
    }

    if (showsPollSheet) {
        ModalBottomSheet(
            onDismissRequest = { showsPollSheet = false },
            containerColor = colors.bgApp,
        ) {
            NewPollSheet(viewModel = viewModel, onDismiss = { showsPollSheet = false })
        }
    }

    if (showsLocationShareConfirm) {
        AlertDialog(
            onDismissRequest = { showsLocationShareConfirm = false },
            containerColor = colors.bgElevated2,
            title = {
                Text("Share your current location?", color = colors.textPrimary, fontSize = 16.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    showsLocationShareConfirm = false
                    // The view model needs a granted location permission first.
                    if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        uiScope.launch { viewModel.shareCurrentLocation() }
                    } else {
                        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }) {
                    Text("Share Location", color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showsLocationShareConfirm = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }
}

/**
 * Owns the composer's keyboard-lift Spacer and the per-frame ime inset read
 * that drives it. Isolating both here means the animated inset (which changes
 * every frame while the keyboard slides) invalidates only this leaf, not the
 * whole ComposerView body — the dominant composer-open jank.
 *
 * The lift math is unchanged from the iOS manualBottomPadding: ride the keyboard
 * only when the panel's own search raised it, otherwise sit above the keyboard,
 * else hug the nav bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerBottomSpacer(
    navBottomDp: androidx.compose.ui.unit.Dp,
    showsExpressionPanel: Boolean,
    panelSearchActive: Boolean,
) {
    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(density).toDp() }
    val effectiveIme = (imeBottomDp - navBottomDp).coerceAtLeast(0.dp)
    val manualBottomPadding = when {
        showsExpressionPanel ->
            // Ride the keyboard only when the panel's own search field raised
            // it; otherwise the departing keyboard is briefly counted on top
            // of the panel height, lifting the bar too high before it settles.
            if (panelSearchActive && effectiveIme > 0.dp) effectiveIme + 8.dp else 0.dp
        effectiveIme > 0.dp -> effectiveIme + 8.dp
        else -> 2.dp
    }
    Spacer(Modifier.height(manualBottomPadding))
}

// MARK: Message field

@Composable
private fun MessageField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    suggestionCount: Int,
    selectedSuggestion: Int,
    onSelectSuggestion: (Int) -> Unit,
    onAcceptSuggestion: () -> Boolean,
    onSend: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = TextStyle(fontSize = 15.sp, color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        maxLines = 8,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Send,
        ),
        // The keyboard's send picks the highlighted suggestion first, else
        // sends (the iOS return-key dispatch).
        keyboardActions = KeyboardActions(onSend = {
            if (!onAcceptSuggestion()) onSend()
        }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(
                        placeholder,
                        fontSize = 15.sp,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            // Hardware-keyboard dispatch: arrows walk an open suggestion list,
            // Tab/Enter accept, plain Enter sends (⇧⏎ stays a newline).
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        if (suggestionCount > 0) {
                            onSelectSuggestion(max(0, selectedSuggestion - 1))
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionDown -> {
                        if (suggestionCount > 0) {
                            onSelectSuggestion(min(suggestionCount - 1, selectedSuggestion + 1))
                            true
                        } else {
                            false
                        }
                    }
                    Key.Tab -> onAcceptSuggestion()
                    Key.Enter, Key.NumPadEnter -> {
                        when {
                            onAcceptSuggestion() -> true
                            event.isShiftPressed -> false
                            else -> {
                                onSend()
                                true
                            }
                        }
                    }
                    else -> false
                }
            },
    )
}

// MARK: Suggestion popups

@Composable
private fun SuggestionContainer(content: @Composable () -> Unit) {
    val colors = LocalDiscourseColors.current
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .widthIn(max = 420.dp)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated2)
            .padding(6.dp),
    ) {
        content()
    }
}

@Composable
private fun SuggestionRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun MentionSuggestionsPopup(
    suggestions: List<TimelineViewModel.MemberItem>,
    selected: Int,
    onPick: (TimelineViewModel.MemberItem) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    SuggestionContainer {
        suggestions.forEachIndexed { index, member ->
            SuggestionRow(selected = index == selected, onClick = { onPick(member) }) {
                RoomAvatarView(
                    name = member.name,
                    isDirect = true,
                    size = 22.dp,
                    avatarUrl = member.avatarUrl,
                )
                Text(
                    member.name,
                    fontSize = 15.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    member.id,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun EmoteSuggestionsPopup(
    suggestions: List<EmojiSuggestion>,
    selected: Int,
    loader: EmoteAssetLoader?,
    onPick: (EmojiSuggestion) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    SuggestionContainer {
        suggestions.forEachIndexed { index, suggestion ->
            SuggestionRow(selected = index == selected, onClick = { onPick(suggestion) }) {
                when (suggestion) {
                    is EmojiSuggestion.Custom -> EmoteImageView(
                        url = suggestion.emote.url,
                        size = 22.dp,
                        loader = loader,
                    )
                    is EmojiSuggestion.Unicode -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(22.dp),
                    ) {
                        Text(suggestion.emoji, fontSize = 20.sp)
                    }
                }
                Text(
                    suggestion.label,
                    fontSize = 15.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: Appendix banners

/** Composer "appendix" styling: a compact tag hugging its content. */
@Composable
private fun AppendixBubble(content: @Composable RowScope.() -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .widthIn(max = 360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.bgElevated2)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        content = content,
    )
}

/** Grows out of the composer's top edge and shrinks back into it. */
@Composable
private fun AppendixVisibility(
    visible: Boolean,
    reduceMotion: Boolean,
    content: @Composable () -> Unit,
) {
    if (reduceMotion) {
        if (visible) content()
        return
    }
    // Grow and shrink on the same spring so the tag settles out the way it came
    // in; the default (stiffer) exit spec popped these banners out faster than
    // they grew, and they toggle every sync tick (typing) so it read as jumpy.
    val appendixSpring = spring<Float>(
        dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(
            animationSpec = appendixSpring,
            initialScale = 0.4f,
            transformOrigin = TransformOrigin(0f, 1f),
        ) + fadeIn(appendixSpring),
        exit = scaleOut(
            animationSpec = appendixSpring,
            targetScale = 0.4f,
            transformOrigin = TransformOrigin(0f, 1f),
        ) + fadeOut(appendixSpring),
    ) {
        content()
    }
}

/** Transient failure line above the bar; the view model auto-clears it. */
@Composable
private fun ErrorBanner(text: String) {
    val colors = LocalDiscourseColors.current
    AppendixBubble {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.unreadMention,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text,
            fontSize = 14.sp,
            color = colors.unreadMention,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Persistent notice that this room isn't end-to-end encrypted, so messages
 *  here aren't private the way they are in an encrypted room. */
@Composable
private fun UnencryptedBanner() {
    val colors = LocalDiscourseColors.current
    AppendixBubble {
        Icon(
            Icons.Filled.LockOpen,
            contentDescription = null,
            // iOS `.orange` (no theme token; same literal family as the
            // MessageRow sender palette).
            tint = Color(0xFFFF9F0A),
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Not encrypted — messages aren't private",
            fontSize = 14.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EditBanner(onCancel: () -> Unit) {
    val colors = LocalDiscourseColors.current
    AppendixBubble {
        Icon(
            Icons.Outlined.Edit,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text("Editing message", fontSize = 14.sp, color = colors.textPrimary)
        BannerCancelButton(label = "Cancel editing", onClick = onCancel)
    }
}

@Composable
private fun ReplyBanner(displayName: String, onCancel: () -> Unit) {
    val colors = LocalDiscourseColors.current
    AppendixBubble {
        Icon(
            Icons.AutoMirrored.Outlined.Reply,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Row(Modifier.weight(1f, fill = false)) {
            Text("Replying to ", fontSize = 14.sp, color = colors.textPrimary, maxLines = 1)
            Text(
                displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        BannerCancelButton(label = "Cancel reply", onClick = onCancel)
    }
}

@Composable
private fun BannerCancelButton(label: String, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    // Roomy target around the 16dp glyph (the iOS padding(13)/(-13) trick).
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Icon(
            Icons.Filled.Cancel,
            contentDescription = label,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TypingIndicator(text: String) {
    val colors = LocalDiscourseColors.current
    AppendixBubble {
        Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text,
            fontSize = 12.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Display names for the typing line, falling back to the mxid localpart. */
private inline fun typingText(typingUsers: List<String>, name: (String) -> String): String {
    val names = typingUsers.map(name)
    return when (names.size) {
        1 -> "${names[0]} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "Several people are typing…"
    }
}

// MARK: Attachments

/** Preview chips for staged attachments. */
@Composable
private fun AttachmentStrip(
    attachments: List<TimelineViewModel.PendingAttachment>,
    onRemove: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp, bottom = 2.dp),
    ) {
        attachments.forEach { attachment ->
            Box {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.bgInput)
                        .let {
                            if (attachment.uploadFailed) {
                                it.border(1.5.dp, colors.unreadMention, RoundedCornerShape(10.dp))
                            } else {
                                it
                            }
                        },
                ) {
                    val preview = attachment.previewImage
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = attachment.filename,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp),
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.InsertDriveFile,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                attachment.filename,
                                fontSize = 10.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    // Excluded from sends until the load lands.
                    if (attachment.isLoading) {
                        CircularProgressIndicator(
                            color = colors.textSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (attachment.uploadFailed) {
                        // Upload failed — retries when the user sends again.
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = "${attachment.filename}: upload failed",
                            tint = colors.unreadMention,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(3.dp)
                                .size(14.dp),
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(onClickLabel = "Remove ${attachment.filename}") {
                            onRemove(attachment.id)
                        },
                ) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = "Remove ${attachment.filename}",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

// MARK: Bar chrome

/** 40dp circular bubble inside a 44dp hit target (the iOS glass bubbles). */
@Composable
private fun BarBubble(
    contentDescription: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier
                .size(40.dp)
                .pressFeedback(interaction)
                .clip(CircleShape)
                .background(colors.bgInput)
                .let {
                    if (onClick != null) {
                        it.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = contentDescription,
                            onClick = onClick,
                        )
                    } else {
                        it
                    }
                },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

// MARK: Voice recording UI

/** Red-dot timer + live level bars while recording. */
@Composable
private fun RecordingBar(duration: Double, levels: List<Float>) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colors.unreadMention))
        Text(
            durationLabel(duration),
            fontSize = 15.sp,
            color = colors.textPrimary,
        )
        // (iOS caps the waveform at 180pt; the phone bar's leftover is
        // narrower than that anyway.)
        WaveformBars(
            samples = levels.takeLast(60),
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
        )
    }
}

/** Held-recording bar: timer plus a "slide to cancel" hint that rides the
 *  finger toward the trash. */
@Composable
private fun SlideToCancelBar(duration: Double, dragX: Float, cancelThresholdPx: Float) {
    val colors = LocalDiscourseColors.current
    val pull = min(0f, dragX)
    val cancelProgress = min(1f, max(0f, pull / cancelThresholdPx))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colors.unreadMention))
        Text(
            durationLabel(duration),
            fontSize = 15.sp,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 10.dp),
        )
        Spacer(Modifier.weight(1f))
        val hintColor = if (cancelProgress > 0.5f) colors.unreadMention else colors.textSecondary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .graphicsLayer {
                    translationX = pull * 0.5f
                    alpha = 1f - cancelProgress * 0.5f
                },
        ) {
            Icon(
                Icons.Outlined.ChevronLeft,
                contentDescription = null,
                tint = hintColor,
                modifier = Modifier.size(14.dp),
            )
            Text("Slide to cancel", fontSize = 15.sp, color = hintColor)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Lock target above the mic; fills in as the slide approaches. */
@Composable
private fun VoiceLockPill(progress: Float, modifier: Modifier = Modifier) {
    val colors = LocalDiscourseColors.current
    val tint = if (progress > 0.7f) colors.accent else colors.textSecondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(colors.bgElevated2)
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        Icon(
            if (progress >= 1f) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp),
        )
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = tint.copy(alpha = 0.6f),
            modifier = Modifier.size(12.dp),
        )
    }
}

private fun durationLabel(duration: Double): String {
    val total = duration.toInt()
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
