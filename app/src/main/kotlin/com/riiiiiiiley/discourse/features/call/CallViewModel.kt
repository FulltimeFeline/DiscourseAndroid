package com.riiiiiiiley.discourse.features.call

import android.util.Log
import com.riiiiiiiley.discourse.core.WellKnownDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientProperties
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetDriverHandle
import org.matrix.rustcomponents.sdk.generateWebviewUrl
import org.matrix.rustcomponents.sdk.getElementCallRequiredPermissions
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import org.matrix.rustcomponents.sdk.newVirtualElementCallWidget
import uniffi.matrix_sdk.EncryptionSystem
import uniffi.matrix_sdk.Intent as CallIntent
import uniffi.matrix_sdk.VirtualElementCallWidgetConfig
import uniffi.matrix_sdk.VirtualElementCallWidgetProperties
import java.util.UUID

/**
 * Driver↔widget traffic + call lifecycle. Info level so it persists to
 * logcat: `adb logcat -s DiscourseCall` surfaces the MatrixRTC membership /
 * delayed-event churn behind call reconnects.
 */
private const val CALL_LOG_TAG = "DiscourseCall"

/**
 * Hosts an Element Call (MatrixRTC) session: builds the virtual widget, runs
 * the SDK widget driver, and shuttles messages between driver and web view.
 * State mutates on the main dispatcher (the iOS @MainActor analogue).
 */
