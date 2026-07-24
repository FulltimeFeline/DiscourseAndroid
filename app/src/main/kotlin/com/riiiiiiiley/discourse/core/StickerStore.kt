package com.riiiiiiiley.discourse.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * The user's personal sticker pack, stored in `im.ponies.user_emotes`
 * account data (MSC2545).
 */
class StickerStore(private val client: Client) {

    data class Sticker(
        val shortcode: String,
        val body: String,
        val url: String, // mxc://
        val width: Int,
        val height: Int,
        val mimetype: String,
        val size: Int,
        /** Organizational pack name (Discourse-specific; ignored elsewhere). */
        val pack: String = defaultPack,
    ) {
        val id: String get() = shortcode

        companion object {
            const val defaultPack = "My Stickers"
        }
    }

    private val _stickers = MutableStateFlow<List<Sticker>>(emptyList())
    val stickers: StateFlow<List<Sticker>> = _stickers.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * Pack names in display order. Plain getter (like the iOS computed
     * property) — read it inside composition after collecting [stickers].
     */
    val packs: List<String>
        get() {
            val seen = mutableListOf<String>()
            for (sticker in _stickers.value) {
                if (sticker.pack !in seen) seen.add(sticker.pack)
            }
            return seen
        }

    fun stickers(inPack: String): List<Sticker> =
        _stickers.value.filter { it.pack == inPack }

