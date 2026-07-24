package com.riiiiiiiley.discourse.core

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lazily fetches and caches each user's profile (pronouns, avatar, display
 * name) so the timeline, member list, profiles, and call-participant strips can
 * show them without re-hitting the homeserver per row.
 *
 * Observation: collect [cache] in composition (its value changes once per
 * landed fetch — StateFlow's equality dedup is the re-publish guard), then read
 * through the getters; they kick off fetches on first miss.
 */
class PronounsStore(private val service: MatrixService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * userId → fetched profile (a stored entry means "fetched", so we don't
     * keep re-requesting even when fields are empty).
     */
    private val _cache = MutableStateFlow<Map<String, MatrixService.ProfileInfo>>(emptyMap())
    val cache: StateFlow<Map<String, MatrixService.ProfileInfo>> = _cache

    private val inFlight = mutableSetOf<String>()

    /**
     * Set by the session owner after both are built: the status comes from
     * presence `status_msg` (where Commet stores it), with the profile field as
     * fallback. (iOS holds this weak; here the session scope tears both down
     * together, so a plain reference can't leak across sessions.)
     */
    var presence: PresenceService? = null

    private fun ensure(userId: String) {
        if (_cache.value.containsKey(userId) || userId in inFlight) return
        inFlight.add(userId)
        scope.launch {
            val info = service.fetchProfile(userId) ?: MatrixService.ProfileInfo()
            _cache.value = _cache.value + (userId to info)
            inFlight.remove(userId)
        }
    }

    /**
     * The cached pronouns, kicking off a fetch on first miss (returns null
     * until it lands, then the [cache] emission fills it in).
     */
    fun pronouns(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.pronouns
    }

    /** The cached avatar mxc URL (for call-participant strips etc.). */
    fun avatarUrl(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.avatarUrl
    }

    fun displayName(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.displayName
    }

    fun bio(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.bio
    }

    /**
     * The user's status. Presence `status_msg` (Commet's store) is preferred;
     * the `chat.commet.profile_status` profile field is the fallback.
     *
     * Gating: when we KNOW the user's presence, show the status only while
     * they're connected (online/idle), hidden when offline. When presence is
     * unknown — either not fetched yet or the homeserver has presence disabled
     * (Tuwunel returns 404/400) — we can't gate, so we still show the
     * profile-field status rather than hiding everything.
     */
    fun status(forUserId: String): String? {
        val state = presence?.state(of = forUserId)
        // Known offline → hide.
        if (state == PresenceService.State.OFFLINE) return null
        // Known connected → prefer the live presence status message.
        if (state == PresenceService.State.ONLINE || state == PresenceService.State.UNAVAILABLE) {
            presence?.statusMessage(of = forUserId)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // Connected without a presence message, or presence unknown (not fetched
        // / homeserver has it disabled): fall back to the profile field.
        ensure(forUserId)
        return _cache.value[forUserId]?.status
    }

    fun bannerUrl(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.bannerUrl
    }

    fun timezone(forUserId: String): String? {
        ensure(forUserId)
        return _cache.value[forUserId]?.timezone
    }

    fun socialLinks(forUserId: String): List<MatrixService.SocialLink> {
        ensure(forUserId)
        return _cache.value[forUserId]?.socialLinks ?: emptyList()
    }

    /**
     * Updates the cached pronouns immediately after the local user edits their
     * own, so the change shows without waiting for a re-fetch.
     */
    fun setLocal(pronouns: String?, forUserId: String) {
        val info = _cache.value[forUserId] ?: MatrixService.ProfileInfo()
        _cache.value = _cache.value + (forUserId to info.copy(pronouns = pronouns))
    }

    /**
     * Drops the cached profile so the next access re-fetches — used after the
     * local user edits their bio/status/timezone/banner.
     */
    fun invalidate(userId: String) {
        _cache.value = _cache.value - userId
        inFlight.remove(userId)
    }
}

/** The analogue of the iOS `\.pronounsStore` environment key. */
val LocalPronounsStore = staticCompositionLocalOf<PronounsStore?> { null }
