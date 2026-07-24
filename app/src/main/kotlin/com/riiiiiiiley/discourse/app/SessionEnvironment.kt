package com.riiiiiiiley.discourse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.riiiiiiiley.discourse.core.LocalCustomEmojiStore
import com.riiiiiiiley.discourse.core.LocalPresenceService
import com.riiiiiiiley.discourse.core.LocalPronounsStore
import com.riiiiiiiley.discourse.core.PresenceService
import com.riiiiiiiley.discourse.core.PronounsStore
import com.riiiiiiiley.discourse.core.SessionScope
import com.riiiiiiiley.discourse.features.profile.LocalMxcThumbnailLoader
import com.riiiiiiiley.discourse.features.profile.MxcThumbnailLoader
import com.riiiiiiiley.discourse.features.roomlist.LocalProfileNames
import com.riiiiiiiley.discourse.features.roomlist.ProfileNameSource
import com.riiiiiiiley.discourse.ui.media.MediaImageLoader
import com.riiiiiiiley.discourse.ui.presence.LocalPresenceSource
import com.riiiiiiiley.discourse.ui.presence.PresenceSource
import com.riiiiiiiley.discourse.ui.presence.PresenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Provides every per-session composition local the feature slices consume —
 * the analogue of the iOS `.environment(\.mediaLoader/presenceService/
 * pronounsStore)` root modifiers. All degrade to their nil behavior (initials
 * avatars, no presence dots, localpart names) when absent, so previews and
 * the logged-out tree never need them.
 */
@Composable
fun ProvideSessionLocals(scope: SessionScope, content: @Composable () -> Unit) {
    val imageLoader = remember(scope) {
        object : MediaImageLoader {
            override suspend fun avatar(mxcUrl: String, pixelSize: Int): ImageBitmap? =
                scope.mediaLoader.avatar(mxcUrl, pixelSize.toFloat())?.asImageBitmap()

            override fun cachedThumbnail(mxcUrl: String, pixelSize: Int): ImageBitmap? =
                scope.mediaLoader.cachedImage(mxcUrl, pixelSize.toFloat())?.asImageBitmap()
        }
    }
    val mxcLoader = remember(scope) {
        MxcThumbnailLoader { mxcUrl, pixelSize ->
            scope.mediaLoader.avatar(mxcUrl, pixelSize.toFloat())?.asImageBitmap()
        }
    }
    val presenceSource = remember(scope) { PresenceSourceAdapter(scope.presence) }
    val profileNames = remember(scope) { PronounsNameSource(scope.pronouns) }

    CompositionLocalProvider(
        com.riiiiiiiley.discourse.ui.media.LocalMediaLoader provides imageLoader,
        com.riiiiiiiley.discourse.core.media.LocalMediaLoader provides scope.mediaLoader,
        LocalMxcThumbnailLoader provides mxcLoader,
        LocalPresenceService provides scope.presence,
        LocalPresenceSource provides presenceSource,
        LocalPronounsStore provides scope.pronouns,
        LocalCustomEmojiStore provides scope.customEmoji,
        LocalProfileNames provides profileNames,
        content = content,
    )
}

/**
 * Snapshot-state bridge from PresenceService's flows to the dot components,
 * so a presence change re-renders only the dots observing that user. Refcounts
 * per user; degrades to "no dot" while the server 403s presence.
 */
private class PresenceSourceAdapter(
    private val service: PresenceService,
) : PresenceSource {
    private val states = mutableStateMapOf<String, PresenceState?>()
    private val jobs = mutableMapOf<String, Job>()
    private val refCounts = mutableMapOf<String, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun stateOf(userId: String): PresenceState? = states[userId]

    override fun register(userId: String) {
        service.register(userId)
        refCounts[userId] = (refCounts[userId] ?: 0) + 1
        if (jobs[userId] == null) {
            jobs[userId] = scope.launch {
                service.entries(userId).collect { entry ->
                    states[userId] = when (entry?.state) {
                        PresenceService.State.ONLINE -> PresenceState.ONLINE
                        PresenceService.State.UNAVAILABLE -> PresenceState.UNAVAILABLE
                        PresenceService.State.OFFLINE -> PresenceState.OFFLINE
                        null -> null
                    }
                }
            }
        }
    }

    override fun unregister(userId: String) {
        service.unregister(userId)
        val remaining = (refCounts[userId] ?: 1) - 1
        if (remaining <= 0) {
            refCounts.remove(userId)
            jobs.remove(userId)?.cancel()
        } else {
            refCounts[userId] = remaining
        }
    }
}

/** Display-name/avatar lookup for call strips, backed by the pronouns cache. */
private class PronounsNameSource(private val pronouns: PronounsStore) : ProfileNameSource {
    override fun displayName(userId: String): String? = pronouns.displayName(forUserId = userId)
    override fun avatarUrl(userId: String): String? = pronouns.avatarUrl(forUserId = userId)
}
