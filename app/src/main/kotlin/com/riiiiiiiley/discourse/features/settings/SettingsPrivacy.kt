package com.riiiiiiiley.discourse.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.Preferences

/**
 * Privacy controls: each toggle gates a real outbound signal (receipts, typing,
 * presence, media metadata) sent to the homeserver.
 */
@Composable
fun PrivacySettingsScreen(prefs: Preferences, onBack: () -> Unit) {
    val p by prefs.state.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Privacy & Security", onBack = onBack) {
        SettingsSection(
            header = "Read Receipts",
            footer = "When off, others won't see when you've read their messages. Your own unread markers still clear as you read.",
        ) {
            ToggleRow(
                title = "Send read receipts",
                checked = p.sendReadReceipts,
                onCheckedChange = { on -> prefs.update { it.copy(sendReadReceipts = on) } },
            )
        }

        SettingsSection(
            header = "Typing",
            footer = "When off, people won't see a “typing…” indicator while you compose.",
        ) {
            ToggleRow(
                title = "Send typing notifications",
                checked = p.sendTypingNotifications,
                onCheckedChange = { on -> prefs.update { it.copy(sendTypingNotifications = on) } },
            )
        }

        SettingsSection(
            header = "Presence",
            footer = "Shares your online status with people you're in rooms with. You can still see theirs.",
        ) {
            ToggleRow(
                title = "Share presence",
                checked = p.sharePresence,
                onCheckedChange = { on -> prefs.update { it.copy(sharePresence = on) } },
            )
        }

        SettingsSection(
            header = "Encryption",
            footer = "Shows a notice above the composer when a room isn't end-to-end encrypted.",
        ) {
            ToggleRow(
                title = "Warn in unencrypted rooms",
                checked = p.warnUnencrypted,
                onCheckedChange = { on -> prefs.update { it.copy(warnUnencrypted = on) } },
            )
        }

        SettingsSection(
            header = "Media",
            footer = "Strips GPS location metadata from photos before sending. Leave on unless you specifically want to share where a photo was taken.",
        ) {
            ToggleRow(
                title = "Remove location from photos",
                checked = p.stripLocationMetadata,
                onCheckedChange = { on -> prefs.update { it.copy(stripLocationMetadata = on) } },
            )
        }
    }
}

/**
 * Accessibility and confirmation preferences, layered on top of the system
 * accessibility settings (only ever adding caution, never relaxing the OS).
 */
@Composable
fun AccessibilitySettingsScreen(prefs: Preferences, onBack: () -> Unit) {
    val p by prefs.state.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Accessibility", onBack = onBack) {
        SettingsSection(
            header = "Accessibility",
            footer = "These apply on top of your system accessibility settings.",
        ) {
            ToggleRow(
                title = "Reduce motion",
                checked = p.reduceTimelineMotion,
                onCheckedChange = { on -> prefs.update { it.copy(reduceTimelineMotion = on) } },
            )
            FormDivider()
            ToggleRow(
                title = "Larger tap targets",
                checked = p.largerTapTargets,
                onCheckedChange = { on -> prefs.update { it.copy(largerTapTargets = on) } },
            )
        }

        // iOS also offers "Return key sends message" here, macOS-only; phones
        // (like this port) send via the composer's send button instead.
        SettingsSection(header = "Behavior") {
            ToggleRow(
                title = "Confirm before deleting messages",
                checked = p.confirmBeforeDeleting,
                onCheckedChange = { on -> prefs.update { it.copy(confirmBeforeDeleting = on) } },
            )
        }
    }
}
