package com.riiiiiiiley.discourse.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * A `roomId → spaceName` map shared with the push-notification path. The
 * notification handler can't cheaply resolve a room's parent space itself
 * (that needs the full space hierarchy), so the app — which already has it —
 * persists the mapping for the handler to read when titling a push.
 *
 * On iOS this lives in the App Group `UserDefaults` so the notification
 * service *extension* (a separate process) can read it; on Android the FCM
 * service runs in the app's own process, so plain `SharedPreferences` is the
 * equivalent shared store.
 */
object SpaceNameStore {
    private const val PREFS_NAME = "space-name-store"
    private const val KEY = "roomSpaceNames"
    private const val AVATARS_KEY = "roomSpaceAvatars"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** SharedPreferences has no map type; maps persist as one JSON blob. */
    private fun saveMap(context: Context, key: String, map: Map<String, String>) {
        prefs(context).edit { putString(key, JSONObject(map.toMap()).toString()) }
    }

    private fun readValue(context: Context, key: String, roomId: String): String? {
        val raw = prefs(context).getString(key, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return json.optString(roomId).takeIf { it.isNotEmpty() }
    }

    fun save(context: Context, map: Map<String, String>) =
        saveMap(context, KEY, map)

    fun spaceName(context: Context, forRoom: String): String? =
        readValue(context, KEY, forRoom)

    /**
     * `roomId → space avatar mxc URL`, so the push handler can show the parent
     * space's pfp on a room-in-space push.
     */
    fun saveAvatars(context: Context, map: Map<String, String>) =
        saveMap(context, AVATARS_KEY, map)

    fun spaceAvatar(context: Context, forRoom: String): String? =
        readValue(context, AVATARS_KEY, forRoom)

    private const val ROOM_AVATARS_KEY = "roomNotificationAvatars"

    /**
     * `roomId → the mxc URL to show on that room's notification`, resolved by
     * the app per its rule (DM → other person, room-in-space → space, else the
     * room). The push item's own avatar fields are unreliable in the handler,
     * so this app-provided map is the primary source.
     */
    fun saveRoomAvatars(context: Context, map: Map<String, String>) =
        saveMap(context, ROOM_AVATARS_KEY, map)

    fun roomAvatar(context: Context, forRoom: String): String? =
        readValue(context, ROOM_AVATARS_KEY, forRoom)
}
