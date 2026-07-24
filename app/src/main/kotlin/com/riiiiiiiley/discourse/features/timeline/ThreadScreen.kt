package com.riiiiiiiley.discourse.features.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.models.TimelineEntry
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Identifiable wrapper so a thread root can drive a sheet. */
data class ThreadTarget(
    val id: String,
    val viewModel: TimelineViewModel,
)

/**
 * A thread's own timeline + composer, shown as a sheet from the room timeline.
 *
 * [entryRow] and [composer] are slots for the row/composer composables (owned
 * by the message-row and composer slices): pass
 * `{ entry -> TimelineEntryRow(entry = entry, viewModel = viewModel, …) }` and
 * `{ ComposerView(viewModel = viewModel) }`. This screen applies the keyboard
 * inset itself (the iOS sheet does the same via `.ignoresSafeArea(.keyboard)` +
 * the composer's manual lift), so the composer slot must NOT add its own
 * `imePadding` — that would double-lift the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    viewModel: TimelineViewModel,
    onDismiss: () -> Unit,
    entryRow: @Composable (TimelineEntry) -> Unit,
    composer: @Composable () -> Unit,
) {
    val colors = LocalDiscourseColors.current

    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Material 3 top app bar for the thread sheet: a close (X) navigation
        // icon and a centered title, replacing the iOS centered-title + Done bar.
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "Thread",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close thread", tint = colors.textPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgApp),
        )

        ThreadBody(viewModel, entryRow, composer)
    }
}

@Composable
private fun ColumnScope.ThreadBody(
    viewModel: TimelineViewModel,
    entryRow: @Composable (TimelineEntry) -> Unit,
    composer: @Composable () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val reachedStart by viewModel.reachedStart.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // Gates tail-follow so a late reply/reaction doesn't yank a user who
    // scrolled up.
    var threadAtBottom by remember { mutableStateOf(true) }
    val bottomThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }

    LaunchedEffect(listState) {
        // iOS: contentSize.height - visibleRect.maxY <= 40.
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index == info.totalItemsCount - 1 &&
                last.offset + last.size - info.viewportEndOffset <= bottomThresholdPx)
        }.collect { threadAtBottom = it }
    }

    // Bottom-anchored like the iOS `.defaultScrollAnchor(.bottom)` scroll view:
    // follow the tail only when at the bottom, or for a reply we just sent
    // (local echo has no event id yet). Prepends from pagination keep their
    // scroll position via item keys, so this only fires for genuine tail growth.
    val lastId = entries.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (lastId == null) return@LaunchedEffect
        val lastItem = (entries.lastOrNull() as? TimelineEntry.Message)?.item
        val sentOwn = lastItem != null && lastItem.isOwn && lastItem.eventId == null
        if (!threadAtBottom && !sentOwn) return@LaunchedEffect
        val count = entries.size + if (!reachedStart) 1 else 0
        // Max offset bottom-anchors the last item (the plain index would
        // top-anchor it and leave the tail off screen for tall items).
        listState.scrollToItem(count - 1, scrollOffset = Int.MAX_VALUE)
    }

    // iOS `.defaultScrollAnchor(.bottom)` also pins the tail through IN-PLACE
    // growth of the last row (reaction, edit, poll-vote update) — none of
    // which change entry ids, so the effect above never fires for them. Watch
    // the tail row's measured height and re-pin when it grows while the user
    // was at the bottom (judged against the pre-growth geometry, since the
    // growth itself can push us past the at-bottom threshold).
    LaunchedEffect(listState) {
        var previousIndex = -1
        var previousSize = -1
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last != null && last.index == info.totalItemsCount - 1) last.index to last.size
            else null
        }.collect { tail ->
            if (tail == null) return@collect
            val (index, size) = tail
            val grewBy = if (index == previousIndex && previousSize in 0 until size) {
                size - previousSize
            } else 0
            previousIndex = index
            previousSize = size
            if (grewBy <= 0 || listState.isScrollInProgress) return@collect
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@collect
            if (last.index != info.totalItemsCount - 1) return@collect
            val overflow = last.offset + last.size - info.viewportEndOffset
            if (overflow > 0 && overflow - grewBy <= bottomThresholdPx) {
                listState.scrollToItem(info.totalItemsCount - 1, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp),
        ) {
            // Back-paginate older replies. Same visibility-driven poll the room
            // timeline uses.
            if (!reachedStart) {
                item(key = "thread-paginate") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.textSecondary,
                        )
                    }
                    LaunchedEffect(Unit) {
                        while (isActive && !viewModel.reachedStart.value) {
                            viewModel.paginateBackwards()
                            delay(1_000)
                        }
                    }
                }
            }
            items(count = entries.size, key = { entries[it].id }) { index ->
                entryRow(entries[index])
            }
        }

        val currentError = error
        if (currentError != null) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Feedback,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    "Timeline Unavailable",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    currentError,
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        } else if (entries.isEmpty()) {
            // Only until the initial page lands (a thread always has its root,
            // open failures land in `error`), so this never sticks.
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = colors.textSecondary,
                )
                Text("Loading messages…", fontSize = 13.sp, color = colors.textSecondary)
            }
        }
    }

    composer()
}
