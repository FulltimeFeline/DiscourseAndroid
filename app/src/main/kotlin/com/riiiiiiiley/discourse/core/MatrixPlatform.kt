package com.riiiiiiiley.discourse.core

import android.util.Log
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform

/** One-time Rust SDK platform init; must run before any `ClientBuilder`. */
object MatrixPlatform {
    private val initialized: Unit by lazy<Unit> {
        try {
            initPlatform(
                config = TracingConfiguration(
                    logLevel = LogLevel.INFO,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = true,
                    writeToFiles = null,
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
        } catch (error: Exception) {
            Log.wtf("MatrixPlatform", "Matrix platform init failed", error)
        }
    }

    fun initializeOnce() {
        initialized
    }
}
