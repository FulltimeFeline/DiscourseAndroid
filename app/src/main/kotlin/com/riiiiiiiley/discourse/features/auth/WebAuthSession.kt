package com.riiiiiiiley.discourse.features.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CompletableDeferred

/**
 * Runs a browser auth flow (Custom Tab) and resolves with the callback URL —
 * the ASWebAuthenticationSession equivalent. The redirect lands back in
 * MainActivity via the app's callback scheme (singleTask + intent-filter) and
 * is forwarded here through [handleRedirect]; a return to the app WITHOUT a
 * redirect (user closed the tab) is detected in [onHostResumed] and treated
 * as cancellation, mirroring ASWebAuthenticationSession's canceledLogin.
 */
object WebAuthSession {

    /** The user dismissed the browser without completing sign-in. */
    class UserCancelledException : Exception("User cancelled browser sign-in")

    private var pending: CompletableDeferred<Uri>? = null
    private var expectedScheme: String? = null

    /** Set when the tab launches; the next host resume without a redirect = cancel. */
    private var awaitingReturn = false

    suspend fun authenticate(context: Context, url: String, callbackScheme: String): Uri {
        // A second flow supersedes the first (matches replacing the iOS session).
        pending?.completeExceptionally(UserCancelledException())
        val deferred = CompletableDeferred<Uri>()
        pending = deferred
        expectedScheme = callbackScheme
        awaitingReturn = true
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
        return deferred.await()
    }

    /**
     * Called with the incoming `com.riiiiiiiley.discourse:/...` redirect.
     * Returns true if it belonged to (and resolved) a pending flow.
     */
    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != expectedScheme) return false
        val deferred = pending ?: return false
        pending = null
        awaitingReturn = false
        deferred.complete(uri)
        return true
    }

    /**
     * Host activity resumed. The redirect intent (if any) arrives via
     * onNewIntent BEFORE onResume, so a still-pending flow here means the
     * user came back without finishing — cancel it.
     */
    fun onHostResumed() {
        if (!awaitingReturn) return
        awaitingReturn = false
        pending?.completeExceptionally(UserCancelledException())
        pending = null
    }

    fun cancel() {
        awaitingReturn = false
        pending?.completeExceptionally(UserCancelledException())
        pending = null
    }
}
