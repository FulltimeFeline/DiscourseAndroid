package com.riiiiiiiley.discourse.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.GlobalSearchIterator
import org.matrix.rustcomponents.sdk.GlobalSearchResult
import org.matrix.rustcomponents.sdk.SearchRoomFilter

/**
 * Global message search across joined rooms, with media-type filtering.
 * Full-screen sheet (iOS: NavigationStack sheet with a nav-bar search drawer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsSheet(
    scope: SessionScope,
    /** iOS reads `scope.roomList`; passed explicitly until RoomListViewModel attaches to SessionScope. */
    roomList: RoomListViewModel,
    appState: AppState,
    /**
     * Seeded from the sidebar's search text, editable in-sheet so a typo doesn't
     * need a dismiss/retype/reopen.
     */
    query: String,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val coroutineScope = rememberCoroutineScope()

    var searchText by remember { mutableStateOf(query) }
    var hits by remember { mutableStateOf(listOf<MessageSearch.Hit>()) }
    var isLoading by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(MessageSearch.Category.ALL) }
    var iterator by remember { mutableStateOf<GlobalSearchIterator?>(null) }
    /**
     * Set when the search request itself failed, so the view can offer a
     * retry instead of a false "No Results".
     */
    var searchError by remember { mutableStateOf<String?>(null) }
    /**
     * Resolved in loadMore, not per-row, so rows don't re-render on every
     * 100 ms summary flush.
     */
    val roomNames = remember { mutableMapOf<String, String>() }

    val filtered = if (category == MessageSearch.Category.ALL) hits
                   else hits.filter { it.category == category }

    suspend fun loadMore() {
        val iter = iterator ?: return
        isLoading = true
        try {
            val batch: List<GlobalSearchResult>
            try {
                // null = no more results, distinct from a request failure.
                val next = iter.nextEvents()
                if (next == null) {
                    canLoadMore = false
                    return
                }
                batch = next
            } catch (error: Exception) {
                canLoadMore = false
                if (hits.isEmpty()) searchError = error.message ?: error.toString()
                return
            }
            canLoadMore = batch.isNotEmpty()
            hits = hits + batch.map { MessageSearch.hit(roomId = it.roomId, event = it.result) }
            // Resolve names once per new room here (untracked by rows) rather
            // than per-row during rendering.
            for (id in batch.map { it.roomId }.toSet()) {
                if (roomNames[id] == null) {
                    roomList.rooms.value.firstOrNull { it.id == id }?.let { roomNames[id] = it.name }
                }
            }
        } finally {
            isLoading = false
        }
    }

    suspend fun startSearch() {
        val trimmed = searchText.trim()
        // Reset for a fresh query; re-runs on every edit via the debounce.
        hits = emptyList()
        // The FFI iterator owns a Rust handle; iOS drops it via ARC.
        iterator?.destroy()
        iterator = null
        canLoadMore = false
        searchError = null
        if (trimmed.isEmpty()) return
        isLoading = true
        try {
            iterator = scope.service.client.searchMessages(trimmed, SearchRoomFilter.ROOMS, 40u)
            loadMore()
        } catch (error: Exception) {
            canLoadMore = false
            searchError = error.message ?: error.toString()
        } finally {
            isLoading = false
        }
    }

    // Initial search on appear, then restarted a beat after typing stops
    // (iOS `.task` + 350 ms debounce).
    var isFirstSearch by remember { mutableStateOf(true) }
    LaunchedEffect(searchText) {
        if (isFirstSearch) isFirstSearch = false else delay(350)
        startSearch()
    }
    DisposableEffect(Unit) {
        onDispose { iterator?.destroy() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgApp),
    ) {
        // Material 3 top app bar: close (X) navigation icon + centered title.
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = "Search",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search", tint = colors.textPrimary)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bgApp),
        )

        SearchCapsuleField(
            query = searchText,
            placeholder = "Search messages and media",
            modifier = Modifier.padding(horizontal = 16.dp),
            onQueryChange = { searchText = it },
        )

        Spacer(Modifier.height(8.dp))
        CategorySegmentedControl(
            selection = category,
            modifier = Modifier.padding(horizontal = 16.dp),
            onSelect = { category = it },
        )
        Spacer(Modifier.height(10.dp))

        when {
            isLoading && hits.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Searching…", fontSize = 15.sp, color = colors.textSecondary)
                }
            }
            searchError != null && hits.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(44.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Search Failed",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        searchError ?: "",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { coroutineScope.launch { startSearch() } }) {
                        Text("Try Again", color = colors.accent)
                    }
                }
            }
            filtered.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NoResultsView(query = searchText)
                }
            }
            else -> {
                // Inset-grouped list card (iOS `.insetGrouped`).
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(filtered, key = { _, hit -> hit.id }) { index, hit ->
                        val shape = when {
                            filtered.size == 1 -> RoundedCornerShape(12.dp)
                            index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            index == filtered.size - 1 ->
                                RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
                        Column(modifier = Modifier.clip(shape).background(colors.bgElevated)) {
                            SearchHitRow(hit = hit, roomName = roomNames[hit.roomId] ?: hit.roomId) {
                                // The navigation layer handles opening the room and scrolling.
                                appState.pendingEventNavigation.value =
                                    AppState.EventNavigation(roomId = hit.roomId, eventId = hit.id)
                                onDismiss()
                            }
                            if (index < filtered.size - 1) {
                                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                            }
                        }
                    }
                    if (canLoadMore) {
                        item(key = "load-more") {
                            TextButton(
                                onClick = { coroutineScope.launch { loadMore() } },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            ) {
                                Text("Load More Results", color = colors.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
