package com.riiiiiiiley.discourse.app

import android.app.Application

class DiscourseApplication : Application() {
    /**
     * Process-wide root state. Lazy so the encrypted session store is only
     * touched once something actually reads state (the activity's RootView).
     * Rust platform init stays lazy too — MatrixService triggers it on the
     * first client build/restore, matching iOS.
     *
     * MUST be created on the main thread: AppState registers a
     * ProcessLifecycleOwner observer, which throws off-main. A cold FCM push
     * runs on a background thread, so it must NOT touch this — it reads
     * [appStateOrNull] instead and falls back to restoring from the session
     * store when the UI hasn't initialized AppState in this process.
     */
    private val appStateLazy = lazy { AppState(this) }
    val appState: AppState get() = appStateLazy.value

    /** The AppState only if already initialized (never triggers off-main init). */
    val appStateOrNull: AppState? get() = if (appStateLazy.isInitialized()) appStateLazy.value else null
}
