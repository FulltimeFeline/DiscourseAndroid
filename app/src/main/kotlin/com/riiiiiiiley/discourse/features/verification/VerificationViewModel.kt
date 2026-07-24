package com.riiiiiiiley.discourse.features.verification

import com.riiiiiiiley.discourse.core.MatrixService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails

/** Events from the interactive session-verification (SAS) flow. */
sealed class VerificationEvent {
    data class RequestReceived(val senderId: String, val flowId: String) : VerificationEvent()
    data object AcceptedByOtherDevice : VerificationEvent()
    data object SasStarted : VerificationEvent()
    data class Emojis(val emojis: List<VerificationEmoji>) : VerificationEvent()
    data object Failed : VerificationEvent()
    data object Cancelled : VerificationEvent()
    data object Finished : VerificationEvent()
}

data class VerificationEmoji(
    val symbol: String,
    val description: String,
)

/**
 * SDK verification delegate → event flow (the Kotlin analogue of the iOS
 * AsyncStream bridge). Unbounded buffer so no SAS event is ever dropped
 * between delegate callback and collection.
 */
class SessionVerificationDelegateBridge : SessionVerificationControllerDelegate {
    private val channel = Channel<VerificationEvent>(Channel.UNLIMITED)
    val events: Flow<VerificationEvent> = channel.receiveAsFlow()

    override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
        channel.trySend(
            VerificationEvent.RequestReceived(
                senderId = details.senderProfile.userId,
                flowId = details.flowId,
            ),
        )
    }

    override fun didAcceptVerificationRequest() {
        channel.trySend(VerificationEvent.AcceptedByOtherDevice)
    }

    override fun didStartSasVerification() {
        channel.trySend(VerificationEvent.SasStarted)
    }

    override fun didReceiveVerificationData(data: SessionVerificationData) {
        if (data is SessionVerificationData.Emojis) {
            channel.trySend(
                VerificationEvent.Emojis(
                    data.emojis.map {
                        VerificationEmoji(symbol = it.symbol(), description = it.description())
                    },
                ),
            )
        }
    }

    override fun didFail() {
        channel.trySend(VerificationEvent.Failed)
    }

    override fun didCancel() {
        channel.trySend(VerificationEvent.Cancelled)
    }

    override fun didFinish() {
        channel.trySend(VerificationEvent.Finished)
    }
}

/**
 * Drives the verify-session sheet: SAS emoji verification against another
 * signed-in device, or recovery-key entry.
 */
class VerificationViewModel(private val service: MatrixService) {
    sealed class Step {
        data object Intro : Step()
        data object WaitingForOtherDevice : Step()
        data class ComparingEmojis(val emojis: List<VerificationEmoji>) : Step()
        data object Confirming : Step()
        data object Done : Step()
        data class Failed(val message: String) : Step()
        data object RecoveryKeyEntry : Step()
        data object Recovering : Step()
    }

    private val _step = MutableStateFlow<Step>(Step.Intro)
    val step: StateFlow<Step> = _step

    private val _recoveryKey = MutableStateFlow("")
    val recoveryKey: StateFlow<String> = _recoveryKey

    fun setRecoveryKey(value: String) {
        _recoveryKey.value = value
    }

    // Never cancelled wholesale: controller calls launched here must survive
    // sheet dismissal (the analogue of iOS's unstructured Tasks). Only the
    // event-collection job is cancelled, by cleanUp.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: SessionVerificationController? = null
    private var bridge: SessionVerificationDelegateBridge? = null
    private var eventJob: Job? = null

    // MARK: Device verification

    fun beginDeviceVerification() {
        scope.launch {
            try {
                attachController()
                withContext(Dispatchers.IO) { controller?.requestDeviceVerification() }
                _step.value = Step.WaitingForOtherDevice
            } catch (error: Exception) {
                _step.value = Step.Failed("Couldn't start verification: ${error.message ?: error}")
            }
        }
    }

    /** Accepts a verification request initiated from another device. */
    fun beginIncomingVerification(senderId: String, flowId: String) {
        scope.launch {
            try {
                attachController()
                withContext(Dispatchers.IO) {
                    controller?.acknowledgeVerificationRequest(senderId, flowId)
                    controller?.acceptVerificationRequest()
                }
                _step.value = Step.WaitingForOtherDevice
            } catch (error: Exception) {
                _step.value =
                    Step.Failed("Couldn't accept the verification request: ${error.message ?: error}")
            }
        }
    }

    private suspend fun attachController() {
        val controller = service.sessionVerificationController()
        this.controller = controller
        val bridge = SessionVerificationDelegateBridge()
        this.bridge = bridge
        controller.setDelegate(bridge)
        eventJob = scope.launch {
            bridge.events.collect { handle(it) }
        }
    }

    private suspend fun handle(event: VerificationEvent) {
        when (event) {
            is VerificationEvent.RequestReceived -> Unit // only originate or explicitly accept here
            VerificationEvent.AcceptedByOtherDevice ->
                withContext(Dispatchers.IO) { runCatching { controller?.startSasVerification() } }
            VerificationEvent.SasStarted -> Unit // emojis follow
            is VerificationEvent.Emojis -> _step.value = Step.ComparingEmojis(event.emojis)
            VerificationEvent.Failed ->
                _step.value = Step.Failed("Verification failed. Try again from the other device too.")
            VerificationEvent.Cancelled ->
                _step.value = Step.Failed("Verification was cancelled.")
            VerificationEvent.Finished -> _step.value = Step.Done
        }
    }

    fun emojisMatch() {
        _step.value = Step.Confirming
        scope.launch { withContext(Dispatchers.IO) { runCatching { controller?.approveVerification() } } }
    }

    fun emojisDontMatch() {
        scope.launch { withContext(Dispatchers.IO) { runCatching { controller?.declineVerification() } } }
        _step.value = Step.Failed("Verification declined — the emojis didn't match.")
    }

    fun cancel() {
        val controller = controller
        scope.launch { withContext(Dispatchers.IO) { runCatching { controller?.cancelVerification() } } }
        cleanUp()
    }

    // MARK: Recovery key

    fun showRecoveryKeyEntry() {
        _step.value = Step.RecoveryKeyEntry
    }

    fun submitRecoveryKey() {
        val key = _recoveryKey.value.trim()
        if (key.isEmpty()) return
        _step.value = Step.Recovering
        scope.launch {
            try {
                service.recover(key)
                _step.value = Step.Done
            } catch (error: Exception) {
                _step.value = Step.Failed("That recovery key didn't work. Check it and try again.")
            }
        }
    }

    fun reset() {
        cleanUp()
        _step.value = Step.Intro
        _recoveryKey.value = ""
    }

    /**
     * Called when the sheet leaves composition: stops event collection and
     * drops the delegate so nothing dangles (in-flight approve/cancel calls
     * on [scope] still run to completion).
     */
    fun dispose() {
        cleanUp()
    }

    private fun cleanUp() {
        eventJob?.cancel()
        eventJob = null
        controller?.setDelegate(null)
        controller = null
        bridge = null
    }
}
