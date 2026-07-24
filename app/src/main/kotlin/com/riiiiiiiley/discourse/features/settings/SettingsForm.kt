package com.riiiiiiiley.discourse.features.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.core.AccentChoice
import com.riiiiiiiley.discourse.core.AppearanceMode
import com.riiiiiiiley.discourse.core.MessageDensity
import com.riiiiiiiley.discourse.core.NotificationPreview
import com.riiiiiiiley.discourse.ui.theme.AppAccent
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors

// iOS grouped-Form building blocks shared by every settings screen: rounded
// section cards on the app background, uppercase headers, tertiary footers.
// Visuals match the login form's grouped styling.

/** Full-screen settings page: centered-title bar (back chevron when nested) over a scrolling grouped form. */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgApp)
            .systemBarsPadding(),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.accent,
                    )
                }
            }
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Extra bottom clearance for the ~80dp Material tab bar that
                // overlays the Settings tab (systemBarsPadding above only covers
                // the system nav inset, not the app's NavigationBar).
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp + 80.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            content()
        }
    }
}

/** One grouped-form section: optional header/footer around a rounded card. */
@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalDiscourseColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgElevated),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                color = colors.textTertiary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
            )
        }
    }
}

/** Inset separator between rows in a section card. */
@Composable
fun FormDivider() {
    HorizontalDivider(
        color = LocalDiscourseColors.current.separator,
        thickness = 0.5.dp,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
fun discourseSwitchColors(): SwitchColors {
    val colors = LocalDiscourseColors.current
    return SwitchDefaults.colors(
        checkedThumbColor = Color.White,
        checkedTrackColor = colors.accent,
        uncheckedThumbColor = colors.textSecondary,
        uncheckedTrackColor = colors.bgInput,
        uncheckedBorderColor = colors.separator,
    )
}

/** Standard toggle row (SwiftUI `Toggle` in a Form). */
@Composable
fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ToggleRow(checked = checked, onCheckedChange = onCheckedChange) {
        Text(
            text = title,
            color = LocalDiscourseColors.current.textPrimary,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Toggle row with a custom label (account rows show name + user ID + badge). */
@Composable
fun ToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        label()
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = discourseSwitchColors())
    }
}

/** SwiftUI `LabeledContent`: label left, selectable secondary value right. */
@Composable
fun LabeledValueRow(label: String, value: String) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text = label, color = colors.textPrimary, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = value,
                color = colors.textSecondary,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** NavigationLink row: optional accent-tinted icon, title, trailing chevron. */
@Composable
fun NavRow(title: String, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text = title, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
        )
    }
}

/** Single-select row with a trailing checkmark (SwiftUI inline Picker). */
@Composable
fun CheckRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text = title, color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Form button row (destructive = red, like SwiftUI's `role: .destructive`). */
@Composable
fun ButtonRow(title: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val tint = if (destructive) colors.unreadMention else colors.accent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            color = if (enabled) tint else tint.copy(alpha = 0.4f),
            fontSize = 16.sp,
        )
    }
}

/** iOS segmented control: equal-width pills inside a recessed track. */
@Composable
fun SegmentedPicker(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bgInput)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) colors.bgElevated2 else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: Preference display metadata (labels live with the UI; the core enums
// stay presentation-free).

internal val AppearanceMode.label: String
    get() = when (this) {
        AppearanceMode.SYSTEM -> "Automatic"
        AppearanceMode.LIGHT -> "Light"
        AppearanceMode.DARK -> "Dark"
    }

internal val AccentChoice.label: String
    get() = when (this) {
        AccentChoice.APP_DEFAULT -> "Default"
        AccentChoice.SYSTEM -> "System"
        AccentChoice.BLUE -> "Blue"
        AccentChoice.INDIGO -> "Indigo"
        AccentChoice.PURPLE -> "Purple"
        AccentChoice.PINK -> "Pink"
        AccentChoice.RED -> "Red"
        AccentChoice.ORANGE -> "Orange"
        AccentChoice.YELLOW -> "Yellow"
        AccentChoice.GREEN -> "Green"
        AccentChoice.TEAL -> "Teal"
        AccentChoice.MINT -> "Mint"
        AccentChoice.BROWN -> "Brown"
        AccentChoice.GRAY -> "Graphite"
    }

internal val MessageDensity.label: String
    get() = when (this) {
        MessageDensity.COMFORTABLE -> "Comfortable"
        MessageDensity.COMPACT -> "Compact"
    }

internal val NotificationPreview.label: String
    get() = when (this) {
        NotificationPreview.FULL -> "Sender and Message"
        NotificationPreview.NAME_ONLY -> "Sender Only"
        NotificationPreview.HIDDEN -> "Nothing"
    }

/**
 * The accent swatches offered on this device. SYSTEM follows Material You's
 * dynamic color — the platform's real OS accent — so it only appears on
 * Android 12+ (mirroring iOS, where the System swatch is macOS-only because
 * iOS has no OS-wide accent to follow).
 */
internal val availableAccentChoices: List<AccentChoice>
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AccentChoice.entries.toList()
    } else {
        AccentChoice.entries.filter { it != AccentChoice.SYSTEM }
    }

/**
 * Concrete tint for an accent choice: APP_DEFAULT is the app purple, SYSTEM
 * resolves Material You's dynamic primary (falling back to the app purple
 * below Android 12). RootView uses this to feed DiscourseTheme's accent.
 */
@Composable
fun AccentChoice.resolvedAccent(darkTheme: Boolean = isSystemInDarkTheme()): Color = when (this) {
    AccentChoice.APP_DEFAULT -> AppAccent
    AccentChoice.SYSTEM -> {
        val context = LocalContext.current
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            scheme.primary
        } else {
            AppAccent
        }
    }
    else -> color ?: AppAccent
}

/** Whether an appearance mode currently means dark; RootView's theme input. */
@Composable
fun AppearanceMode.resolvesToDark(): Boolean = when (this) {
    AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
}
