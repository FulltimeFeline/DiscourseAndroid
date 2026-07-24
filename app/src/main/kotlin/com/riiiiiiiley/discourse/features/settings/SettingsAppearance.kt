package com.riiiiiiiley.discourse.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.AccentChoice
import com.riiiiiiiley.discourse.core.AppearanceMode
import com.riiiiiiiley.discourse.core.MessageDensity
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlin.math.roundToInt

/** Theme, accent, density, chat text size, and timeline-display toggles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(prefs: Preferences, onBack: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val p by prefs.state.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Appearance", onBack = onBack) {
        SettingsSection(header = "Theme") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SegmentedPicker(
                    options = AppearanceMode.entries.map { it.label },
                    selectedIndex = AppearanceMode.entries.indexOf(p.appearance),
                    onSelect = { index ->
                        prefs.update { it.copy(appearance = AppearanceMode.entries[index]) }
                    },
                )
            }
        }

        SettingsSection(
            header = "Accent Color",
            footer = "Washes the window background with the accent color; off keeps the system gray.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                for (choice in availableAccentChoices) {
                    AccentSwatch(
                        choice = choice,
                        isSelected = p.accentColor == choice,
                        select = { prefs.update { it.copy(accentColor = choice) } },
                    )
                }
            }
        }

        SettingsSection(header = "Message Density") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                SegmentedPicker(
                    options = MessageDensity.entries.map { it.label },
                    selectedIndex = MessageDensity.entries.indexOf(p.messageDensity),
                    onSelect = { index ->
                        prefs.update { it.copy(messageDensity = MessageDensity.entries[index]) }
                    },
                )
            }
        }

        SettingsSection(
            header = "Chat Text Size",
            footer = "Scales message text on top of the system text size.",
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "The quick brown fox jumps over the lazy dog.",
                    color = colors.textSecondary,
                    fontSize = (17f * p.chatFontScale).sp,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.TextDecrease,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    val percent = (p.chatFontScale * 100).roundToInt()
                    Slider(
                        value = p.chatFontScale,
                        onValueChange = { raw ->
                            // Snap to the iOS slider's 0.05 step to keep values clean.
                            val stepped = (raw / 0.05f).roundToInt() * 0.05f
                            prefs.update { it.copy(chatFontScale = stepped.coerceIn(0.8f, 1.4f)) }
                        },
                        valueRange = 0.8f..1.4f,
                        steps = 11,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.bgInput,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Chat Text Size, $percent percent" },
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Filled.TextIncrease,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        SettingsSection(footer = "Changes apply to the timeline immediately.") {
            ToggleRow(
                title = "Show avatars in timeline",
                checked = p.showAvatarsInTimeline,
                onCheckedChange = { on -> prefs.update { it.copy(showAvatarsInTimeline = on) } },
            )
            FormDivider()
            ToggleRow(
                title = "Colored sender names",
                checked = p.coloredSenderNames,
                onCheckedChange = { on -> prefs.update { it.copy(coloredSenderNames = on) } },
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    choice: AccentChoice,
    isSelected: Boolean,
    select: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val swatch = choice.resolvedAccent(darkTheme = colors.isDark)
    val label = choice.label
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 22.dp),
                onClick = select,
            )
            .semantics {
                contentDescription = label
                selected = isSelected
            },
    ) {
        // Selection ring outside the inner border, so it reads against the swatch.
        Box(
            Modifier
                .size(38.dp)
                .border(
                    width = 2.dp,
                    color = if (isSelected) colors.textPrimary else Color.Transparent,
                    shape = CircleShape,
                ),
        )
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(width = 2.dp, color = colors.bgElevated, shape = CircleShape),
        )
    }
}

/** Chat behavior toggles: emoji rendering, time format, timestamps, receipts. */
@Composable
fun ChatSettingsScreen(prefs: Preferences, onBack: () -> Unit) {
    val p by prefs.state.collectAsStateWithLifecycle()

    SettingsScaffold(title = "Chat", onBack = onBack) {
        SettingsSection(
            header = "Emoji",
            footer = "Jumbo emoji enlarges messages that are only emoji.",
        ) {
            ToggleRow(
                title = "Jumbo emoji",
                checked = p.jumboEmoji,
                onCheckedChange = { on -> prefs.update { it.copy(jumboEmoji = on) } },
            )
        }

        SettingsSection(
            header = "Time",
            footer = "Always show timestamps displays the time on every message, not just on hover.",
        ) {
            ToggleRow(
                title = "24-hour time",
                checked = p.use24HourTime,
                onCheckedChange = { on -> prefs.update { it.copy(use24HourTime = on) } },
            )
            FormDivider()
            ToggleRow(
                title = "Always show timestamps",
                checked = p.alwaysShowTimestamps,
                onCheckedChange = { on -> prefs.update { it.copy(alwaysShowTimestamps = on) } },
            )
        }

        SettingsSection(footer = "Shows who has read up to each message in the timeline.") {
            ToggleRow(
                title = "Show read receipts",
                checked = p.showReadReceipts,
                onCheckedChange = { on -> prefs.update { it.copy(showReadReceipts = on) } },
            )
        }
    }
}
