package com.riiiiiiiley.discourse.features.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import com.riiiiiiiley.discourse.BuildConfig
import com.riiiiiiiley.discourse.core.UpdateChecker
import com.riiiiiiiley.discourse.core.UpdateInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors

/**
 * Developer-facing controls, the session identity readout, and a reset-everything
 * escape hatch.
 */
@Composable
fun AdvancedSettingsScreen(scope: SessionScope, prefs: Preferences, onBack: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val p by prefs.state.collectAsStateWithLifecycle()
    var showsResetConfirm by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        SettingsSection(header = "Session") {
            LabeledValueRow(label = "User ID", value = scope.userId)
            FormDivider()
            LabeledValueRow(label = "Homeserver", value = scope.token.session.homeserverUrl)
            FormDivider()
            LabeledValueRow(label = "Device ID", value = scope.token.session.deviceId)
        }

        SettingsSection(
            header = "Developer",
            footer = "Displays raw Matrix event IDs beneath messages. Useful for debugging.",
        ) {
            ToggleRow(
                title = "Show event IDs",
                checked = p.showEventIds,
                onCheckedChange = { on -> prefs.update { it.copy(showEventIds = on) } },
            )
        }

        SettingsSection(
            footer = "Restores every customization option to its default. Your account and messages are not affected.",
        ) {
            ButtonRow(title = "Reset All Settings", destructive = true) {
                showsResetConfirm = true
            }
        }
    }

    if (showsResetConfirm) {
        AlertDialog(
            onDismissRequest = { showsResetConfirm = false },
            containerColor = colors.bgElevated2,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Reset all settings?") },
            text = { Text("Every customization returns to its default value. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showsResetConfirm = false
                    prefs.resetToDefaults()
                }) {
                    Text("Reset All Settings", color = colors.unreadMention)
                }
            },
            dismissButton = {
                TextButton(onClick = { showsResetConfirm = false }) {
                    Text("Cancel", color = colors.accent)
                }
            },
        )
    }
}

/** App identity, version, and links out to the Matrix project. */
@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current

    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val version = packageInfo?.versionName ?: "—"
    val build = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it).toString() } ?: "—"

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    SettingsScaffold(title = "About", onBack = onBack) {
        SettingsSection {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Forum,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Discourse",
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = "A Matrix client", color = colors.textSecondary, fontSize = 15.sp)
                Text(text = "by FulltimeFeline", color = colors.textTertiary, fontSize = 13.sp)
            }
        }

        SettingsSection(header = "Version") {
            LabeledValueRow(label = "Version", value = version)
            FormDivider()
            LabeledValueRow(label = "Build", value = build)
            FormDivider()
            UpdateCheckRow()
        }

        SettingsSection(
            footer = "Discourse speaks the open Matrix protocol for secure, decentralized messaging.",
        ) {
            LinkRow(icon = Icons.Filled.Code, title = "Source on GitHub") {
                open("https://github.com/FulltimeFeline/Discourse")
            }
            FormDivider()
            LinkRow(icon = Icons.Filled.Language, title = "About the Matrix Protocol") {
                open("https://matrix.org")
            }
            FormDivider()
            LinkRow(icon = Icons.Filled.Description, title = "Matrix Specification") {
                open("https://spec.matrix.org")
            }
        }
    }
}

/**
 * Manual "Check for Updates" against the GitHub releases: taps to check, shows
 * an inline status, and offers the download+install dialog when one is found.
 */
@Composable
private fun UpdateCheckRow() {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking) {
                checking = true
                status = null
                scope.launch {
                    val found = runCatching { UpdateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
                    checking = false
                    if (found != null) update = found
                    else status = "You're on the latest version."
                }
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = "Check for Updates", color = colors.accent, fontSize = 16.sp)
            status?.let { Text(it, color = colors.textSecondary, fontSize = 13.sp) }
        }
        if (checking) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.textSecondary,
            )
        }
    }

    val current = update
    if (current != null) {
        val downloading = progress != null
        AlertDialog(
            onDismissRequest = { if (!downloading) update = null },
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Version ${current.versionName} is available " +
                            "(you have ${BuildConfig.VERSION_NAME}).",
                        color = colors.textPrimary,
                    )
                    if (current.notes.isNotBlank()) {
                        Text(
                            current.notes.take(500),
                            color = colors.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    progress?.let {
                        LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !downloading,
                    onClick = {
                        if (!UpdateChecker.canInstall(context)) {
                            UpdateChecker.requestInstallPermission(context)
                            return@TextButton
                        }
                        scope.launch {
                            progress = 0f
                            val apk = UpdateChecker.download(context, current) { progress = it }
                            progress = null
                            if (apk != null) UpdateChecker.install(context, apk)
                            else status = "Download failed. Try again."
                            update = null
                        }
                    },
                ) { Text(if (downloading) "Downloading…" else "Update") }
            },
            dismissButton = {
                if (!downloading) TextButton(onClick = { update = null }) { Text("Later") }
            },
            containerColor = colors.bgElevated,
        )
    }
}

/** External-link row: accent icon + accent title, opening the browser. */
@Composable
private fun LinkRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text = title, color = colors.accent, fontSize = 16.sp)
    }
}
