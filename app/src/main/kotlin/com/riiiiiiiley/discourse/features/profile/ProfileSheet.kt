package com.riiiiiiiley.discourse.features.profile

import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.LocalPresenceService
import com.riiiiiiiley.discourse.core.LocalPronounsStore
import com.riiiiiiiley.discourse.core.MatrixService
import com.riiiiiiiley.discourse.core.PresenceIndicator
import com.riiiiiiiley.discourse.core.PresenceService
import com.riiiiiiiley.discourse.features.roomlist.RoomListViewModel
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class ProfileTarget(
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
) {
    val id: String get() = userId
}

/**
 * mxc thumbnail access for profile imagery (banner, avatar, link icons). The
 * media phase provides the real implementation (adapting `MediaLoader.avatar`);
 * until then the null default renders placeholders — the analogue of the iOS
 * optional `\.mediaLoader` environment.
 */
fun interface MxcThumbnailLoader {
    suspend fun load(mxcUrl: String, pixelSize: Int): ImageBitmap?
}

val LocalMxcThumbnailLoader = staticCompositionLocalOf<MxcThumbnailLoader?> { null }

/** A wide banner image loaded from an mxc URL through the media loader. */
@Composable
fun BannerImageView(mxcUrl: String, modifier: Modifier = Modifier) {
    val loader = LocalMxcThumbnailLoader.current
    val colors = LocalDiscourseColors.current
    var image by remember(mxcUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mxcUrl, loader) {
        image = loader?.load(mxcUrl, 700)
    }
    Box(modifier.clipToBounds()) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Box(Modifier.matchParentSize().background(colors.bgElevated2))
    }
}

/**
 * Initials-on-color avatar with an mxc image when it loads — the profile-local
 * stand-in for the shared room avatar (swap for the room-list slice's avatar
 * component when it lands). Same fallback rule as iOS RoomAvatarView: first
 * letters of the first two words on a name-hashed palette color.
 */
@Composable
private fun ProfileAvatarView(
    name: String,
    size: Dp,
    avatarUrl: String?,
    ringColor: Color? = null,
    ringWidth: Dp = 0.dp,
) {
    val loader = LocalMxcThumbnailLoader.current
    val density = LocalDensity.current
    var image by remember(avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(avatarUrl, loader) {
        image = if (avatarUrl != null && loader != null) {
            loader.load(avatarUrl, with(density) { (size * 2).roundToPx() })
        } else {
            null
        }
    }
    val ring = if (ringColor != null && ringWidth > 0.dp) {
        Modifier.border(ringWidth, ringColor, CircleShape)
    } else {
        Modifier
    }
    Box(
        Modifier.size(size).clip(CircleShape).then(ring),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val base = fallbackColor(name)
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.verticalGradient(listOf(base, darken(base, 0.15f)))),
            )
            Text(
                text = initials(name),
                fontSize = with(density) { (size * 0.42f).toSp() },
                fontWeight = FontWeight.Medium,
                color = Color.White,
            )
        }
    }
}

private fun initials(name: String): String {
    val cleaned = name.trim { it in "#@!+ " }
    val letters = cleaned.split(' ').filter { it.isNotEmpty() }.take(2).mapNotNull { it.firstOrNull() }
    return if (letters.isEmpty()) "?" else letters.joinToString("").uppercase()
}

/** iOS system palette, same name-hash as RoomAvatarView. */
private val avatarPalette = listOf(
    Color(0xFF007AFF), Color(0xFF5856D6), Color(0xFFAF52DE), Color(0xFFFF2D55),
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFF30B0C7), Color(0xFF34C759),
)

private fun fallbackColor(name: String): Color {
    var hash = 0
    name.codePoints().forEach { cp -> hash = hash * 31 + cp }
    return avatarPalette[Math.floorMod(hash, avatarPalette.size)]
}

private fun darken(color: Color, amount: Float): Color = Color(
    red = color.red * (1 - amount),
    green = color.green * (1 - amount),
    blue = color.blue * (1 - amount),
    alpha = color.alpha,
)

/**
 * A tappable `foxchat.social_links` entry: an optional icon, the title, and an
 * external-link chevron. Opens the link in the browser.
 */
@Composable
private fun SocialLinkRow(link: MatrixService.SocialLink) {
    val colors = LocalDiscourseColors.current
    val loader = LocalMxcThumbnailLoader.current
    val uriHandler = LocalUriHandler.current
    var icon by remember(link.img) { mutableStateOf<ImageBitmap?>(null) }
    // Only mxc icons load through the media loader; http(s) icons are skipped
    // (remote-fetch parity with the web CSP), falling back to the link glyph.
    LaunchedEffect(link.img, loader) {
        val img = link.img
        if (img != null && img.startsWith("mxc://")) icon = loader?.load(img, 40)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated2)
            .clickable { runCatching { uriHandler.openUri(link.link) } }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = icon
            val emoji = link.img
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                !emoji.isNullOrEmpty() && !emoji.startsWith("mxc://") ->
                    Text(emoji, fontSize = 13.sp)  // unicode emoji icon
                else -> Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.textSecondary,
                )
            }
        }
        Text(
            text = link.title,
            fontSize = 16.sp,
            color = colors.textPrimary,
            maxLines = 1,
            // iOS truncates the middle; Compose only offers tail ellipsis.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Outlined.ArrowOutward,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = colors.textTertiary,
        )
    }
}

