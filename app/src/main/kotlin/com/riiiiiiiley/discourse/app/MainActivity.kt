package com.riiiiiiiley.discourse.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.riiiiiiiley.discourse.core.AccentChoice
import com.riiiiiiiley.discourse.core.NotificationManager
import com.riiiiiiiley.discourse.core.PushRegistrar
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiConnectedNoInternet4
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.riiiiiiiley.discourse.BuildConfig
import com.riiiiiiiley.discourse.core.UpdateChecker
import com.riiiiiiiley.discourse.core.UpdateInfo
import kotlinx.coroutines.launch
import androidx.compose.runtime.key
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.features.auth.LoginScreen
import com.riiiiiiiley.discourse.features.settings.resolvedAccent
import com.riiiiiiiley.discourse.features.settings.resolvesToDark
import com.riiiiiiiley.discourse.features.auth.WebAuthSession
import com.riiiiiiiley.discourse.ui.theme.DiscourseTheme
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors

class MainActivity : ComponentActivity() {

    private val appState get() = (application as DiscourseApplication).appState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold-start delivery of an OAuth/SSO redirect (rare: the tab's
        // launching task was killed mid-flow).
        intent?.data?.let { WebAuthSession.handleRedirect(it) }
        // Cold-start notification tap: queue the open until the session wires
        // its handlers (NotificationManager pendingActions).
        intent?.let { NotificationManager.handleIntent(it) }
        // The iOS requestAuthorization analogue; local banners need the
        // runtime grant on API 33+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent {
            RootView(appState)
        }
    }

    /** The OAuth/SSO redirect re-enters the (singleTask) activity here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { WebAuthSession.handleRedirect(it) }
        // Notification taps re-enter the running activity here (singleTask).
        NotificationManager.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Redirect intents land in onNewIntent BEFORE onResume, so a browser
        // flow still pending here means the user backed out of the tab.
        WebAuthSession.onHostResumed()
    }
}

@Composable
private fun RootView(appState: AppState) {
    val phase by appState.phase.collectAsStateWithLifecycle()
    val prefs by appState.preferences.state.collectAsStateWithLifecycle()

    // Restore the last session once; guarded internally so recomposition
    // (or activity recreation) can't restart it.
    LaunchedEffect(Unit) { appState.start() }

    // Register for self-hosted push once a session is active (idempotent;
    // a no-op until the ntfy distributor app is installed).
    val pushContext = LocalContext.current
    LaunchedEffect(phase) {
        if (phase is AppState.Phase.Active) {
            PushRegistrar.registerForPush(pushContext)
            PushRegistrar.requestBatteryExemptionOnce(pushContext)
        }
    }

    // Theme follows Preferences: "System" accent = Material You, i.e. the OS
    // wallpaper palette (API 31+); any other choice seeds a brand M3 scheme
    // from that color (APP_DEFAULT = the app purple).
    val dark = prefs.appearance.resolvesToDark()
    DiscourseTheme(
        accent = prefs.accentColor.resolvedAccent(dark),
        dynamicColor = prefs.accentColor == AccentChoice.SYSTEM,
        darkTheme = dark,
    ) {
        when (val current = phase) {
            is AppState.Phase.Launching ->
                // No spinner splash: restore is fast and a loading screen for
                // that reads as slow. The backdrop matches the chat window
                // background; chats replace it the instant restore lands.
                LaunchBackdrop()
            is AppState.Phase.LoggedOut ->
                LoginScreen(appState)
            is AppState.Phase.Disconnected ->
                DisconnectedView(appState)
            is AppState.Phase.Active ->
                // Keyed so an account switch rebuilds the whole shell (iOS
                // `.id(scope.userId)`).
                key(current.scope.userId) {
                    ProvideSessionLocals(current.scope) {
                        MainShell(appState = appState, scope = current.scope)
                    }
                }
        }

        // Add-account login, presentable over any phase (Settings → Add).
        val addAccount by appState.isAddAccountPresented.collectAsStateWithLifecycle()
        if (addAccount) {
            Dialog(
                onDismissRequest = { appState.isAddAccountPresented.value = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                LoginScreen(
                    appState = appState,
                    isSheet = true,
                    onDismiss = { appState.isAddAccountPresented.value = false },
                )
            }
        }

        // Self-update prompt (this build is sideloaded from GitHub releases).
        UpdateGate()
    }
}

/**
 * Checks the GitHub releases for a newer build on launch and, if found, offers
 * to download + install it. Sideload-only; no-op when up to date or offline.
 */
@Composable
private fun UpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        info = runCatching { UpdateChecker.check(BuildConfig.VERSION_NAME) }.getOrNull()
    }

    val update = info
    if (update == null || dismissed) return
    val colors = LocalDiscourseColors.current
    val downloading = progress != null

    AlertDialog(
        onDismissRequest = { if (!downloading) dismissed = true },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Version ${update.versionName} is available " +
                        "(you have ${BuildConfig.VERSION_NAME}).",
                    color = colors.textPrimary,
                )
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes.take(500),
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                error?.let { Text(it, color = colors.unreadMention, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    error = null
                    if (!UpdateChecker.canInstall(context)) {
                        UpdateChecker.requestInstallPermission(context)
                        return@TextButton
                    }
                    scope.launch {
                        progress = 0f
                        val apk = UpdateChecker.download(context, update) { progress = it }
                        progress = null
                        if (apk != null) UpdateChecker.install(context, apk)
                        else error = "Download failed. Try again."
                    }
                },
            ) { Text(if (downloading) "Downloading…" else "Update") }
        },
        dismissButton = {
            if (!downloading) TextButton(onClick = { dismissed = true }) { Text("Later") }
        },
        containerColor = colors.bgElevated,
    )
}

/**
 * Launch backdrop shown while the session restores: just the window
 * background, so cold launch reads as an already-open app filling in rather
 * than a loading screen.
 */
@Composable
private fun LaunchBackdrop() {
    val colors = LocalDiscourseColors.current
    Box(Modifier.fillMaxSize().background(colors.bgApp))
}

@Composable
private fun DisconnectedView(appState: AppState) {
    val colors = LocalDiscourseColors.current
    Column(
        modifier = Modifier.fillMaxSize().background(colors.bgApp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.SignalWifiConnectedNoInternet4,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Can't connect to server",
            color = colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = colors.textSecondary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Trying to reconnect…",
                color = colors.textSecondary,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = appState::retryConnectionNow) {
            Text("Try Again", color = colors.accent)
        }
    }
}
