package com.riiiiiiiley.discourse.features.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.riiiiiiiley.discourse.app.AppState
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.flow.update

/** iOS system red, matching the SwiftUI semantic `.red` the call UI tints with. */
internal val CallRed = Color(0xFFFF3B30)

/** Full-screen call UI (port of iOS CallView; the phone layout is the reference). */
@Composable
fun CallScreen(viewModel: CallViewModel, onDismiss: () -> Unit) {
    val colors = LocalDiscourseColors.current
    val webViewUrl by viewModel.webViewUrl.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val didHangUp by viewModel.didHangUp.collectAsStateWithLifecycle()
    /** The close button hangs up rather than closing a window; confirm first. */
    var showsLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    // Element Call reported a hangup/leave: close the call screen.
    LaunchedEffect(didHangUp) { if (didHangUp) onDismiss() }
    // System back = the iOS Esc/cancelAction: it ends the call, so it routes
    // through the same leave confirm as the header button.
    BackHandler { showsLeaveConfirm = true }

    // Header stays inside the top safe area (iOS gives only the WKWebView
    // .ignoresSafeArea(edges: .bottom)); edge-to-edge would put the hang-up
    // button under the status bar/cutout otherwise.
    Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).statusBarsPadding()) {
        CallHeader(roomName = viewModel.roomName) { showsLeaveConfirm = true }

        HorizontalDivider(color = colors.separator)

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val currentError = error
            val currentUrl = webViewUrl
            when {
                currentError != null -> CallUnavailableView(description = currentError)
                currentUrl != null -> CallWebView(url = currentUrl, viewModel = viewModel)
                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(color = colors.accent)
                    Text("Connecting…", fontSize = 14.sp, color = colors.textSecondary)
                }
            }
        }
    }

    if (showsLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showsLeaveConfirm = false },
            containerColor = colors.bgElevated2,
            titleContentColor = colors.textPrimary,
            title = { Text("Leave the call?") },
            confirmButton = {
                TextButton(onClick = {
                    showsLeaveConfirm = false
                    onDismiss()
                }) { Text("Leave Call", color = CallRed) }
            },
            dismissButton = {
                TextButton(onClick = { showsLeaveConfirm = false }) {
                    Text("Cancel", color = colors.accent)
                }
            },
        )
    }
}