/** A room/space shared with the profile's user. */
data class MutualRef(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val isSpace: Boolean,
)

/** The list opened by a "Mutual …" button. */
private data class MutualList(
    val title: String,
    val refs: List<MutualRef>,
)

/** A full-screen list of shared rooms/spaces, opened from a "Mutual …" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MutualRoomsList(
    title: String,
    refs: List<MutualRef>,
    open: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDone) {
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
        LazyColumn {
            items(refs, key = { it.id }) { ref ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open(ref.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatarView(name = ref.name, size = 30.dp, avatarUrl = ref.avatarUrl)
                    Text(
                        text = ref.name,
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

/**
 * Compact user profile: banner + avatar header, names, Commet profile fields,
 * mutual rooms, message/copy actions. Presented as a modal bottom sheet (the
 * iOS `.medium`/`.large` detent sheet).
 *
 * `message` starts (or opens) a DM and navigates to it; false when creation
 * failed. `roomList` resolves mutual-room IDs against our own room list —
 * pass the active session's RoomListViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(
    target: ProfileTarget,
    ownUserId: String,
    appState: AppState,
    roomList: RoomListViewModel? = null,
    message: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val pronounsStore = LocalPronounsStore.current
    val presence = LocalPresenceService.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    // Collected (value read) so the sheet recomposes when a profile fetch or
    // presence poll lands; the getters below read the same backing state.
    pronounsStore?.cache?.collectAsStateWithLifecycle()?.value
    presence?.entries(target.userId)?.collectAsStateWithLifecycle()?.value

    var isMessaging by remember { mutableStateOf(false) }
    var messageError by remember { mutableStateOf<String?>(null) }
    var mutualSpaces by remember { mutableStateOf(listOf<MutualRef>()) }
    var mutualRooms by remember { mutableStateOf(listOf<MutualRef>()) }
    var mutualList by remember { mutableStateOf<MutualList?>(null) }

    // Loads shared rooms/spaces (MSC2666) and resolves them against our own
    // room list, split into spaces vs (non-DM) rooms.
    LaunchedEffect(target.userId) {
        val active = appState.phase.value as? AppState.Phase.Active
        if (active == null || target.userId == ownUserId) {
            mutualSpaces = emptyList(); mutualRooms = emptyList()
            return@LaunchedEffect
        }
        val ids = active.scope.service.mutualRooms(with = target.userId).toSet()
        if (ids.isEmpty()) {
            mutualSpaces = emptyList(); mutualRooms = emptyList()
            return@LaunchedEffect
        }
        mutualSpaces = roomList?.spaces?.value.orEmpty()
            .filter { it.id in ids }
            .map { MutualRef(id = it.id, name = it.name, avatarUrl = it.avatarUrl, isSpace = true) }
        mutualRooms = roomList?.rooms?.value.orEmpty()
            .filter { it.id in ids && !it.isSpace && !it.isDirect }
            .map { MutualRef(id = it.id, name = it.name, avatarUrl = it.avatarUrl, isSpace = false) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Half-height first like the iOS .medium detent; drag up for .large.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = colors.bgElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: a full-width banner (or accent gradient) with the avatar
            // overlapping its bottom edge, ringed against the sheet background —
            // the familiar social-profile look.
            Box(Modifier.fillMaxWidth().height(BANNER_HEIGHT + AVATAR_OVERLAP)) {
                val banner = pronounsStore?.bannerUrl(target.userId)
                if (banner != null) {
                    BannerImageView(
                        mxcUrl = banner,
                        modifier = Modifier.fillMaxWidth().height(BANNER_HEIGHT),
                    )
                } else {
                    // Accent gradient fallback (iOS masks .tint at 35%→10%).
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(BANNER_HEIGHT)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        colors.accent.copy(alpha = 0.35f),
                                        colors.accent.copy(alpha = 0.10f),
                                    ),
                                ),
                            ),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = BANNER_HEIGHT - AVATAR_OVERLAP),
                ) {
                    PresenceIndicator(userId = target.userId, size = 18.dp, ringColor = colors.bgElevated) {
                        ProfileAvatarView(
                            name = target.displayName ?: target.userId,
                            size = 92.dp,
                            avatarUrl = target.avatarUrl,
                            ringColor = colors.bgElevated,
                            ringWidth = 4.dp,
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Name, pronouns, handle, status, presence and local time.
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = target.displayName ?: target.userId,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        pronounsStore?.pronouns(target.userId)?.let { pronouns ->
                            Text(
                                text = pronouns,
                                fontSize = 15.sp,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = target.userId,
                            fontSize = 16.sp,
                            color = colors.textSecondary,
                        )
                    }
                    pronounsStore?.status(target.userId)?.takeIf { it.isNotEmpty() }?.let { status ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Icon(
                                Icons.Filled.FormatQuote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.textSecondary,
                            )
                            Text(status, fontSize = 16.sp, color = colors.textSecondary)
                        }
                    }
                    if (presence != null) {
                        presence.detailText(of = target.userId)?.let { detail ->
                            Text(
                                text = detail,
                                fontSize = 16.sp,
                                color = if (presence.state(of = target.userId) == PresenceService.State.ONLINE) {
                                    colors.presenceOnline
                                } else {
                                    colors.textSecondary
                                },
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    pronounsStore?.timezone(target.userId)?.let { tz ->
                        localTime(tz)?.let { local ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = colors.textSecondary,
                                )
                                Text(local, fontSize = 16.sp, color = colors.textSecondary)
                            }
                        }
                    }
                }

                // Bio card.
                pronounsStore?.bio(target.userId)?.let { bio ->
                    SelectionContainer {
                        Text(
                            text = bio,
                            fontSize = 16.sp,
                            color = colors.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.bgElevated2)
                                .padding(12.dp),
                        )
                    }
                }

                // Social links.
                val links = pronounsStore?.socialLinks(target.userId).orEmpty()
                if (links.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        links.forEach { link -> SocialLinkRow(link) }
                    }
                }

                // Mutual rooms/spaces: "Mutual …(N)" buttons that open the full
                // list in their own sheet, so the profile card stays compact
                // even with lots of shared rooms.
                if (mutualSpaces.isNotEmpty() || mutualRooms.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (mutualSpaces.isNotEmpty()) {
                            MutualButton("Mutual Spaces", Icons.Outlined.Layers, mutualSpaces) {
                                mutualList = MutualList("Mutual Spaces", mutualSpaces)
                            }
                        }
                        if (mutualRooms.isNotEmpty()) {
                            MutualButton("Mutual Rooms", Icons.Outlined.Tag, mutualRooms) {
                                mutualList = MutualList("Mutual Rooms", mutualRooms)
                            }
                        }
                    }
                }

                // Side by side; iOS falls back to a stack via ViewThatFits at
                // accessibility type sizes — Compose splits the width instead.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (target.userId != ownUserId) {
                        Button(
                            onClick = {
                                if (isMessaging) return@Button
                                isMessaging = true
                                messageError = null
                                coroutineScope.launch {
                                    val ok = message(target.userId)
                                    if (ok) {
                                        onDismiss()
                                    } else {
                                        isMessaging = false
                                        messageError = "Couldn't start the conversation."
                                    }
                                }
                            },
                            enabled = !isMessaging,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accent,
                                contentColor = colors.textOnAccent,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Message")
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("User ID", target.userId)),
                                )
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.bgElevated2,
                            contentColor = colors.textPrimary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy User ID")
                    }
                }

                messageError?.let { error ->
                    Text(text = error, fontSize = 12.sp, color = colors.unreadMention)
                }
            }
        }
    }

    // The "Mutual …" list, stacked over the profile sheet.
    mutualList?.let { list ->
        ModalBottomSheet(
            onDismissRequest = { mutualList = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = colors.bgElevated,
        ) {
            MutualRoomsList(
                title = list.title,
                refs = list.refs,
                open = { id ->
                    appState.pendingRoomNavigation.value = id
                    mutualList = null
                    onDismiss()
                },
                onDone = { mutualList = null },
            )
        }
    }
}

private val BANNER_HEIGHT = 112.dp
private val AVATAR_OVERLAP = 46.dp

/** A "Mutual …(N)" row button. */
@Composable
private fun MutualButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    refs: List<MutualRef>,
    onClick: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.bgElevated2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.textSecondary,
        )
        Text(title, fontSize = 16.sp, color = colors.textPrimary)
        Text("${refs.size}", fontSize = 16.sp, color = colors.textTertiary)
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.textTertiary,
        )
    }
}

/** The user's current local time, from their `m.tz` IANA timezone. */
private fun localTime(timezoneId: String): String? {
    val zone = runCatching { ZoneId.of(timezoneId) }.getOrNull() ?: return null
    val now = ZonedDateTime.now(zone)
    val time = now.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    // Short zone name ("PST" / "GMT+2"), the analogue of iOS `abbreviation()`.
    val abbreviation = runCatching { now.format(DateTimeFormatter.ofPattern("zzz")) }
        .getOrNull() ?: timezoneId
    return "$time local time ($abbreviation)"
}
