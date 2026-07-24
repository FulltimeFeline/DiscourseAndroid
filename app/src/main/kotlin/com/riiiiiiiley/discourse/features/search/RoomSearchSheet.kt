package com.riiiiiiiley.discourse.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.features.timeline.TimelineViewModel
import com.riiiiiiiley.discourse.models.MessageItem
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * In-room search (⌘F on iOS). Searches the timeline itself: instant over loaded
 * history, then back-paginates to reach old (and encrypted) messages the SDK's
 * cache-only search misses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSearchSheet(
    viewModel: TimelineViewModel,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MessageSearch.Category.ALL) }
    var isScanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val searchFieldFocus = remember { FocusRequester() }

    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val reachedStart by viewModel.reachedStart.collectAsStateWithLifecycle()

    val trimmedQuery = query.trim()

    /** Newest-first matches over everything loaded so far. */
    val matches = remember(entries, trimmedQuery, category) {
        if (trimmedQuery.isEmpty() && category == MessageSearch.Category.ALL) emptyList()
        else entries.asReversed().mapNotNull { entry ->
            val message = (entry as? TimelineEntry.Message)?.item ?: return@mapNotNull null
            if (message.eventId == null) return@mapNotNull null
            if (category != MessageSearch.Category.ALL &&
                categoryOf(message.kind) != category) return@mapNotNull null
            if (trimmedQuery.isNotEmpty() &&
                !searchTextOf(message).contains(trimmedQuery, ignoreCase = true))
                return@mapNotNull null
            message
        }
    }

    /** The date search has reached going back, for the footer. */
    val oldestLoaded: Long? = remember(entries) {
        entries.firstNotNullOfOrNull { (it as? TimelineEntry.Message)?.item?.timestamp }
    }

    /** Pulls older history in chunks; `matches` recomputes live as pages land. */
    fun scanOlder(pages: Int = 20) {
        if (isScanning || viewModel.reachedStart.value) return
        isScanning = true
        scanJob = coroutineScope.launch {
            try {
                repeat(pages) {
                    if (!isActive || viewModel.reachedStart.value) return@launch
                    viewModel.paginateBackwards()
                    // Let the diff stream apply before the next round.
                    delay(40)
                }
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery.isNotEmpty() && !isScanning) scanOlder(pages = 6)
    }
    // ⌘F means "type now" — autofocus.
    LaunchedEffect(Unit) { searchFieldFocus.requestFocus() }
    DisposableEffect(Unit) {
        onDispose { scanJob?.cancel() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp),
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Search in $roomName",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
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
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchCapsuleField(
                query = query,
                placeholder = "Search messages and media…",
                focusRequester = searchFieldFocus,
                onSearch = { scanOlder() },
                onQueryChange = { query = it },
            )

            CategorySegmentedControl(
                selection = category,
                modifier = Modifier.padding(top = 2.dp),
                onSelect = { category = it },
            )

            if (matches.isEmpty()) {
                if (trimmedQuery.isEmpty() && category == MessageSearch.Category.ALL) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Search This Room",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Type to search messages — or pick a media type to browse attachments.",
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                } else if (isScanning) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Searching…", fontSize = 15.sp, color = colors.textSecondary)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        NoResultsView(query = trimmedQuery)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(matches, key = { it.eventId ?: it.id }) { message ->
                        SearchHitRow(
                            hit = MessageSearch.Hit(
                                id = message.eventId ?: message.id,
                                roomId = viewModel.roomId,
                                sender = message.sender,
                                senderName = message.displayName,
                                timestamp = message.timestamp,
                                preview = previewTextOf(message),
                                category = categoryOf(message.kind),
                            ),
                            roomName = null,
                        ) {
                            onDismiss()
                            message.eventId?.let { onSelect(it) }
                        }
                    }
                }
            }

            // Coverage footer: how deep the search has gone, plus a lever to
            // keep digging through server history.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Searching older messages…",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                    )
                    TextButton(onClick = { scanJob?.cancel() }) {
                        Text("Stop", color = colors.accent, fontSize = 14.sp)
                    }
                } else if (reachedStart) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.presenceOnline,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Searched the whole conversation.",
                        fontSize = 15.sp,
                        color = colors.textSecondary,
                    )
                } else {
                    if (oldestLoaded != null) {
                        Text(
                            "Searched back to ${formatCoverageDate(oldestLoaded)}.",
                            fontSize = 15.sp,
                            color = colors.textSecondary,
                        )
                    }
                    TextButton(onClick = { scanOlder() }) {
                        Text("Search Older Messages", color = colors.accent, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (matches.size == 1) "1 result" else "${matches.size} results",
                    fontSize = 15.sp,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

private fun categoryOf(kind: MessageItem.Kind): MessageSearch.Category = when (kind) {
    is MessageItem.Kind.Image -> MessageSearch.Category.IMAGES
    // iOS misses Kind.video here (it falls to .text); mapped correctly on Android.
    is MessageItem.Kind.Video -> MessageSearch.Category.VIDEO
    is MessageItem.Kind.Audio -> MessageSearch.Category.AUDIO
    is MessageItem.Kind.Media ->
        if (kind.systemImage == "video") MessageSearch.Category.VIDEO
        else MessageSearch.Category.FILES
    else -> MessageSearch.Category.TEXT
}

private fun searchTextOf(message: MessageItem): String {
    val parts = mutableListOf(message.displayName)
    when (val kind = message.kind) {
        is MessageItem.Kind.Text -> parts.add(kind.body)
        is MessageItem.Kind.Notice -> parts.add(kind.body)
        is MessageItem.Kind.Emote -> parts.add(kind.body)
        is MessageItem.Kind.Image -> {
            parts.add(kind.item.filename)
            kind.item.caption?.let { parts.add(it) }
        }
        is MessageItem.Kind.Video -> {
            parts.add(kind.item.filename)
            kind.item.caption?.let { parts.add(it) }
        }
        is MessageItem.Kind.Audio -> parts.add(kind.item.filename)
        is MessageItem.Kind.Media -> parts.add(kind.label)
        is MessageItem.Kind.Poll -> parts.add(kind.item.question)
        is MessageItem.Kind.Location -> parts.add(kind.body)
        else -> {}
    }
    return parts.joinToString(" ")
}

private fun previewTextOf(message: MessageItem): String = when (val kind = message.kind) {
    is MessageItem.Kind.Text -> kind.body
    is MessageItem.Kind.Notice -> kind.body
    is MessageItem.Kind.Emote -> kind.body
    is MessageItem.Kind.Image -> kind.item.caption ?: kind.item.filename
    is MessageItem.Kind.Video -> kind.item.caption ?: kind.item.filename
    is MessageItem.Kind.Audio ->
        if (kind.item.isVoiceMessage) "Voice message" else kind.item.filename
    is MessageItem.Kind.Media -> kind.label
    is MessageItem.Kind.Poll -> kind.item.question
    is MessageItem.Kind.Location -> kind.body.ifEmpty { "Shared location" }
    else -> "Message"
}
