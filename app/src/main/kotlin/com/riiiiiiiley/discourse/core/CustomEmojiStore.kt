package com.riiiiiiiley.discourse.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Custom emoji (MSC2545) aggregated from `im.ponies.user_emotes` account
 * data, `im.ponies.room_emotes` state in every joined space (plus rooms opted
 * in via `im.ponies.emote_rooms`), and packs of rooms opened this session.
 *
 * Main-thread confined (the iOS @MainActor analogue): all bookkeeping maps
 * mutate on the main dispatcher; state downloads and JSON parsing hop off-main.
 */
class CustomEmojiStore(private val client: Client) {

    data class Emote(
        /** Shortcode without the wrapping colons. */
        val shortcode: String,
        val url: String,
        val body: String,
        val packId: String,
        /** Empty = usable as both emoticon and sticker. */
        val usage: Set<String> = emptySet(),
        val width: Int? = null,
        val height: Int? = null,
        val mimetype: String? = null,
        val size: Int? = null,
    ) {
        val id: String get() = "$packId/$shortcode"
        val token: String get() = ":$shortcode:"
        val isEmoticon: Boolean get() = usage.isEmpty() || "emoticon" in usage
        val isSticker: Boolean get() = usage.isEmpty() || "sticker" in usage
    }

    data class Pack(
        /** "user" for the personal pack, else "roomId|stateKey". */
        val id: String,
        val displayName: String,
        val avatarUrl: String? = null,
        val emotes: List<Emote> = emptyList(),
        val roomId: String? = null,
        val stateKey: String? = null,
    ) {
        val emoticons: List<Emote> get() = emotes.filter { it.isEmoticon }
        val stickers: List<Emote> get() = emotes.filter { it.isSticker }
    }

    /** Display order: personal pack first, then room packs A–Z. */
    private val _packs = MutableStateFlow<List<Pack>>(emptyList())
    val packs: StateFlow<List<Pack>> = _packs.asStateFlow()

    /**
     * Room/space packs with at least one sticker; the personal sticker maker
     * lives in StickerStore. Plain getter — read after collecting [packs].
     */
    val stickerPacks: List<Pack>
        get() = _packs.value.filter { it.roomId != null && it.stickers.isNotEmpty() }

    /** shortcode → emoticon, first-wins with the personal pack prioritised. */
    private val _byShortcode = MutableStateFlow<Map<String, Emote>>(emptyMap())
    val byShortcode: StateFlow<Map<String, Emote>> = _byShortcode.asStateFlow()

    /**
     * `byShortcode`'s values sorted by shortcode, maintained here so the
     * composer's autocomplete doesn't re-sort per keystroke.
     */
    private val _sortedEmoticons = MutableStateFlow<List<Emote>>(emptyList())
    val sortedEmoticons: StateFlow<List<Emote>> = _sortedEmoticons.asStateFlow()

    /** mxc URL → emote (any usage), for labelling image reactions. */
    private val _byUrl = MutableStateFlow<Map<String, Emote>>(emptyMap())
    val byUrl: StateFlow<Map<String, Emote>> = _byUrl.asStateFlow()

    val isEmpty: Boolean get() = _packs.value.all { it.emotes.isEmpty() }

