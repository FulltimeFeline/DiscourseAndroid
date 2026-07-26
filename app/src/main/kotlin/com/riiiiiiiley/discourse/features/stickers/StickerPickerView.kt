package com.riiiiiiiley.discourse.features.stickers

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.core.StickerStore
import com.riiiiiiiley.discourse.core.StickerUsage
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteAssetLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteImageView
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// Pack-bar tab metrics: keyboard-sized touch targets (the iOS phone values).
private val tabWidth = 44.dp
private val tabHeight = 36.dp
private val tabIconSize = 16.dp
private val tabThumbSize = 26.dp

/** Sentinel section key for the recents tab (iOS `"\u{0}recents"`). */
private const val recentsTab = "\u0000recents"

/** One grid slot: a section title (full-row) or a tappable sticker. */
private sealed class StickerCell {
    data class Header(val key: String, val title: String) : StickerCell()
    data class Personal(val sticker: StickerStore.Sticker) : StickerCell()
    data class Pack(val emote: CustomEmojiStore.Emote) : StickerCell()
}

/**
 * Grid popover of the user's sticker packs plus any room/space sticker packs
 * (MSC2545 `usage: ["sticker"]`).
 */
@Composable
fun StickerPickerView(
    store: StickerStore,
    loader: MediaLoader,
    customEmoji: CustomEmojiStore? = null,
    send: (StickerStore.Sticker) -> Unit,
    sendPackSticker: ((CustomEmojiStore.Emote) -> Unit)? = null,
    /** Reports search-field focus so the expression panel can coexist with the keyboard. */
    onSearchFocusChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val stickers by store.stickers.collectAsStateWithLifecycle()
    val customPacks by remember(customEmoji) {
        customEmoji?.packs ?: MutableStateFlow<List<CustomEmojiStore.Pack>>(emptyList())
    }.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        store.load()
        customEmoji?.refreshIfStale()
    }

    // Room/space packs join only when the caller can send them.
    val roomPacks = remember(customPacks, sendPackSticker == null) {
        if (sendPackSticker == null) emptyList()
        else customPacks.filter { it.roomId != null && it.stickers.isNotEmpty() }
    }

    var query by remember { mutableStateOf("") }
    var selectedPack by remember { mutableStateOf<String?>(null) }
    /** Bumped per sent sticker: fires the send haptic and refreshes recents (the panel stays up for chaining). */
    var sendCount by remember { mutableIntStateOf(0) }

    val trimmedQuery = query.trim()

    val recentStickers = remember(stickers, sendCount) {
        StickerUsage.recents(context).mapNotNull { shortcode ->
            stickers.firstOrNull { it.shortcode == shortcode }
        }
    }
    /** Personal pack names in display order. */
    val packNames = remember(stickers) {
        val seen = mutableListOf<String>()
        for (sticker in stickers) if (sticker.pack !in seen) seen.add(sticker.pack)
        seen
    }

    if (stickers.isEmpty() && roomPacks.isEmpty()) {
        // iOS ContentUnavailableView("No stickers yet", …).
        Column(
            modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.EmojiEmotions, contentDescription = null,
                tint = colors.textTertiary, modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("No stickers yet", color = colors.textPrimary,
                 fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Make some in Settings → Stickers.", color = colors.textSecondary,
                 fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }

    val emoteLoader = remember(loader) {
        object : EmoteAssetLoader {
            override fun cachedImage(mxcUrl: String, pixelSize: Float): Bitmap? =
                loader.cachedImage(mxcUrl, pixelSize)
            override suspend fun avatar(mxcUrl: String, pixelSize: Float): Bitmap? =
                loader.avatar(mxcUrl, pixelSize)
        }
    }

    val searchResults = remember(stickers, trimmedQuery) {
        stickers.filter {
            it.shortcode.contains(trimmedQuery, ignoreCase = true) ||
                it.body.contains(trimmedQuery, ignoreCase = true)
        }
    }
    val packSearchResults = remember(roomPacks, trimmedQuery) {
        roomPacks.flatMap { it.stickers }.filter {
            it.shortcode.contains(trimmedQuery, ignoreCase = true) ||
                it.body.contains(trimmedQuery, ignoreCase = true)
        }
    }

    // One continuous scroll: recents, then each pack as a titled section.
    // Search mode drops the headers (iOS swaps the LazyVStack content).
    val cells = remember(stickers, packNames, roomPacks, recentStickers, trimmedQuery,
                         searchResults, packSearchResults) {
        buildList {
            if (trimmedQuery.isNotEmpty()) {
                searchResults.forEach { add(StickerCell.Personal(it)) }
                packSearchResults.forEach { add(StickerCell.Pack(it)) }
            } else {
                if (recentStickers.isNotEmpty()) {
                    add(StickerCell.Header(recentsTab, "Recently Used"))
                    recentStickers.forEach { add(StickerCell.Personal(it)) }
                }
                for (pack in packNames) {
                    add(StickerCell.Header(pack, pack))
                    stickers.filter { it.pack == pack }.forEach { add(StickerCell.Personal(it)) }
                }
                for (pack in roomPacks) {
                    add(StickerCell.Header(pack.id, pack.displayName))
                    pack.stickers.forEach { add(StickerCell.Pack(it)) }
                }
            }
        }
    }
    val headerIndexByKey = remember(cells) {
        buildMap {
            cells.forEachIndexed { index, cell ->
                if (cell is StickerCell.Header) put(cell.key, index)
            }
        }
    }
    // Section of each cell, so the bar highlight can follow the scroll without
    // per-frame layout queries (the iOS HeaderPositionBox analogue).
    val sectionOfCell = remember(cells) {
        var current: String? = null
        cells.map { cell ->
            if (cell is StickerCell.Header) current = cell.key
            current
        }
    }

    val gridState = rememberLazyGridState()
    // A new query rebuilds `cells`, but the grid restores position by key: the
    // previously-visible key is gone, so the stored index survives and measure
    // clamps it to the tail of the match list. Guarded on the first
    // composition so a rotation's restored position isn't wiped.
    var lastQuery by remember { mutableStateOf(trimmedQuery) }
    LaunchedEffect(trimmedQuery) {
        if (trimmedQuery != lastQuery) {
            lastQuery = trimmedQuery
            gridState.scrollToItem(0)
        }
    }
    /** The pack whose header most recently crossed the top. */
    val derivedPack by remember(cells) {
        derivedStateOf {
            if (sectionOfCell.isEmpty()) null
            else sectionOfCell[gridState.firstVisibleItemIndex.coerceIn(0, sectionOfCell.size - 1)]
        }
    }
    // No header near the top (search mode / empty): keep the current selection.
    val scrolledPack = derivedPack
        ?: selectedPack
        ?: (if (recentStickers.isEmpty()) packNames.firstOrNull() else recentsTab)

    fun jumpTo(key: String) {
        selectedPack = key
        query = ""
        scope.launch { gridState.scrollToItem(headerIndexByKey[key] ?: 0) }
    }

    fun fireSendHaptic() {
        sendCount += 1
        // iOS .sensoryFeedback(.impact(weight: .light)).
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }

    Column(modifier.fillMaxSize()) {
        // Search field, carved out above the grid so the popover can't
        // squeeze it out.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                .background(colors.bgInput, RoundedCornerShape(16.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Outlined.Search, contentDescription = null,
                tint = colors.textSecondary, modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search", color = colors.textTertiary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    // Shortcodes aren't words.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { onSearchFocusChange?.invoke(it.isFocused) },
                )
            }
            if (query.isNotEmpty()) {
                // Real hit target so a near-miss doesn't fall through to the grid.
                Box(
                    Modifier.size(28.dp).clickable { query = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Cancel, contentDescription = "Clear search",
                        tint = colors.textSecondary, modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 76.dp),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    count = cells.size,
                    span = { index ->
                        if (cells[index] is StickerCell.Header) GridItemSpan(maxLineSpan)
                        else GridItemSpan(1)
                    },
                    contentType = { index ->
                        when (cells[index]) {
                            is StickerCell.Header -> 0
                            is StickerCell.Personal -> 1
                            is StickerCell.Pack -> 2
                        }
                    },
                ) { index ->
                    when (val cell = cells[index]) {
                        is StickerCell.Header -> Text(
                            cell.title,
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        is StickerCell.Personal -> Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    send(cell.sticker)
                                    fireSendHaptic()
                                }
                                .semantics { contentDescription = cell.sticker.body },
                            contentAlignment = Alignment.Center,
                        ) {
                            StickerThumb(sticker = cell.sticker, loader = loader, size = 72.dp)
                        }
                        is StickerCell.Pack -> Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    sendPackSticker?.invoke(cell.emote)
                                    fireSendHaptic()
                                }
                                .semantics { contentDescription = cell.emote.body },
                            contentAlignment = Alignment.Center,
                        ) {
                            EmoteImageView(url = cell.emote.url, size = 72.dp, loader = emoteLoader,
                                           contentDescription = cell.emote.body)
                        }
                    }
                }
            }
            if (trimmedQuery.isNotEmpty() && searchResults.isEmpty() && packSearchResults.isEmpty()) {
                // iOS ContentUnavailableView.search.
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.Search, contentDescription = null,
                        tint = colors.textTertiary, modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("No Results for \"$trimmedQuery\"", color = colors.textPrimary,
                         fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                         textAlign = TextAlign.Center)
                    Text("Check the spelling or try a new search.", color = colors.textSecondary,
                         fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // Pack bar: first sticker as icon. Carved out below the grid.
        Column(Modifier.fillMaxWidth().background(colors.bgElevated)) {
            HorizontalDivider(color = colors.separator)
            Spacer(Modifier.size(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                if (recentStickers.isNotEmpty()) {
                    PackTab(
                        selected = scrolledPack == recentsTab,
                        contentDescription = "Recently Used",
                        onClick = { jumpTo(recentsTab) },
                    ) {
                        Icon(
                            Icons.Outlined.Schedule, contentDescription = null,
                            tint = if (scrolledPack == recentsTab) colors.accent
                                   else colors.textSecondary,
                            modifier = Modifier.size(tabIconSize),
                        )
                    }
                }
                for (pack in packNames) {
                    PackTab(
                        selected = scrolledPack == pack,
                        contentDescription = pack,
                        onClick = { jumpTo(pack) },
                    ) {
                        val first = stickers.firstOrNull { it.pack == pack }
                        if (first != null) {
                            StickerThumb(sticker = first, loader = loader, size = tabThumbSize)
                        } else {
                            Text(pack.take(1), color = colors.textPrimary,
                                 fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                for (pack in roomPacks) {
                    PackTab(
                        selected = scrolledPack == pack.id,
                        contentDescription = pack.displayName,
                        onClick = { jumpTo(pack.id) },
                    ) {
                        val avatarUrl = pack.avatarUrl
                        if (avatarUrl != null) {
                            EmoteImageView(url = avatarUrl, size = tabThumbSize,
                                           loader = emoteLoader)
                        } else {
                            Text(pack.displayName.take(1), color = colors.textPrimary,
                                 fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
        }
    }
}

/** One pack-bar tab: fixed frame, circular highlight when its section is current. */
@Composable
private fun PackTab(
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Box(
        Modifier
            .size(width = tabWidth, height = tabHeight)
            .clip(CircleShape)
            .background(
                if (selected) colors.textPrimary.copy(alpha = 0.08f) else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A sticker thumbnail with a placeholder tile + spinner until the bitmap lands. */
@Composable
fun StickerThumb(
    sticker: StickerStore.Sticker,
    loader: MediaLoader,
    size: Dp = 72.dp,
) {
    val colors = LocalDiscourseColors.current
    val density = LocalDensity.current
    val pixelSize = with(density) { size.toPx() }
    // Synchronous cache hit so recycled cells paint on the first frame.
    var image by remember(sticker.url) {
        mutableStateOf(loader.cachedImage(sticker.url, pixelSize))
    }
    LaunchedEffect(sticker.url) {
        if (image == null) image = loader.avatar(sticker.url, pixelSize)
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val display = image
        if (display != null) {
            Image(
                bitmap = display.asImageBitmap(),
                contentDescription = sticker.body,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            )
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.textSecondary,
            )
        }
    }
}
