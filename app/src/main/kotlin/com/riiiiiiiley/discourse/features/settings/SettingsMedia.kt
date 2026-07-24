package com.riiiiiiiley.discourse.features.settings

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.core.NotificationPreview
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notification privacy + sound preferences: how much a lock-screen banner
 * reveals, and whether it chimes.
 */
@Composable
fun NotificationSettingsScreen(appState: AppState, onBack: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val prefs = appState.preferences
    val p by prefs.state.collectAsStateWithLifecycle()
    val accountTokens by appState.accountTokens.collectAsStateWithLifecycle()
    // Recompose hint for the per-account badges (unreadCount is a plain read
    // into the warm scopes, like the iOS computed property).
    val otherAccountsHaveUnread by appState.otherAccountsHaveUnread.collectAsStateWithLifecycle()
    // The per-account disabled-set lives outside the Snapshot flow (keyed per
    // user), so bump a local version to re-read it after each toggle.
    var notificationTogglesVersion by remember { mutableStateOf(0) }

    SettingsScaffold(title = "Notifications", onBack = onBack) {
        SettingsSection(
            header = "Show in Notifications",
            footer = "Sender and Message shows who wrote and a preview of the text. " +
                "Sender Only shows who and where, but hides the message. " +
                "Nothing reveals only that a notification arrived.",
        ) {
            NotificationPreview.entries.forEachIndexed { index, level ->
                if (index > 0) FormDivider()
                CheckRow(
                    title = level.label,
                    selected = p.notificationPreview == level,
                    onClick = { prefs.update { it.copy(notificationPreview = level) } },
                )
            }
        }

        SettingsSection {
            ToggleRow(
                title = "Play sound",
                checked = p.notificationSound,
                onCheckedChange = { on -> prefs.update { it.copy(notificationSound = on) } },
            )
        }

        // Per-account notification toggles, so each signed-in account can be
        // silenced independently. Each row also shows that account's unread.
        if (accountTokens.size > 1) {
            SettingsSection(
                header = "Accounts",
                footer = "Turn notifications on or off for each account.",
            ) {
                accountTokens.forEachIndexed { index, token ->
                    val userId = token.session.userId
                    if (index > 0) FormDivider()
                    val enabled = remember(notificationTogglesVersion, userId) {
                        prefs.notificationsEnabled(forUserId = userId)
                    }
                    ToggleRow(
                        checked = enabled,
                        onCheckedChange = { on ->
                            appState.setNotificationsEnabled(on, forUserId = userId)
                            notificationTogglesVersion += 1
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = appState.accountDisplayName(forUserId = userId),
                                    color = colors.textPrimary,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = userId,
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                            // Shown only for non-active accounts with unread,
                            // mirroring iOS.
                            val unread = remember(userId, otherAccountsHaveUnread) {
                                appState.unreadCount(forUserId = userId)
                            }
                            if (unread > 0 && userId != appState.activeUserId) {
                                Spacer(Modifier.width(8.dp))
                                UnreadBadge(count = unread)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A small pill showing an unread count (capped at 99+), for account rows and
 * tab/switcher badges.
 */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Text(
        text = if (count > 99) "99+" else "$count",
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Default,
        modifier = modifier
            .background(LocalDiscourseColors.current.unreadMention, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Media downloading + on-disk cache management. A null loader (session media
 * wiring not attached yet) reads as an empty cache with Clear disabled — the
 * same states iOS shows for an empty cache.
 */
@Composable
fun StorageSettingsScreen(
    prefs: Preferences,
    onBack: () -> Unit,
    loader: MediaLoader? = null,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val p by prefs.state.collectAsStateWithLifecycle()
    val runScope = rememberCoroutineScope()

    // null while the first measurement is in flight.
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var isMeasuring by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    suspend fun measure() {
        isMeasuring = true
        cacheSize = loader?.totalDiskCacheSize() ?: 0L
        isMeasuring = false
    }

    LaunchedEffect(Unit) { measure() }

    SettingsScaffold(title = "Storage", onBack = onBack) {
        SettingsSection(
            footer = "When off, images wait behind a tap before downloading. Stickers always load.",
        ) {
            ToggleRow(
                title = "Auto-download images",
                checked = p.autoDownloadImages,
                onCheckedChange = { on -> prefs.update { it.copy(autoDownloadImages = on) } },
            )
        }

        SettingsSection(header = "Cache") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(text = "Image Cache", color = colors.textPrimary, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                if (isMeasuring && cacheSize == null) {
                    CircularProgressIndicator(
                        color = colors.textSecondary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = Formatter.formatFileSize(context, cacheSize ?: 0L),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                    )
                }
            }
            FormDivider()
            ButtonRow(
                title = "Clear Cache",
                destructive = true,
                enabled = !isClearing && (cacheSize ?: 0L) != 0L,
                onClick = {
                    isClearing = true
                    loader?.clearCache()
                    // Deletion is fire-and-forget off-main; re-measure shortly after.
                    runScope.launch {
                        delay(300)
                        measure()
                        isClearing = false
                    }
                },
            )
        }
    }
}