class CallViewModel(
    private val room: Room,
    private val client: Client,
    private val ownUserId: String,
    private val joinExisting: Boolean = false,
) {
    val roomName: String = room.displayName() ?: room.id()

    private val _webViewUrl = MutableStateFlow<String?>(null)
    val webViewUrl: StateFlow<String?> = _webViewUrl

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * Set when Element Call reports the user hung up / left, so the call
     * screen can close itself.
     */
    private val _didHangUp = MutableStateFlow(false)
    val didHangUp: StateFlow<Boolean> = _didHangUp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var handle: WidgetDriverHandle? = null
    /** Uniffi objects kept referenced for the whole call so they aren't destroyed. */
    private var retained: List<Any> = emptyList()
    private var driverJob: Job? = null
    private var pumpJob: Job? = null

    /** Delivers driver→widget messages into the web view; set by the view. */
    var postToWebView: ((String) -> Unit)? = null

    suspend fun start() {
        if (_webViewUrl.value != null) return
        CallRegistry.localRooms.add(room.id())
        try {
            // Self-hosted EC if the homeserver advertises one, Element's
            // otherwise. /room is the embedded-widget entrypoint; the bare
            // origin serves the standalone SPA (→ "Missing access token").
            val elementCallUrl = WellKnownDiscovery.elementCallWidgetUrl(ownUserId)
                ?: "https://call.element.io/room"
            // The widget-settings build, webview-URL generation, driver
            // construction and deviceId read are all FFI that poll the Rust
            // runtime — do them off-main so opening a call doesn't hitch, then
            // assign state / launch the pump on Main below.
            val built = withContext(Dispatchers.IO) {
            val settings = newVirtualElementCallWidget(
                VirtualElementCallWidgetProperties(
                    elementCallUrl = elementCallUrl,
                    widgetId = UUID.randomUUID().toString(),
                    // Parent is the call page itself, so widget postMessages
                    // stay in-page where our injected bridge can capture them.
                    parentUrl = elementCallUrl,
                    fontScale = null,
                    font = null,
                    encryption = EncryptionSystem.PerParticipantKeys,
                    posthogUserId = null,
                    posthogApiHost = null,
                    posthogApiKey = null,
                    rageshakeSubmitUrl = null,
                    sentryDsn = null,
                    sentryEnvironment = null,
                ),
                VirtualElementCallWidgetConfig(
                    intent = if (joinExisting) CallIntent.JOIN_EXISTING else CallIntent.START_CALL,
                    skipLobby = false,
                    header = null,
                    hideHeader = true,
                    preload = null,
                    appPrompt = false,
                    confineToRoom = true,
                    hideScreensharing = false,
                    controlledAudioDevices = null,
                    sendNotificationType = null,
                ),
            )

            val urlString = generateWebviewUrl(
                settings,
                room,
                ClientProperties(
                    clientId = "com.riiiiiiiley.discourse",
                    languageTag = null,
                    theme = null,
                ),
            )

            Log.i(CALL_LOG_TAG, "call url=$urlString")
            val pair = makeWidgetDriver(settings)
            val capabilities = CallCapabilitiesBridge(
                ownUserId = ownUserId,
                ownDeviceId = runCatching { client.deviceId() }.getOrDefault(""),
            )
            Triple(urlString, pair, capabilities)
            }

            val (urlString, pair, capabilities) = built
            val driverHandle = pair.handle
            handle = driverHandle
            retained = listOf(pair.driver, pair.handle, capabilities)
            driverJob = scope.launch {
                pair.driver.run(room, capabilities)
            }
            pumpJob = scope.launch {
                while (isActive) {
                    val message = driverHandle.recv() ?: break
                    Log.i(CALL_LOG_TAG, "driver→widget: ${message.take(400)}")
                    postToWebView?.invoke(message)
                }
            }

            _webViewUrl.value = urlString
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _error.value = "Couldn't start the call: ${error.localizedMessage ?: error}"
        }
    }

    /** Widget→driver, called from the web view's message handler (main thread). */
    fun receiveFromWebView(message: String) {
        val handle = handle ?: return
        Log.i(CALL_LOG_TAG, "widget→driver: ${message.take(400)}")
        val json = runCatching { JSONObject(message) }.getOrNull()
        val action = json?.optString("action")?.takeIf { it.isNotEmpty() }
        if (json != null && action != null) {
            // Element Call posts a hangup/close action when the user leaves — the
            // screen should then close itself rather than lingering on the widget.
            if (action.contains("hangup") || action == "close" ||
                action == "im.vector.hangup" || action == "io.element.close"
            ) {
                _didHangUp.value = true
            }
            // Host-level actions the driver can't parse: ack them ourselves and
            // do NOT forward, so Element Call sees success instead of an error.
            if (action in hostHandledActions) {
                ackWidgetAction(json, action)
                return
            }
        }
        scope.launch { handle.send(message) }
    }

    /**
     * Posts an empty-success response back to the widget for a request the host
     * handles. Element Call matches it by `requestId`; a `response` with no
     * `error` reads as success (and `set_always_on_screen` wants `success`).
     */
    private fun ackWidgetAction(request: JSONObject, action: String) {
        request.put(
            "response",
            if (action == "set_always_on_screen") JSONObject().put("success", true) else JSONObject(),
        )
        Log.i(CALL_LOG_TAG, "host-ack: $action")
        postToWebView?.invoke(request.toString())
    }

    fun stop() {
        CallRegistry.localRooms.remove(room.id())
        pumpJob?.cancel()
        driverJob?.cancel()
        pumpJob = null
        driverJob = null
        handle = null
        retained = emptyList()
        postToWebView = null
    }

    // Keeping the process alive and un-throttled for the call — so the
    // MatrixRTC "delayed leave" heartbeat keeps refreshing while idle or
    // backgrounded — is the view's job on Android: the call WebView holds
    // `keepScreenOn`, and AppState skips pausing sync while a call is live
    // (same rule as the iOS scene-phase handler). Like iOS deliberately not
    // touching AVAudioSession, we never reconfigure AudioManager here: the
    // WebView owns the WebRTC capture session and an outside mode change
    // desyncs its audio routing.

    companion object {
        /**
         * Element Call "host" widget actions that the embedding client is meant to
         * answer itself — they're NOT part of the Matrix widget API the SDK's driver
         * implements. Forwarding them to the driver gets an "unknown variant" error
         * back, which desyncs Element Call's state machine (e.g. the mic shows muted
         * while you're unmuted, join/screenshare stall). We ack them here instead.
         */
        private val hostHandledActions: Set<String> = setOf(
            "io.element.join",
            "io.element.device_mute",
            "set_always_on_screen",
            "io.element.tile_layout",
        )
    }
}

/** Rooms whose call we started/joined here, so the ringing UI skips our own. */
object CallRegistry {
    /** Main-thread confined (the iOS @MainActor analogue). */
    val localRooms: MutableSet<String> = mutableSetOf()
}

/** Grants Element Call the permissions the SDK says it requires. */
class CallCapabilitiesBridge(
    private val ownUserId: String,
    private val ownDeviceId: String,
) : WidgetCapabilitiesProvider {
    override fun acquireCapabilities(capabilities: WidgetCapabilities): WidgetCapabilities =
        getElementCallRequiredPermissions(ownUserId, ownDeviceId)
}
