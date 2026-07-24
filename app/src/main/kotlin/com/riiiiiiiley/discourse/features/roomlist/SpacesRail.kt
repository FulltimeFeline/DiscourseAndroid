package com.riiiiiiiley.discourse.features.roomlist

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** iOS phone layout constant: the rail column is 68pt wide. */
val SpacesRailWidth = 68.dp

private val avatarSize = 40.dp

/** Slot fits the selection ring too — the ring must stay inside it or the rail clips it. */
private val slotSize = 48.dp

/**
 * The server column: Home plus one avatar per joined top-level space. The
 * account switcher lives in the Profile tab, as on iOS (the macOS-only rail
 * switcher isn't ported).
 */
@Composable
fun SpacesRail(
    viewModel: RoomListViewModel,
    preferences: Preferences,
    onNewSpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val context = LocalContext.current

    val orderedSpaces by viewModel.orderedSpaces.collectAsStateWithLifecycle()
    val selectedSpaceId by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    val homeHasUnread by viewModel.homeHasUnread.collectAsStateWithLifecycle()
    val homeHasMention by viewModel.homeHasMention.collectAsStateWithLifecycle()
    val unreadSpaceIds by viewModel.unreadSpaceIds.collectAsStateWithLifecycle()
    val mentionSpaceIds by viewModel.mentionSpaceIds.collectAsStateWithLifecycle()
    val prefsState by preferences.state.collectAsStateWithLifecycle()

    // iOS combines the in-app toggle with the system reduce-motion switch.
    val systemReduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val reduceMotion = prefsState.reduceTimelineMotion || systemReduceMotion

    // The accent wash is a pure function of the accent color; rebuild the Brush
    // only when the accent changes rather than allocating it every recomposition.
    val homeAccentBrush = remember(colors.accent) { accentGradient(colors.accent) }

    var leavingSpace by remember { mutableStateOf<RoomListViewModel.SpaceItem?>(null) }
    var menuSpaceId by remember { mutableStateOf<String?>(null) }
    var homeMenuOpen by remember { mutableStateOf(false) }

    // Long-press-drag reorder. In-process state only (the iOS private drag type
    // exists so a foreign drag can't replay a stale draggingSpaceId; gestures
    // here are inherently in-process).
    var draggingSpaceId by remember { mutableStateOf<String?>(null) }
    var dragOriginIndex by remember { mutableIntStateOf(0) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    val currentOrdered by rememberUpdatedState(orderedSpaces)
    // Slot height plus the rail's 8dp spacing: one full step between neighbors.
    val stepPx = with(density) { (slotSize + 8.dp).toPx() }
    val slopPx = with(density) { 10.dp.toPx() }

    Column(
        modifier
            .width(SpacesRailWidth)
            .fillMaxHeight()
            .background(colors.bgRail)
            // Keep the rail's content clear of the status bar (background still
            // reaches the top edge; only the content is inset).
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // Bottom clearance so the last space/Add button can scroll clear
                // of the bottom NavigationBar (its ~80dp height + the system nav
                // inset it consumes) instead of hiding behind it.
                .padding(
                    top = 6.dp,
                    bottom = 6.dp + 80.dp +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton(
                isSelected = selectedSpaceId == null,
                help = "Home",
                hasUnread = homeHasUnread,
                hasMention = homeHasMention,
                reduceMotion = reduceMotion,
                interaction = Modifier.combinedClickable(
                    onClick = { coroutineScope.launch { viewModel.selectSpace(null) } },
                    onLongClick = { homeMenuOpen = true },
                ),
                menu = {
                    DropdownMenu(
                        expanded = homeMenuOpen,
                        onDismissRequest = { homeMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mark All as Read") },
                            leadingIcon = { Icon(Icons.Outlined.Drafts, contentDescription = null) },
                            onClick = {
                                homeMenuOpen = false
                                viewModel.markRead(viewModel.homeRoomIds)
                            },
                        )
                    }
                },
            ) {
                // The accent resolves through the theme (Preferences-driven), the
                // Compose analogue of iOS reading prefs.resolvedTint instead of
                // the asset-catalog accent.
                Box(
                    Modifier
                        .size(avatarSize)
                        .background(homeAccentBrush, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Email,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Divider between the chats button and the spaces. Concrete gray:
            // semantic styles vanish against the rail background.
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .size(width = 32.dp, height = 2.dp)
                    .background(Color.Gray.copy(alpha = 0.55f), CircleShape),
            )

            orderedSpaces.forEachIndexed { index, space ->
                // Keyed so a live reorder moves the node (and its in-flight drag
                // gesture) instead of restarting it at the new position.
                key(space.id) {
                    val isDragging = draggingSpaceId == space.id
                    RailButton(
                        isSelected = selectedSpaceId == space.id,
                        help = space.name,
                        hasUnread = unreadSpaceIds.contains(space.id),
                        hasMention = mentionSpaceIds.contains(space.id),
                        reduceMotion = reduceMotion,
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                // Follow the finger: accumulated drag minus the distance
                                // the live reorder has already moved the item.
                                translationY = if (isDragging) {
                                    dragAccumulated - (index - dragOriginIndex) * stepPx
                                } else 0f
                            },
                        interaction = Modifier
                            .pointerInput(space.id) {
                                detectTapGestures(
                                    // The long press belongs to the drag detector below;
                                    // handling it here just stops the eventual release
                                    // from also selecting the space.
                                    onLongPress = {},
                                    onTap = {
                                        coroutineScope.launch { viewModel.selectSpace(space.id) }
                                    },
                                )
                            }
                            // Long-press-drag to rearrange; Home and "+" stay pinned. A
                            // press released without movement opens the context menu
                            // instead (the iOS long-press menu).
                            .pointerInput(space.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggingSpaceId = space.id
                                        dragOriginIndex = currentOrdered
                                            .indexOfFirst { it.id == space.id }
                                            .coerceAtLeast(0)
                                        dragAccumulated = 0f
                                        dragMoved = false
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulated += dragAmount.y
                                        if (abs(dragAccumulated) > slopPx) dragMoved = true
                                        // Reorder live as the dragged avatar passes its
                                        // neighbors; the arrangement persists via the
                                        // view model.
                                        val ordered = currentOrdered
                                        val current = ordered.indexOfFirst { it.id == space.id }
                                        val target = (dragOriginIndex +
                                            (dragAccumulated / stepPx).roundToInt())
                                            .coerceIn(0, ordered.lastIndex.coerceAtLeast(0))
                                        if (current >= 0 && target != current) {
                                            val others = ordered.filter { it.id != space.id }
                                            viewModel.moveSpace(
                                                space.id, others.getOrNull(target)?.id)
                                        }
                                    },
                                    onDragEnd = {
                                        if (!dragMoved) menuSpaceId = space.id
                                        draggingSpaceId = null
                                    },
                                    onDragCancel = { draggingSpaceId = null },
                                )
                            },
                        menu = {
                            DropdownMenu(
                                expanded = menuSpaceId == space.id,
                                onDismissRequest = { menuSpaceId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Mark All as Read") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Drafts, contentDescription = null)
                                    },
                                    onClick = {
                                        menuSpaceId = null
                                        viewModel.markRead(viewModel.childRoomIds(space.id))
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text("Leave Space…",
                                             color = MaterialTheme.colorScheme.error)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.Logout,
                                             contentDescription = null,
                                             tint = MaterialTheme.colorScheme.error)
                                    },
                                    onClick = {
                                        menuSpaceId = null
                                        leavingSpace = space
                                    },
                                )
                            }
                        },
                    ) {
                        RoomAvatarView(
                            name = space.name,
                            isDirect = false,
                            size = avatarSize,
                            avatarUrl = space.avatarUrl,
                        )
                    }
                }
            }

            RailButton(
                isSelected = false,
                help = "New Space",
                hasUnread = false,
                hasMention = false,
                reduceMotion = reduceMotion,
                interaction = Modifier.combinedClickable(onClick = onNewSpace),
            ) {
                Box(
                    Modifier
                        .size(avatarSize)
                        .background(colors.bgElevated2, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    leavingSpace?.let { space ->
        AlertDialog(
            onDismissRequest = { leavingSpace = null },
            title = { Text("Leave “${space.name}”?") },
            text = {
                Text("Rooms in the space stay joined. You'll need an invite to rejoin a private space.")
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch { viewModel.leave(space.id) }
                    leavingSpace = null
                }) {
                    Text("Leave Space", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { leavingSpace = null }) { Text("Cancel") }
            },
        )
    }
}

/** SwiftUI `.gradient` analogue: a subtle top-light vertical wash of the color. */
private fun accentGradient(color: Color): Brush = Brush.verticalGradient(
    listOf(lerp(color, Color.White, 0.12f), lerp(color, Color.Black, 0.08f)),
)

/**
 * One rail slot: centered label, left-edge selection/unread pill, bottom-right
 * mention dot, and an anchored context menu.
 */
@Composable
private fun RailButton(
    isSelected: Boolean,
    help: String,
    hasUnread: Boolean,
    hasMention: Boolean,
    reduceMotion: Boolean,
    interaction: Modifier,
    modifier: Modifier = Modifier,
    menu: @Composable BoxScope.() -> Unit = {},
    label: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    // Span the rail so the pip's leading edge is the window edge.
    Box(modifier.fillMaxWidth().height(slotSize)) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(slotSize)
                .then(interaction)
                // .help is hover-only on iOS; TalkBack needs the name spoken, and
                // the pip/dot/pill are purely visual — speak them too.
                .semantics {
                    contentDescription = help
                    selected = isSelected
                    if (hasMention) {
                        stateDescription = "Mention"
                    } else if (hasUnread && !isSelected) {
                        stateDescription = "Unread"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            label()
            // Red mention dot, distinct from the left-edge unread pill.
            AnimatedVisibility(
                visible = hasMention,
                enter = if (reduceMotion) EnterTransition.None else scaleIn() + fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp),
            ) {
                Box(
                    Modifier
                        .size(13.dp)
                        .background(colors.unreadMention, CircleShape)
                        .border(2.5.dp, colors.bgRail, CircleShape),
                )
            }
            menu()
        }
        // Left-edge indicator: a tall pill when selected, a short pip when unread.
        val pillHeight = if (isSelected) 30.dp else if (hasUnread) 10.dp else 0.dp
        val animatedHeight by animateDpAsState(
            targetValue = pillHeight,
            // Material motion: a smooth, non-bouncy medium spring instead of the
            // stiff (400) snap that made the pip flick abruptly. Reduce-motion
            // still jumps instantly.
            animationSpec = if (reduceMotion) {
                snap()
            } else {
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                )
            },
            label = "railPip",
        )
        if (animatedHeight > 0.dp) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(5.dp)
                    .height(animatedHeight)
                    .background(
                        colors.textPrimary,
                        RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
                    ),
            )
        }
    }
}
