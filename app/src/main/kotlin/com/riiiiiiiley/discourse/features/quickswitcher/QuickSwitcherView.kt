package com.riiiiiiiley.discourse.features.quickswitcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.features.roomlist.RoomAvatarView
import com.riiiiiiiley.discourse.features.search.NoResultsView
import com.riiiiiiiley.discourse.models.RoomSummary
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlin.math.max
import kotlin.math.min

/** ⌘K jump-to-room palette; Enter (or a hardware keyboard) opens the highlighted room. */
@Composable
fun QuickSwitcherView(
    rooms: List<RoomSummary>,
    open: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current

    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    /** openRoom needs a joined timeline; spaces and pending invites have none. */
    fun eligibleRooms(): List<RoomSummary> = rooms.filter { !it.isSpace && !it.isInvited }
    /** Recomputed only on query/rooms change; read several times per pass. */
    var matches by remember {
        // Seed so the first pass (before the effects run) isn't empty.
        mutableStateOf(eligibleRooms().take(8))
    }
    val focusRequester = remember { FocusRequester() }

    fun recomputeMatches() {
        if (query.isEmpty()) {
            matches = eligibleRooms().take(8)
            return
        }
        val q = RoomSummary.foldedForSearch(query)
        // Prefix matches outrank contains matches; order preserved within each.
        val prefixMatches = mutableListOf<RoomSummary>()
        val containsMatches = mutableListOf<RoomSummary>()
        for (room in eligibleRooms()) {
            if (room.foldedName.startsWith(q)) {
                prefixMatches.add(room)
                if (prefixMatches.size == 8) break
            } else if (containsMatches.size < 8 && room.foldedName.contains(q)) {
                containsMatches.add(room)
            }
        }
        matches = (prefixMatches + containsMatches).take(8)
    }

    fun openHighlighted() {
        val room = matches.getOrNull(highlighted) ?: return
        open(room.id)
        onDismiss()
    }

    LaunchedEffect(query) {
        highlighted = 0
        recomputeMatches()
    }
    // Rooms keep syncing while open; refresh so the list doesn't freeze.
    LaunchedEffect(rooms) {
        highlighted = min(highlighted, max(0, matches.size - 1))
        recomputeMatches()
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 20.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { openHighlighted() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .focusRequester(focusRequester)
                // Hardware ↑/↓ move the highlight, like the iOS onKeyPress pair.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            highlighted = min(highlighted + 1, matches.size - 1)
                            true
                        }
                        Key.DirectionUp -> {
                            highlighted = max(highlighted - 1, 0)
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { innerField ->
                Box {
                    if (query.isEmpty()) {
                        Text("Jump to room…", fontSize = 20.sp, color = colors.textTertiary)
                    }
                    innerField()
                }
            },
        )

        HorizontalDivider(color = colors.separator)

        if (matches.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                NoResultsView(query = query, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Column(modifier = Modifier.padding(8.dp)) {
                matches.forEachIndexed { index, room ->
                    ResultRow(
                        room = room,
                        isHighlighted = index == highlighted,
                        select = {
                            open(room.id)
                            onDismiss()
                        },
                    )
                    if (index < matches.size - 1) Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(room: RoomSummary, isHighlighted: Boolean, select: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Touch-sized rows (44pt), matching the iOS phone density.
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHighlighted) colors.accentSoft else Color.Transparent)
            .clickable(onClick = select)
            .padding(horizontal = 10.dp)
            .semantics { if (room.hasUnread) stateDescription = "Unread" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatarView(
            name = room.name,
            isDirect = room.isDirect,
            size = 22.dp,
            avatarUrl = room.avatarUrl,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = room.name,
            fontSize = 16.sp,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (room.isMentioned) {
            // Red for a ping, matching the sidebar/rail signal.
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.unreadMention),
            )
        } else if (room.hasUnread) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}
