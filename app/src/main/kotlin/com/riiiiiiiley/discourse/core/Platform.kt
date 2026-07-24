package com.riiiiiiiley.discourse.core

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

/** One-liners over platform state (the Android analogue of iOS `Platform`). */
object Platform {
    /**
     * Whether the app is foregrounded (any activity resumed). Read from the
     * main thread — ProcessLifecycleOwner is main-thread confined, matching
     * the call sites (view models on the main dispatcher).
     */
    val isAppActive: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}
