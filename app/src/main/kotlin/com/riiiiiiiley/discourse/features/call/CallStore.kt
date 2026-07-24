package com.riiiiiiiley.discourse.features.call

/**
 * The live calls of one session, keyed by room id — port of the `calls` map on
 * the iOS SessionScope. Owned by SessionScope; main-thread confined like the
 * rest of its state.
 *
 * The factory builds a CallViewModel for a room (iOS goes through
 * `timeline(forRoomId:)?.callViewModel()` so `joinExisting` reflects the
 * room's live `hasActiveCall`); it returns null while the room has no FFI
 * backing yet (snapshot-restored rooms before the first sync batch).
 */
class CallStore(private val factory: (roomId: String) -> CallViewModel?) {
    private val calls = mutableMapOf<String, CallViewModel>()

    /**
     * The live call for a room, created once and kept alive so it can run
     * independent of any one screen's lifecycle.
     */
    fun callForRoom(roomId: String): CallViewModel? {
        calls[roomId]?.let { return it }
        val viewModel = factory(roomId) ?: return null
        calls[roomId] = viewModel
        return viewModel
    }

    /** Whether a live call exists WITHOUT creating one (timeline-LRU eviction guard). */
    fun hasCall(roomId: String): Boolean = calls.containsKey(roomId)

    /** Tears down and drops the call, so reopening starts a fresh session. */
    fun endCall(roomId: String) {
        calls.remove(roomId)?.stop()
    }

    /** Session teardown (logout / account switch). */
    fun tearDown() {
        calls.values.forEach { it.stop() }
        calls.clear()
    }
}
