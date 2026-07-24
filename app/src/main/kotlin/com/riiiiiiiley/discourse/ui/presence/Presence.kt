package com.riiiiiiiley.discourse.ui.presence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors

/**
 * User presence (online/idle/offline), mirroring the iOS PresenceService
 * states. Presence can be server-disabled (fulltimefeline 403s every call), so
 * every consumer treats a null state as "draw nothing".
 */
enum class PresenceState(val label: String) {
    ONLINE("Online"),
    UNAVAILABLE("Idle"),
    OFFLINE("Offline"),
}

/**
 * Poll-loop surface the dots register against; PresenceService (phase 7)
 * implements it. `stateOf` must be backed by Compose snapshot state so a
 * presence change re-renders only the dots observing that user.
 */
interface PresenceSource {
    fun stateOf(userId: String): PresenceState?

    /** Refcounted: keys with at least one visible dot are what the poll fetches. */
    fun register(userId: String)

    fun unregister(userId: String)
}

/** Provided per active session by the main shell; null while logged out. */
val LocalPresenceSource = compositionLocalOf<PresenceSource?> { null }

/**
 * Presence dot pinned to an avatar's bottom-trailing corner; registers with
 * the poll loop while visible.
 */
@Composable
fun PresenceDot(userId: String, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val presence = LocalPresenceSource.current
    val colors = LocalDiscourseColors.current
    DisposableEffect(userId, presence) {
        presence?.register(userId)
        onDispose { presence?.unregister(userId) }
    }
    val state = presence?.stateOf(userId)
    AnimatedVisibility(
        visible = state != null,
        enter = scaleIn() + fadeIn(),
        exit = scaleOut() + fadeOut(),
        modifier = modifier,
    ) {
        val color = when (state) {
            PresenceState.ONLINE -> colors.presenceOnline
            PresenceState.UNAVAILABLE -> Color(0xFFFF9500)
            else -> Color.Gray.copy(alpha = 0.6f)
        }
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(size)
                .background(color, CircleShape)
                // iOS strokes with `.background`; the sidebar surface is what
                // the dot sits over here.
                .border(size * 0.18f, colors.bgSidebar, CircleShape)
                .semantics { contentDescription = state?.label ?: "" },
        )
    }
}
