package com.riiiiiiiley.discourse.app

import android.app.Application

class DiscourseApplication : Application() {
    /**
     * Process-wide root state. Lazy so the encrypted session store is only
     * touched once something actually reads state (the activity's RootView).
     * Rust platform init stays lazy too — MatrixService triggers it on the
     * first client build/restore, matching iOS.
     */
    val appState: AppState by lazy { AppState(this) }
}