    suspend fun load() {
        val images = readImages() ?: return
        val loaded = mutableListOf<Sticker>()
        for (shortcode in images.keys()) {
            val entry = images.optJSONObject(shortcode) ?: continue
            if (!isSticker(entry)) continue
            val url = (entry.opt("url") as? String) ?: continue
            val info = entry.optJSONObject("info") ?: JSONObject()
            loaded.add(Sticker(
                shortcode = shortcode,
                body = (entry.opt("body") as? String) ?: shortcode,
                url = url,
                width = info.optInt("w", 512),
                height = info.optInt("h", 512),
                mimetype = (info.opt("mimetype") as? String) ?: "image/png",
                size = info.optInt("size", 0),
                pack = (entry.opt("es.discourse.pack") as? String) ?: Sticker.defaultPack,
            ))
        }
        _stickers.value =
            loaded.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.shortcode })
    }

    /**
     * Square-crops and downscales to 512px PNG, uploads, and files it in the
     * pack.
     */
    suspend fun add(name: String, imageData: ByteArray, pack: String = Sticker.defaultPack) {
        _errorMessage.value = null
        val processed = makeStickerPng(imageData)
        if (processed == null) {
            _errorMessage.value = "That image couldn't be read."
            return
        }
        try {
            val mxcUrl = client.uploadMedia("image/png", processed.data, progressWatcher = null)
            val shortcode = name.lowercase()
                .replace(" ", "_")
                .filter { it.isLetter() || it.isDigit() || it == '_' || it == '-' }
            val packName = pack.trim()
            // Uniquify up front so save() can't overwrite a same-named foreign
            // emoji sharing this event.
            val baseShortcode = shortcode.ifEmpty { "sticker_${_stickers.value.size + 1}" }
            val uniqueShortcode = uniqueStickerShortcode(baseShortcode, foreignShortcodes())
            val sticker = Sticker(
                shortcode = uniqueShortcode,
                body = name,
                url = mxcUrl,
                width = processed.width,
                height = processed.height,
                mimetype = "image/png",
                size = processed.data.size,
                pack = packName.ifEmpty { Sticker.defaultPack },
            )
            _stickers.value = _stickers.value.filter { it.shortcode != sticker.shortcode } + sticker
            save()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _errorMessage.value = "Couldn't save the sticker: ${error.message ?: error}"
        }
    }

    suspend fun remove(shortcode: String) {
        _errorMessage.value = null
        _stickers.value = _stickers.value.filter { it.shortcode != shortcode }
        try {
            save()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Surface and re-sync; a silent failure resurrects the sticker on
            // the next load.
            _errorMessage.value = "Couldn't remove the sticker: ${error.message ?: error}"
            load()
        }
    }

    // MARK: Account-data plumbing

    /**
     * Whether an image entry is ours (declared sticker usage or our pack tag).
     * Foreign custom emoji must never be imported or rewritten here.
     */
    private fun isSticker(entry: JSONObject): Boolean {
        val usage = entry.optJSONArray("usage")
        if (usage != null && usage.length() > 0) {
            return (0 until usage.length()).any { usage.optString(it) == "sticker" }
        }
        return entry.has("es.discourse.pack")
    }

    /** The `images` map of the shared account-data event, or null when unreadable. */
    private suspend fun readImages(): JSONObject? {
        val json = runCatching { client.accountData(accountDataType) }
            .getOrNull() ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return root.optJSONObject("images")
    }

    /**
     * Foreign shortcodes in the shared event, which must never be overwritten
     * by a same-named sticker.
     */
    private suspend fun foreignShortcodes(): Set<String> {
        val images = readImages() ?: return emptySet()
        return buildSet {
            for (shortcode in images.keys()) {
                val entry = images.optJSONObject(shortcode) ?: continue
                if (!isSticker(entry)) add(shortcode)
            }
        }
    }

    /**
     * Appends a numeric suffix until the shortcode collides with neither a
     * foreign nor an existing local entry.
     */
    private fun uniqueStickerShortcode(base: String, foreignShortcodes: Set<String>): String {
        val localShortcodes = _stickers.value.map { it.shortcode }.toSet()
        fun taken(code: String): Boolean = code in foreignShortcodes
        if (!taken(base)) return base
        var suffix = 2
        while (true) {
            val candidate = "${base}_$suffix"
            if (!taken(candidate) && candidate !in localShortcodes) return candidate
            suffix += 1
        }
    }

    private suspend fun save() {
        // Merge into current server content: this event also carries the user's
        // custom emoji, which a wholesale replace would turn into stickers.
        val root = runCatching { client.accountData(accountDataType) }
            .getOrNull()
            ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
            ?: JSONObject()
        val existing = root.optJSONObject("images") ?: JSONObject()
        // Drop only our own entries; foreign ones stay verbatim.
        val images = JSONObject()
        val foreignKeys = mutableSetOf<String>()
        for (shortcode in existing.keys()) {
            val entry = existing.optJSONObject(shortcode)
            if (entry == null || isSticker(entry)) continue
            foreignKeys.add(shortcode)
            images.put(shortcode, existing.get(shortcode))
        }
        for (sticker in _stickers.value) {
            // Never overwrite a foreign entry, even on a shortcode collision.
            if (sticker.shortcode in foreignKeys) continue
            images.put(sticker.shortcode, JSONObject().apply {
                put("body", sticker.body)
                put("url", sticker.url)
                put("usage", JSONArray().put("sticker"))
                put("es.discourse.pack", sticker.pack)
                put("info", JSONObject().apply {
                    put("w", sticker.width)
                    put("h", sticker.height)
                    put("mimetype", sticker.mimetype)
                    put("size", sticker.size)
                })
            })
        }
        root.put("images", images)
        if (!root.has("pack")) {
            root.put("pack", JSONObject().put("display_name", "Discourse"))
        }
        client.setAccountData(accountDataType, root.toString())
    }

    private class ProcessedPng(val data: ByteArray, val width: Int, val height: Int)

    /** Center-square-crop + downscale to 512px, preserving transparency. */
    private suspend fun makeStickerPng(data: ByteArray): ProcessedPng? =
        withContext(Dispatchers.Default) {
            val image = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return@withContext null
            val side = min(image.width, image.height)
            if (side <= 0) return@withContext null
            val cropped = runCatching {
                Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2,
                                    side, side)
            }.getOrNull() ?: return@withContext null
            val target = min(side, 512)
            val scaled = if (side > target) {
                Bitmap.createScaledBitmap(cropped, target, target, true)
            } else {
                cropped
            }
            // PNG encoding keeps the alpha channel (the ImageIO path on iOS).
            val output = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) return@withContext null
            if (output.size() == 0) return@withContext null
            ProcessedPng(output.toByteArray(), target, target)
        }

    private companion object {
        const val accountDataType = "im.ponies.user_emotes"
    }
}
