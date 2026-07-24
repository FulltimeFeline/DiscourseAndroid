package com.riiiiiiiley.discourse.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI

/**
 * Discovers a self-hosted Element Call from the homeserver's client
 * well-known (`io.element.call.widget_url`), so calls run on the user's own
 * MatrixRTC stack when one is advertised. Port of iOS WellKnownDiscovery.
 */
object WellKnownDiscovery {
    /** server -> discovered URL (null = definitively none). Absent = unknown. */
    private val cache = mutableMapOf<String, String?>()
    private val mutex = Mutex()

    suspend fun elementCallWidgetUrl(userId: String): String? {
        val server = userId.substringAfter(':', "").ifEmpty { return null }
        mutex.withLock { if (cache.containsKey(server)) return cache[server] }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URI("https://$server/.well-known/matrix/client")
                    .toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().readText() else null
                conn.disconnect()
                code to body
            }.getOrNull()
        // No response (offline, timeout): don't cache a verdict; retry next call.
        } ?: return null

        val (code, body) = result
        return when (code) {
            200 -> {
                val discovered = runCatching {
                    val root = Json.parseToJsonElement(body ?: "").jsonObject
                    val widgetUrl = root["io.element.call"]?.jsonObject
                        ?.get("widget_url")?.jsonPrimitive?.content
                    widgetUrl?.let {
                        val uri = URI(it)
                        if (uri.scheme != "https") return@runCatching null
                        // EC's widget entrypoint is /room; a bare origin loads the
                        // standalone SPA, which can't authenticate as a widget.
                        if (uri.path.isNullOrEmpty() || uri.path == "/") it.trimEnd('/') + "/room" else it
                    }
                }.getOrNull()
                // Definitive answer (URL, or 200 without the key): cache it.
                mutex.withLock { cache[server] = discovered }
                discovered
            }
            404 -> {
                // Definitively no self-hosted EC.
                mutex.withLock { cache[server] = null }
                null
            }
            // 5xx / 429 / redirects: transient, retry next time.
            else -> null
        }
    }
}