@Composable
private fun CallHeader(roomName: String, onLeaveTapped: () -> Unit) {
    val colors = LocalDiscourseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Icon(
            Icons.Filled.Call,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Call — $roomName",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Hang-up glyph: this button ends the call, not closes a window.
        IconButton(onClick = onLeaveTapped, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = "Leave Call",
                tint = CallRed,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
internal fun CallUnavailableView(description: String? = null) {
    val colors = LocalDiscourseColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(
            Icons.Filled.PhoneDisabled,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "Call Unavailable",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        if (description != null) {
            Text(
                description,
                fontSize = 14.sp,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * WebView hosting Element Call, bridging its widget postMessage API to the
 * SDK's widget driver (port of iOS CallWebView).
 */
@Composable
private fun CallWebView(url: String, viewModel: CallViewModel) {
    val context = LocalContext.current
    // Only the validated origin (the Element Call URL) may capture camera/mic.
    val capture = remember(url) {
        MediaCaptureBridge(context.applicationContext, allowedHost = Uri.parse(url).host)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { capture.onAppPermissionsResult() }
    SideEffect { capture.requestAppPermissions = { permissionLauncher.launch(it) } }
    // Ask for mic+camera up front, so the lobby's first getUserMedia already
    // finds the app-level grants in place.
    LaunchedEffect(Unit) { capture.requestMissingUpfront() }

    val webView = remember(url) { makeCallWebView(context, url, viewModel, capture) }
    DisposableEffect(webView) {
        onDispose {
            viewModel.postToWebView = null
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

private fun makeCallWebView(
    context: Context,
    url: String,
    viewModel: CallViewModel,
    capture: MediaCaptureBridge,
): WebView {
    val webView = WebView(context)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        // Element Call's lobby preview and remote streams must play unprompted
        // (mediaTypesRequiringUserActionForPlayback = [] on iOS).
        mediaPlaybackRequiresUserGesture = false
    }
    webView.setBackgroundColor(android.graphics.Color.BLACK)
    // Android analogue of the macOS App Nap assertion: a dark screen throttles
    // the WebView's JS timers, and Element Call refreshes the MatrixRTC
    // "delayed leave" heartbeat on a JS timer — a throttled refresh lets the
    // server drop us for everyone.
    webView.keepScreenOn = true

    val mainHandler = Handler(Looper.getMainLooper())

    // Element Call posts widget-API messages to its "parent" (itself, per
    // our parentUrl); capture and forward them natively.
    val bridgeScript = """
        window.addEventListener('message', (event) => {
            let message = { data: event.data, origin: event.origin };
            if (message.data.response && message.data.api == 'toWidget'
                || !message.data.response && message.data.api == 'fromWidget') {
                window.widgetBridge.postMessage(JSON.stringify(message.data));
            }
        });
    """.trimIndent()

    webView.addJavascriptInterface(
        object {
            @JavascriptInterface
            fun postMessage(message: String) {
                // JS-interface calls arrive on a WebView thread; the view
                // model is main-confined.
                mainHandler.post { viewModel.receiveFromWebView(message) }
            }
        },
        "widgetBridge",
    )

    // WKUserScript-at-document-start analogue, scoped to the call origin.
    val hasDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    if (hasDocumentStartScript) {
        val origin = Uri.parse(url).let { "${it.scheme}://${it.authority}" }
        WebViewCompat.addDocumentStartJavaScript(webView, bridgeScript, setOf(origin))
    }
    // A WebViewClient must be installed either way, or navigations leave for
    // the system browser.
    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, startedUrl: String?, favicon: Bitmap?) {
            // Fallback injection: EC boots its widget transport asynchronously,
            // so start-of-page is still early enough to not miss messages.
            if (!hasDocumentStartScript) view.evaluateJavascript(bridgeScript, null)
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            capture.handle(request)
        }
    }

    viewModel.postToWebView = { message ->
        // The message is raw JSON, injected as the postMessage argument
        // unquoted — same as iOS.
        mainHandler.post { webView.evaluateJavascript("window.postMessage($message, '*')", null) }
    }

    webView.loadUrl(url)
    return webView
}

/**
 * Grants WebRTC capture only to the validated origin — the call URL can come
 * from a homeserver's `.well-known`, so a rogue URL must not — and bridges the
 * WebView grant to Android's runtime mic/camera permissions, which the app
 * must hold before a WebView-level grant means anything.
 */
private class MediaCaptureBridge(
    private val context: Context,
    private val allowedHost: String?,
) {
    /** Set by the composable; launches the runtime permission dialog. */
    var requestAppPermissions: ((Array<String>) -> Unit)? = null
    private var pending: PermissionRequest? = null

    fun requestMissingUpfront() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { !isGranted(it) }
        if (missing.isNotEmpty()) requestAppPermissions?.invoke(missing.toTypedArray())
    }

    /** WebChromeClient.onPermissionRequest, on the UI thread. */
    fun handle(request: PermissionRequest) {
        if (allowedHost.isNullOrEmpty() || request.origin?.host != allowedHost) {
            request.deny()
            return
        }
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        val missing = wanted.mapNotNull(::platformPermission).filter { !isGranted(it) }
        if (missing.isEmpty()) {
            request.grant(wanted.toTypedArray())
        } else {
            // Hold the WebView's request open until the runtime dialog resolves.
            pending?.deny()
            pending = request
            requestAppPermissions?.invoke(missing.toTypedArray())
        }
    }

    fun onAppPermissionsResult() {
        val request = pending ?: return
        pending = null
        val grantable = request.resources.filter { resource ->
            platformPermission(resource)?.let(::isGranted) == true
        }
        if (grantable.isEmpty()) request.deny() else request.grant(grantable.toTypedArray())
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun platformPermission(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }
}

/**
 * Full-screen call layer for the phone (port of iOS PhoneCallScreen): hosts
 * the shared CallScreen with the same session bookkeeping, and mirrors the
 * incoming-call banner so a ring during a call is answerable.
 */
@Composable
fun PhoneCallCover(
    appState: AppState,
    roomId: String,
    /** `scope.calls.callForRoom(roomId)`; null once the session is gone (logout mid-call). */
    call: CallViewModel?,
    /** `scope.calls.endCall(roomId)` on the owning session, if one is still active. */
    endCall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDiscourseColors.current
    val ringing by appState.ringingCall.collectAsStateWithLifecycle()

    val serviceContext = LocalContext.current
    DisposableEffect(roomId) {
        appState.activeCallRoomIds.update { it + roomId }
        // Foreground service for the call's lifetime: keeps mic/camera capture
        // and the MatrixRTC delayed-leave heartbeat alive when backgrounded.
        CallForegroundService.start(serviceContext, call?.roomName ?: "Call")
        onDispose {
            CallForegroundService.stop(serviceContext)
            appState.activeCallRoomIds.update { it - roomId }
            endCall()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (call != null) {
            CallScreen(viewModel = call, onDismiss = onDismiss)
        } else {
            // Session gone mid-call (logout): still needs a way out. There is
            // no call to confirm leaving, so back dismisses directly.
            BackHandler { onDismiss() }
            Column(modifier = Modifier.fillMaxSize().background(colors.bgApp).statusBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Call",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = "Close",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                HorizontalDivider(color = colors.separator)
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) { CallUnavailableView() }
            }
        }

        // MainShell's incoming-call banner sits under this cover (audible but
        // invisible); mirror it here so a ring during a call is answerable.
        var displayedRing by remember { mutableStateOf<AppState.RingingCall?>(null) }
        ringing?.let { displayedRing = it }
        AnimatedVisibility(
            visible = ringing != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        ) {
            displayedRing?.let { ring ->
                IncomingCallBanner(
                    call = ring,
                    accept = {
                        appState.ringingCall.value = null
                        // Ring for the room we're already in: nothing to do.
                        if (ring.roomId != roomId) {
                            // End the current call first: openChat drops foreign-room
                            // navigation while a call is live, so live-call state must
                            // clear before the navigation lands. (onDispose repeats
                            // this; both steps are idempotent.)
                            appState.activeCallRoomIds.update { it - roomId }
                            endCall()
                            onDismiss()
                            appState.pendingCallJoin.value = ring.roomId
                            appState.pendingRoomNavigation.value = ring.roomId
                        }
                    },
                    decline = { appState.ringingCall.value = null },
                )
            }
        }
    }
}
