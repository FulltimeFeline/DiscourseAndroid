package com.riiiiiiiley.discourse.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Avatar/banner loading surface for list UI, the analogue of the iOS
 * `@Environment(\.mediaLoader)`. The session-scoped MediaLoader (media phase)
 * implements this; until it's provided, views fall back to colored initials —
 * the same behavior iOS has when the environment value is nil.
 */
interface MediaImageLoader {
    /** Fetches (or reads from cache) a thumbnail for an `mxc://` URL. */
    suspend fun avatar(mxcUrl: String, pixelSize: Int): ImageBitmap?

    /**
     * Synchronous cache hit so recycled rows show the avatar on their first
     * frame instead of flashing initials.
     */
    fun cachedThumbnail(mxcUrl: String, pixelSize: Int): ImageBitmap?
}

/** Provided per active session by the main shell; null while logged out. */
val LocalMediaLoader = staticCompositionLocalOf<MediaImageLoader?> { null }
