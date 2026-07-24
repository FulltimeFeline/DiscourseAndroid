package com.riiiiiiiley.discourse.features.search

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.models.previewText
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.RoomSearchResult
import org.matrix.rustcomponents.sdk.TimelineItemContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared bits between global and in-room message search. */
object MessageSearch {
    data class Hit(
        val id: String,
        val roomId: String,
        val sender: String,
        val senderName: String,
        /** Epoch milliseconds (iOS `Date`). */
        val timestamp: Long,
        val preview: String,
        val category: Category,
    )

    enum class Category(val title: String) {
        ALL("All"),
        TEXT("Text"),
        IMAGES("Images"),
        VIDEO("Video"),
        AUDIO("Audio"),
        FILES("Files"),
    }

    fun hit(roomId: String, event: RoomSearchResult): Hit {
        var senderName = event.sender
        (event.senderProfile as? ProfileDetails.Ready)?.displayName?.let { senderName = it }
        return Hit(
            id = event.eventId,
            roomId = roomId,
            sender = event.sender,
            senderName = senderName,
            timestamp = event.timestamp.toLong(),
            preview = previewText(event.content) ?: "…",
            category = category(event.content),
        )
    }

    fun category(content: TimelineItemContent): Category {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return Category.TEXT
        return when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> when (kind.content.msgType) {
                is MessageType.Image, is MessageType.Gallery -> Category.IMAGES
                is MessageType.Video -> Category.VIDEO
                is MessageType.Audio -> Category.AUDIO
                is MessageType.File -> Category.FILES
                else -> Category.TEXT
            }
            is MsgLikeKind.Sticker -> Category.IMAGES
            else -> Category.TEXT
        }
    }
}

/** "24 Jul 14:05"-style hit timestamp (iOS `.dateTime.day().month().hour().minute()`). */
internal fun formatHitTimestamp(epochMs: Long): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMjmm")
    return SimpleDateFormat(pattern, locale).format(Date(epochMs))
}

/** "24 Jul 2026"-style coverage date (iOS `.dateTime.day().month().year()`). */
internal fun formatCoverageDate(epochMs: Long): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMMy")
    return SimpleDateFormat(pattern, locale).format(Date(epochMs))
}

@Composable
internal fun SearchHitRow(
    hit: MessageSearch.Hit,
    /** Shown above the message; null in single-room search. */
    roomName: String?,
    modifier: Modifier = Modifier,
    select: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = select)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (roomName != null) {
                Text(
                    text = roomName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = formatHitTimestamp(hit.timestamp),
                fontSize = 12.sp,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = hit.senderName,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = hit.preview,
                fontSize = 16.sp,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * iOS 26-style segmented control for the search category: a capsule with a
 * tinted pill that slides to the selected segment (the matchedGeometryEffect
 * animation, done here with an animated offset).
 */
@Composable
internal fun CategorySegmentedControl(
    selection: MessageSearch.Category,
    modifier: Modifier = Modifier,
    onSelect: (MessageSearch.Category) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val categories = MessageSearch.Category.entries
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.bgElevated2)
            .border(1.dp, colors.separator, CircleShape)
            .padding(3.dp),
    ) {
        val segmentWidth = maxWidth / categories.size
        // iOS shrinks segment labels to fit (`minimumScaleFactor(0.8)`);
        // approximate by dropping a size on narrow screens.
        val compactLabels = maxWidth < 344.dp
        val pillOffset by animateDpAsState(
            targetValue = segmentWidth * categories.indexOf(selection),
            animationSpec = tween(durationMillis = 280),
            label = "categoryPill",
        )
        Box(
            modifier = Modifier
                .offset(x = pillOffset)
                .width(segmentWidth)
                .height(34.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.85f)),
        )
        Row {
            for (item in categories) {
                val selected = item == selection
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(CircleShape)
                        .clickable { onSelect(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.title,
                        fontSize = if (compactLabels && item.title.length > 5) 13.sp else 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) colors.textOnAccent else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Liquid-glass search bubble: a capsule with an inline magnifier and clear
 * button (the pre-iOS-26 material fallback: filled capsule + hairline border).
 */
@Composable
internal fun SearchCapsuleField(
    query: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: () -> Unit = {},
    onQueryChange: (String) -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(colors.bgInput)
            .border(1.dp, colors.separator, CircleShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .weight(1f)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            decorationBox = { innerField ->
                Box {
                    if (query.isEmpty()) {
                        Text(placeholder, fontSize = 16.sp, color = colors.textTertiary, maxLines = 1)
                    }
                    innerField()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(22.dp)) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = "Clear search",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** iOS `ContentUnavailableView.search(text:)`. */
@Composable
internal fun NoResultsView(query: String, modifier: Modifier = Modifier) {
    val colors = LocalDiscourseColors.current
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (query.isEmpty()) "No Results" else "No Results for “$query”",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Check the spelling or try a new search.",
            fontSize = 14.sp,
            color = colors.textSecondary,
        )
    }
}