    /**
     * Wired by the session scope so refreshes see the current rail without
     * the store owning room-list state. Pairs of (id, name).
     */
    var spacesProvider: () -> List<Pair<String, String>> = { emptyList() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lastRefreshMillis: Long? = null
    /** Space set as of the last refresh; a change bypasses the throttle. */
    private var lastSpaceIds: Set<String> = emptySet()
    private var refreshJob: Job? = null
    /** Rooms fetched this session, so `ensureRoomPack` is one request per room. */
    private val fetchedRoomIds = mutableSetOf<String>()
    /** Keyed by source ("user" or a room ID). Rebuilt into `packs`. */
    private var packsBySource = mutableMapOf<String, List<Pack>>()
    /** Room display names, for pack fallbacks. */
    private val roomNames = mutableMapOf<String, String>()
    /**
     * `im.ponies.room_emotes` state keys per room from its last full-state
     * fetch (recorded even when empty). Lets the cheap refresh poll each pack
     * by key instead of re-downloading multi-MB room state every cycle.
     */
    private val packStateKeys = mutableMapOf<String, Set<String>>()
    /**
     * Last full-state fetch per room (epoch ms). Full state is the only way to
     * discover packs under new state keys, so it still runs, just on a long
     * interval.
     */
    private val lastFullFetch = mutableMapOf<String, Long>()

    /**
     * Full refresh, throttled to one pass per 5 minutes unless the space list
     * changed. Safe to call on every picker open and autocomplete keystroke.
     */
    suspend fun refreshIfStale(force: Boolean = false) =
        withContext(Dispatchers.Main.immediate) {
            val currentSpaceIds = spacesProvider().map { it.first }.toSet()
            val last = lastRefreshMillis
            if (!force && last != null &&
                System.currentTimeMillis() - last < 300_000 &&
                currentSpaceIds == lastSpaceIds
            ) {
                return@withContext
            }
            refreshJob?.let {
                it.join()
                return@withContext
            }
            val job = scope.launch { performRefresh() }
            refreshJob = job
            // Clear even when the waiting caller is cancelled mid-join, or the
            // stale handle would swallow every later refresh.
            try {
                job.join()
            } finally {
                if (refreshJob === job) refreshJob = null
            }
        }

    /** Fetches one room's emote packs when its timeline opens, once per session. */
    suspend fun ensureRoomPack(roomId: String, roomName: String) =
        withContext(Dispatchers.Main.immediate) {
            if (roomId in fetchedRoomIds) return@withContext
            fetchedRoomIds.add(roomId)
            roomNames[roomId] = roomName
            val credentials = homeserverCredentials() ?: return@withContext
            val (found, keys) = fetchRoomPacksWithKeys(roomId, roomName, credentials)
            if (keys != null) {
                packStateKeys[roomId] = keys
                lastFullFetch[roomId] = System.currentTimeMillis()
            }
            if ((packsBySource[roomId] ?: emptyList()) != found) {
                packsBySource[roomId] = found
                rebuild()
            }
        }

    // MARK: Credentials

    private class Credentials(val base: String, val accessToken: String)

    /** Cached `.well-known`-resolved client-API base for the raw state reads. */
    private var resolvedApiBase: String? = null

    /**
     * Base URL + token for the raw state reads.
     * Deviation from iOS (which uses `session.homeserverUrl` verbatim): the
     * base resolves `.well-known/matrix/client` once — the session URL can be
     * a bare server name whose client API 404s on delegated deployments.
     */
    private suspend fun homeserverCredentials(): Credentials? {
        val session = runCatching { client.session() }.getOrNull() ?: return null
        val base = resolvedApiBase ?: run {
            val raw = session.homeserverUrl.trimEnd('/')
            val resolved = resolveClientApiBase(raw) ?: raw
            resolvedApiBase = resolved
            resolved
        }
        return Credentials(base, session.accessToken)
    }

    // MARK: Refresh pipeline

    private suspend fun performRefresh() {
        lastRefreshMillis = System.currentTimeMillis()
        val spaces = spacesProvider()
        lastSpaceIds = spaces.map { it.first }.toSet()
        for ((id, name) in spaces) roomNames[id] = name

        val userPack = fetchUserPack()

        // Spaces plus rooms opted in via `im.ponies.emote_rooms`.
        val roomIds = spaces.map { it.first }.toMutableList()
        for (roomId in fetchEmoteRoomIds()) {
            if (roomId !in roomIds) roomIds.add(roomId)
        }
        // Keep previously opened rooms' packs fresh.
        for (roomId in fetchedRoomIds) {
            if (roomId !in roomIds) roomIds.add(roomId)
        }

        val bySource = mutableMapOf<String, List<Pack>>()
        if (userPack != null) bySource["user"] = listOf(userPack)
        val credentials = homeserverCredentials()
        if (credentials != null) {
            val now = System.currentTimeMillis()
            class RoomResult(val roomId: String, val packs: List<Pack>, val keys: Set<String>?)
            val results = coroutineScope {
                roomIds.map { roomId ->
                    val name = roomNames[roomId] ?: ""
                    // Per-key poll when keys are known and the last full fetch
                    // is recent; otherwise full state.
                    val stateKeys = packStateKeys[roomId]
                    val recentFull = lastFullFetch[roomId]
                        ?.let { now - it < FULL_FETCH_INTERVAL_MILLIS } ?: false
                    if (stateKeys != null && recentFull) {
                        val cached = packsBySource[roomId] ?: emptyList()
                        async {
                            RoomResult(
                                roomId,
                                refreshRoomPacksByKey(roomId, name, stateKeys, cached, credentials),
                                null,
                            )
                        }
                    } else {
                        async {
                            val (found, keys) = fetchRoomPacksWithKeys(roomId, name, credentials)
                            RoomResult(roomId, found, keys)
                        }
                    }
                }.awaitAll()
            }
            for (result in results) {
                fetchedRoomIds.add(result.roomId)
                if (result.keys != null) {
                    // Full fetch succeeded; a failed one reports null keys,
                    // leaving the prior record intact.
                    packStateKeys[result.roomId] = result.keys
                    lastFullFetch[result.roomId] = now
                }
                if (result.packs.isNotEmpty()) bySource[result.roomId] = result.packs
            }
        }
        // Rooms opened via `ensureRoomPack` mid-refresh aren't in `roomIds`;
        // carry their packs over instead of clobbering.
        for ((source, sourcePacks) in packsBySource) {
            if (source != "user" && source !in roomIds && bySource[source] == null) {
                bySource[source] = sourcePacks
            }
        }
        packsBySource = bySource
        rebuild()
    }

    private fun rebuild() {
        val ordered = mutableListOf<Pack>()
        ordered += packsBySource["user"] ?: emptyList()
        ordered += packsBySource
            .filterKeys { it != "user" }
            .values.flatten()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
        ordered.removeAll { it.emotes.isEmpty() }

        val shortcodes = mutableMapOf<String, Emote>()
        val urls = mutableMapOf<String, Emote>()
        for (pack in ordered) {
            for (emote in pack.emotes) {
                if (emote.isEmoticon && emote.shortcode !in shortcodes) {
                    shortcodes[emote.shortcode] = emote
                }
                if (emote.url !in urls) urls[emote.url] = emote
            }
        }
        // Equality-guarded publishes; sortedEmoticons only recomputes when the
        // shortcode map actually changed.
        if (_packs.value != ordered) _packs.value = ordered.toList()
        if (_byShortcode.value != shortcodes) {
            _byShortcode.value = shortcodes.toMap()
            _sortedEmoticons.value = shortcodes.values.sortedBy { it.shortcode }
        }
        if (_byUrl.value != urls) _byUrl.value = urls.toMap()
    }

    // MARK: Sources

    /** `im.ponies.user_emotes` account data; only emoticon-usage images. */
    private suspend fun fetchUserPack(): Pack? {
        val json = runCatching { client.accountData("im.ponies.user_emotes") }
            .getOrNull() ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val emotes = emotesFromPackContent(root, packId = "user").filter { it.isEmoticon }
        if (emotes.isEmpty()) return null
        return Pack(
            id = "user",
            displayName = "My Emoji",
            avatarUrl = emotes.first().url,
            emotes = emotes,
        )
    }

    /** `im.ponies.emote_rooms`: rooms whose packs the user enabled globally. */
    private suspend fun fetchEmoteRoomIds(): List<String> {
        val json = runCatching { client.accountData("im.ponies.emote_rooms") }
            .getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val rooms = root.optJSONObject("rooms") ?: return emptyList()
        return rooms.keys().asSequence().toList()
    }

    /**
     * Full room state via the client-server API (the FFI doesn't expose
     * arbitrary state reads), reporting every `im.ponies.room_emotes` state
     * key (even packs with no usable emotes) so the caller can poll them
     * cheaply later. `keys` is null only when the request failed.
     * Off-main: full state is multi-MB for big spaces; the download and parse
     * must stay off the main dispatcher.
     */
    private suspend fun fetchRoomPacksWithKeys(
        roomId: String,
        roomName: String,
        credentials: Credentials,
    ): Pair<List<Pack>, Set<String>?> = withContext(Dispatchers.IO) {
        val failure = emptyList<Pack>() to null
        val (code, body) = httpRequest(
            method = "GET",
            url = "${credentials.base}/_matrix/client/v3/rooms/${encodePath(roomId)}/state",
            bearer = credentials.accessToken,
            timeoutMillis = 30_000,
        ) ?: return@withContext failure
        if (code != 200) return@withContext failure
        val events = runCatching { JSONArray(body ?: "") }.getOrNull() ?: return@withContext failure

        val found = mutableListOf<Pack>()
        val keys = mutableSetOf<String>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString("type") != "im.ponies.room_emotes") continue
            val content = event.optJSONObject("content") ?: continue
            val stateKey = (event.opt("state_key") as? String) ?: ""
            keys.add(stateKey)
            packFromContent(content, stateKey, roomId, roomName)?.let { found.add(it) }
        }
        found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }) to keys
    }

    /**
     * One pack from a single `im.ponies.room_emotes` content blob; null when
     * it has no usable emotes.
     */
    private fun packFromContent(
        content: JSONObject,
        stateKey: String,
        roomId: String,
        roomName: String,
    ): Pack? {
        val packId = "$roomId|$stateKey"
        val emotes = emotesFromPackContent(content, packId)
        if (emotes.isEmpty()) return null
        val meta = content.optJSONObject("pack")
        val name = (meta?.opt("display_name") as? String)
            ?: stateKey.takeIf { it.isNotEmpty() }
            ?: roomName
        return Pack(
            id = packId,
            displayName = name.ifEmpty { roomName },
            avatarUrl = (meta?.opt("avatar_url") as? String) ?: emotes.first().url,
            emotes = emotes,
            roomId = roomId,
            stateKey = stateKey,
        )
    }

    /**
     * Cheap refresh when pack state keys are known: one
     * `GET /state/im.ponies.room_emotes/{key}` per key. `Found` updates,
     * `Absent` drops (deleted), `Failed` keeps the cached pack.
     */
    private suspend fun refreshRoomPacksByKey(
        roomId: String,
        roomName: String,
        stateKeys: Set<String>,
        cached: List<Pack>,
        credentials: Credentials,
    ): List<Pack> = withContext(Dispatchers.IO) {
        val byKey = mutableMapOf<String, Pack>()
        for (pack in cached) byKey[pack.stateKey ?: ""] = pack
        val results = coroutineScope {
            stateKeys.map { key ->
                async { key to fetchPackContent(roomId, key, credentials) }
            }.awaitAll()
        }
        for ((key, result) in results) {
            when (result) {
                is PackContentResult.Found -> {
                    val pack = packFromContent(result.content, key, roomId, roomName)
                    if (pack != null) byKey[key] = pack else byKey.remove(key)
                }
                PackContentResult.Absent -> byKey.remove(key)
                PackContentResult.Failed -> Unit // keep the cached pack
            }
        }
        byKey.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    /**
     * Emotes from one MSC2545 pack content. An image's `usage` falls back to
     * the pack's; absent both, it's usable everywhere.
     */
    private fun emotesFromPackContent(content: JSONObject, packId: String): List<Emote> {
        val images = content.optJSONObject("images") ?: return emptyList()
        val packUsage = content.optJSONObject("pack")?.optJSONArray("usage")?.let { toStringList(it) }
        val emotes = mutableListOf<Emote>()
        for (shortcode in images.keys()) {
            val entry = images.optJSONObject(shortcode) ?: continue
            val url = (entry.opt("url") as? String) ?: continue
            if (!url.startsWith("mxc://")) continue
            // These URLs get interpolated into outgoing HTML; reject anything
            // that could break out of an attribute.
            if (url.any { it.isWhitespace() || it in "\"'<>&" }) continue
            val usage = entry.optJSONArray("usage")?.let { toStringList(it) }
                ?: packUsage ?: emptyList()
            val info = entry.optJSONObject("info") ?: JSONObject()
            emotes.add(Emote(
                shortcode = shortcode,
                url = url,
                body = (entry.opt("body") as? String) ?: shortcode,
                packId = packId,
                usage = usage.toSet(),
                width = (info.opt("w") as? Number)?.toInt(),
                height = (info.opt("h") as? Number)?.toInt(),
                mimetype = info.opt("mimetype") as? String,
                size = (info.opt("size") as? Number)?.toInt(),
            ))
        }
        return emotes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shortcode })
    }

    private fun toStringList(array: JSONArray): List<String> =
        (0 until array.length()).mapNotNull { array.opt(it) as? String }

    // MARK: Rendering fallback

    /**
     * Maps `:tokens:` in a plain body to known emotes — fallback for messages
     * whose HTML never reached us.
     */
    fun knownEmotes(body: String): Map<String, String> {
        val map = _byShortcode.value
        if (!body.contains(':') || map.isEmpty()) return emptyMap()
        val found = mutableMapOf<String, String>()
        var index = 0
        while (true) {
            val colon = body.indexOf(':', index)
            if (colon < 0) break
            var end = colon + 1
            while (end < body.length && isShortcodeCharacter(body[end])) end++
            val emote = if (end < body.length && body[end] == ':' && end > colon + 1) {
                map[body.substring(colon + 1, end)]
            } else {
                null
            }
            if (emote != null) {
                found[emote.token] = emote.url
                index = end + 1
            } else {
                index = colon + 1
            }
        }
        return found
    }

    // MARK: Editing room/space packs

    /**
     * Adds an image to a room's default `im.ponies.room_emotes` pack (state
     * key ""). Returns an error message on failure, null on success.
     */
    suspend fun addToRoomPack(
        roomId: String,
        roomName: String,
        name: String,
        imageData: ByteArray,
        mimeType: String,
        width: Int? = null,
        height: Int? = null,
        usage: Set<String>,
    ): String? {
        val shortcode = sanitizedShortcode(name)
        if (shortcode.isEmpty()) return "Give it a name first."
        val entry: JSONObject
        try {
            val mxcUrl = client.uploadMedia(mimeType, imageData, progressWatcher = null)
            entry = JSONObject().apply {
                put("url", mxcUrl)
                put("body", name)
                if (usage.isNotEmpty()) {
                    put("usage", JSONArray(usage.sorted()))
                }
                put("info", JSONObject().apply {
                    put("mimetype", mimeType)
                    put("size", imageData.size)
                    width?.let { put("w", it) }
                    height?.let { put("h", it) }
                })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return error.message ?: error.toString()
        }
        return mutateRoomPack(roomId, roomName) { images ->
            images.put(shortcode, entry)
        }
    }

    /**
     * Removes a shortcode from the room's default pack; error message on
     * failure, null on success.
     */
    suspend fun removeFromRoomPack(roomId: String, roomName: String, shortcode: String): String? =
        mutateRoomPack(roomId, roomName) { images ->
            images.remove(shortcode)
        }

    /**
     * Read-modify-write of the room's default (`state_key: ""`) pack, then a
     * local refetch so the pickers update immediately.
     */
    private suspend fun mutateRoomPack(
        roomId: String,
        roomName: String,
        mutate: (JSONObject) -> Unit,
    ): String? = withContext(Dispatchers.Main.immediate) {
        val credentials = homeserverCredentials()
        val room = runCatching { client.getRoom(roomId) }.getOrNull()
        if (credentials == null || room == null) {
            return@withContext "This room isn't available right now."
        }
        // A fetch failure MUST abort: writing over a pack we couldn't read
        // would replace everyone's emotes with this one change.
        val content: JSONObject = when (val result = fetchPackContent(roomId, "", credentials)) {
            is PackContentResult.Found -> result.content
            PackContentResult.Absent -> JSONObject()
            PackContentResult.Failed ->
                return@withContext "Couldn't load the current pack — check your connection and try again."
        }
        val images = content.optJSONObject("images") ?: JSONObject()
        mutate(images)
        content.put("images", images)
        if (!content.has("pack")) {
            content.put("pack", JSONObject().put("display_name", roomName))
        }
        try {
            room.sendStateEventRaw("im.ponies.room_emotes", "", content.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Usually M_FORBIDDEN — no permission to send state here.
            val text = error.message ?: error.toString()
            return@withContext if (text.contains("M_FORBIDDEN") || text.contains("forbidden")) {
                "You don't have permission to edit this room's emoji."
            } else {
                text
            }
        }
        // Refetch so the pickers update without waiting for a refresh cycle.
        val (found, keys) = fetchRoomPacksWithKeys(roomId, roomName, credentials)
        if (keys != null) {
            packStateKeys[roomId] = keys
            lastFullFetch[roomId] = System.currentTimeMillis()
        }
        fetchedRoomIds.add(roomId)
        packsBySource[roomId] = found
        rebuild()
        null
    }

    /**
     * Result of `GET /state/im.ponies.room_emotes/{stateKey}`. `Absent` and
     * `Failed` are distinct so the read-modify-write can't treat a timeout
     * as an empty pack.
     */
    private sealed class PackContentResult {
        class Found(val content: JSONObject) : PackContentResult()
        object Absent : PackContentResult()
        object Failed : PackContentResult()
    }

    private suspend fun fetchPackContent(
        roomId: String,
        stateKey: String,
        credentials: Credentials,
    ): PackContentResult = withContext(Dispatchers.IO) {
        // Empty state key still needs its path segment (trailing slash).
        val url = "${credentials.base}/_matrix/client/v3/rooms/${encodePath(roomId)}" +
            "/state/im.ponies.room_emotes/${encodePath(stateKey)}"
        val (code, body) = httpRequest(
            method = "GET",
            url = url,
            bearer = credentials.accessToken,
        ) ?: return@withContext PackContentResult.Failed
        if (code == 404) return@withContext PackContentResult.Absent
        if (code != 200) return@withContext PackContentResult.Failed
        val content = runCatching { JSONObject(body ?: "") }.getOrNull()
            ?: return@withContext PackContentResult.Failed
        PackContentResult.Found(content)
    }

    // MARK: Outgoing messages

    /**
     * HTML body (MSC2545 `<img data-mx-emoticon>`) if `text` has known
     * `:shortcode:` tokens; null to fall back to the markdown path.
     */
    fun htmlBody(text: String): String? {
        val map = _byShortcode.value
        if (!text.contains(':') || map.isEmpty()) return null
        val html = StringBuilder()
        var replaced = false
        var index = 0
        while (true) {
            val colon = text.indexOf(':', index)
            if (colon < 0) break
            html.append(escapeHtml(text.substring(index, colon)))
            // Longest-match scan up to a closing colon.
            var end = colon + 1
            while (end < text.length && isShortcodeCharacter(text[end])) end++
            val emote = if (end < text.length && text[end] == ':' && end > colon + 1) {
                map[text.substring(colon + 1, end)]
            } else {
                null
            }
            if (emote != null) {
                html.append("<img data-mx-emoticon src=\"${escapeHtml(emote.url)}\"" +
                    " alt=\"${escapeHtml(emote.token)}\" title=\"${escapeHtml(emote.token)}\"" +
                    " height=\"32\" />")
                replaced = true
                index = end + 1
            } else {
                html.append(':')
                index = colon + 1
            }
        }
        html.append(escapeHtml(text.substring(index)))
        if (!replaced) return null
        return html.toString().replace("\n", "<br/>")
    }

    companion object {
        /** How long a room's state-key set is trusted before a full re-fetch. */
        private const val FULL_FETCH_INTERVAL_MILLIS = 45L * 60 * 1000

        fun sanitizedShortcode(name: String): String =
            name.lowercase()
                .replace(" ", "_")
                .filter { isShortcodeCharacter(it) }

        /** Characters accepted in a `:token:`. */
        fun isShortcodeCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character == '_' || character == '-' ||
                character == '.'

        private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        // Private duplicates of MatrixService's REST helpers (those are
        // private to its companion); kept byte-compatible.

        /**
         * Resolves `.well-known/matrix/client` → `m.homeserver.base_url` for a
         * server URL. Returns null if there's no delegation (caller falls back).
         */
        private suspend fun resolveClientApiBase(serverUrl: String): String? {
            val (code, response) = httpRequest(
                method = "GET",
                url = "${serverUrl.trimEnd('/')}/.well-known/matrix/client",
                bearer = null,
            ) ?: return null
            if (code != 200) return null
            val json = runCatching { JSONObject(response ?: "") }.getOrNull() ?: return null
            val base = json.optJSONObject("m.homeserver")?.optString("base_url")
                ?.takeIf { it.isNotEmpty() } ?: return null
            return base.trimEnd('/')
        }

        /** Percent-encodes one URL path segment (room IDs contain `:` etc). */
        private fun encodePath(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        /**
         * Minimal HTTP helper for the manual client-server API calls. Returns
         * (statusCode, bodyText) or null on a transport failure. Runs on IO.
         */
        private suspend fun httpRequest(
            method: String,
            url: String,
            bearer: String?,
            timeoutMillis: Int = 15_000,
        ): Pair<Int, String?>? = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = timeoutMillis
                    conn.readTimeout = timeoutMillis
                    bearer?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }
                    code to text
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }
    }
}

/** Provided per active session (SessionEnvironment); null while logged out. */
val LocalCustomEmojiStore =
    androidx.compose.runtime.staticCompositionLocalOf<CustomEmojiStore?> { null }
