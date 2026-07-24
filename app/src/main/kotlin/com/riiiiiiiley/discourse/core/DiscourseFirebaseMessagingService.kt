package com.riiiiiiiley.discourse.core

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * FCM entry point. Firebase hands us the registration token (to register the
 * Matrix pusher against sygnal) and delivers pushes (sygnal's event_id-only
 * data payload). The real work lives in [PushRegistrar].
 */
class DiscourseFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch { PushRegistrar.onNewToken(applicationContext, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The service can be torn down as soon as this returns; block on the
        // (short) fetch+decrypt so the notification is actually posted.
        runBlocking { PushRegistrar.onMessage(applicationContext, message.data) }
    }
}
