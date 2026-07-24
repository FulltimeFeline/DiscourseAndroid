package com.riiiiiiiley.discourse.features.stickers

import android.content.Context
import android.graphics.BitmapFactory
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.core.StickerStore
import com.riiiiiiiley.discourse.core.StickerUsage
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.models.MediaSourceBox
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Room

/**
 * Raw `m.sticker` sends — the port of iOS TimelineViewModel.sendSticker
 * (both overloads). Standalone because TimelineViewModel's `room` handle is
 * private; the integrator grafts thin `sendSticker` wrappers onto
 * TimelineViewModel at its comment-marked "MARK: Stickers" spot.
 */
object StickerSender {

    /** Sends a personal-pack sticker and records usage for the recents tab. */
    suspend fun send(room: Room, sticker: StickerStore.Sticker, context: Context) {
        val content = JSONObject().apply {
            put("body", sticker.body)
            put("url", sticker.url)
            put("info", JSONObject().apply {
                put("w", sticker.width)
                put("h", sticker.height)
                put("mimetype", sticker.mimetype)
                put("size", sticker.size)
            })
        }
        StickerUsage.record(context, sticker.shortcode)
        sendRaw(room, content)
    }

    /** Sends a room/space-pack sticker (MSC2545 image). */
    suspend fun send(room: Room, emote: CustomEmojiStore.Emote, mediaLoader: MediaLoader) {
        var width = emote.width
        var height = emote.height
        // Packs often omit `info`; without w/h receivers guess a frame and
        // crop. Read the real pixel size from the picker's cached bytes.
        if (width == null || height == null) {
            val source = runCatching { MediaSource.fromUrl(emote.url) }.getOrNull()
            val data = source?.let { mediaLoader.fullContent(MediaSourceBox(it)) }
            if (data != null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    width = bounds.outWidth
                    height = bounds.outHeight
                }
            }
        }
        val info = JSONObject()
        width?.let { info.put("w", it) }
        height?.let { info.put("h", it) }
        emote.mimetype?.let { info.put("mimetype", it) }
        emote.size?.let { info.put("size", it) }
        val content = JSONObject().apply {
            put("body", emote.body)
            put("url", emote.url)
            put("info", info)
        }
        sendRaw(room, content)
    }

    /** iOS `try? await room.sendRaw(...)`: failures drop silently. */
    private suspend fun sendRaw(room: Room, content: JSONObject) {
        try {
            room.sendRaw("m.sticker", content.toString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
