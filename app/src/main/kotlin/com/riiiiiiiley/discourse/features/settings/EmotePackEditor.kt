package com.riiiiiiiley.discourse.features.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.CustomEmojiStore
import com.riiiiiiiley.discourse.core.MediaProcessing
import com.riiiiiiiley.discourse.features.timeline.EmoteAssetLoader
import com.riiiiiiiley.discourse.features.timeline.EmoteImageView
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Editor for a room/space's MSC2545 emote pack (`im.ponies.room_emotes`, default
 * state key). Writes require state-event permission.
 */
@Composable
fun EmotePackEditor(model: RoomSettingsModel) {
    val colors = LocalDiscourseColors.current
    val state by model.state.collectAsStateWithLifecycle()
    val store = model.scope.customEmoji
    val packs by store.packs.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val isSpace = model.target.isSpace
    val roomId = model.target.roomId

    val loader = remember(model.scope) {
        object : EmoteAssetLoader {
            override fun cachedImage(mxcUrl: String, pixelSize: Float) =
                model.scope.mediaLoader.cachedImage(mxcUrl, pixelSize)

            override suspend fun avatar(mxcUrl: String, pixelSize: Float) =
                model.scope.mediaLoader.avatar(mxcUrl, pixelSize)
        }
    }

    val pack = packs.firstOrNull { it.roomId == roomId && it.stateKey == "" }
    val emotes = pack?.emotes ?: emptyList()

    var newName by remember { mutableStateOf("") }
    var newUsage by remember { mutableStateOf(EmoteUsage.EMOTICON) }
    var stagedImage by remember { mutableStateOf<ByteArray?>(null) }
    // "image/png" for the flatten path, or the source type for a kept animation.
    var stagedMimeType by remember { mutableStateOf("image/png") }
    var stagedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var isWorking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { store.ensureRoomPack(roomId = roomId, roomName = state.name) }

    val imagePicker = rememberImagePicker { raw, displayName ->
        coroutineScope.launch {
            // Keep a small animated GIF/WebP intact; flatten everything else to PNG.
            val staged = withContext(Dispatchers.Default) { stageEmoteImage(raw) }
            if (staged == null) {
                errorMessage = "That image couldn't be read."
                return@launch
            }
            errorMessage = null
            stagedImage = staged.data
            stagedMimeType = staged.mimeType
            stagedPreview = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(staged.data, 0, staged.data.size)?.asImageBitmap()
            }
            if (newName.isEmpty() && displayName != null) {
                newName = displayName.substringBeforeLast('.')
            }
        }
    }

    fun add() {
        val staged = stagedImage ?: return
        val processedName = newName
        val usage = newUsage.usageSet
        isWorking = true
        errorMessage = null
        coroutineScope.launch {
            val size = withContext(Dispatchers.Default) { pixelSize(staged) }
            val error = store.addToRoomPack(
                roomId = roomId, roomName = state.name,
                name = processedName, imageData = staged, mimeType = stagedMimeType,
                width = size?.first, height = size?.second, usage = usage)
            isWorking = false
            if (error != null) {
                errorMessage = error
            } else {
                newName = ""
                stagedImage = null
                stagedMimeType = "image/png"
                stagedPreview = null
            }
        }
    }

    fun remove(emote: CustomEmojiStore.Emote) {
        isWorking = true
        errorMessage = null
        coroutineScope.launch {
            val error = store.removeFromRoomPack(roomId = roomId, roomName = state.name,
                                                 shortcode = emote.shortcode)
            isWorking = false
            if (error != null) errorMessage = error
        }
    }

    FormScreen {
        FormSection(
            header = "Pack",
            footer = if (isSpace)
                "Everyone in the space can use these in messages, reactions, and as stickers."
            else
                "Everyone in the room can use these in messages, reactions, and as stickers.",
        ) {
            if (emotes.isEmpty()) {
                FormFootnoteRow(
                    if (isSpace) "No custom emoji in this space yet."
                    else "No custom emoji in this room yet.")
            } else {
                emotes.forEachIndexed { index, emote ->
                    if (index > 0) FormRowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        EmoteImageView(url = emote.url, size = 28.dp, loader = loader)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(emote.token, color = colors.textPrimary, fontSize = 15.sp,
                                 maxLines = 1)
                            Text(
                                when {
                                    emote.usage.isEmpty() -> "Emoji & sticker"
                                    "sticker" in emote.usage -> "Sticker"
                                    else -> "Emoji"
                                },
                                color = colors.textSecondary, fontSize = 12.sp)
                        }
                        IconButton(onClick = { remove(emote) }, enabled = !isWorking) {
                            Icon(Icons.Outlined.Delete,
                                 contentDescription = "Remove ${emote.token}",
                                 tint = colors.unreadMention,
                                 modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        FormSection(
            header = "Add Emoji or Sticker",
            footer = "Images are scaled down to 256 px; small animated GIFs are kept as-is. " +
                "Emotes are shared as part of the ${if (isSpace) "space" else "room"}. " +
                "You need permission to change ${if (isSpace) "space" else "room"} settings.",
        ) {
            FormTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = "Name (becomes :shortcode:)",
                autoCorrect = false,
            )
            FormRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Usable as", color = colors.textPrimary, fontSize = 15.sp,
                     modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    EmoteUsage.entries.forEachIndexed { index, usage ->
                        SegmentedButton(
                            selected = newUsage == usage,
                            onClick = { newUsage = usage },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = EmoteUsage.entries.size),
                        ) {
                            Text(usage.label, fontSize = 13.sp)
                        }
                    }
                }
            }
            FormRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                stagedPreview?.let { preview ->
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                TextButton(onClick = { imagePicker() }) {
                    Text(if (stagedImage == null) "Choose Image…" else "Change Image…",
                         color = colors.accent, fontSize = 15.sp)
                }
            }
            FormRowDivider()
            if (isWorking) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                FormButtonRow(
                    title = "Add to Pack",
                    enabled = stagedImage != null
                        && CustomEmojiStore.sanitizedShortcode(newName).isNotEmpty(),
                    onClick = { add() },
                )
            }
            errorMessage?.let { error ->
                FormRowDivider()
                Text(error, color = colors.unreadMention, fontSize = 14.sp,
                     modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

private enum class EmoteUsage(val label: String) {
    EMOTICON("Emoji"), STICKER("Sticker"), BOTH("Both");

    /** MSC2545: an empty usage list means usable as both. */
    val usageSet: Set<String>
        get() = when (this) {
            EMOTICON -> setOf("emoticon")
            STICKER -> setOf("sticker")
            BOTH -> emptySet()
        }
}

// MARK: - Image staging

private class StagedEmoteImage(val data: ByteArray, val mimeType: String)

/**
 * Keeps a small animated GIF/WebP intact (multi-frame, ≤512 KB); flattens
 * everything else to a ≤256 px PNG. Null when the bytes aren't an image.
 */
private fun stageEmoteImage(raw: ByteArray): StagedEmoteImage? {
    val attrs = MediaProcessing.imageAttributes(raw)
    if (attrs != null && raw.size <= 512 * 1024 && isAnimatedImage(raw)) {
        return StagedEmoteImage(raw, attrs.mimetype)
    }
    val processed = processedEmoteImage(raw) ?: return null
    return StagedEmoteImage(processed, "image/png")
}

private fun isAnimatedImage(data: ByteArray): Boolean = runCatching {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(data))
    ImageDecoder.decodeDrawable(source) is AnimatedImageDrawable
}.getOrDefault(false)

/** Downscales to ≤256 px (aspect preserved) and re-encodes as PNG to keep transparency. */
private fun processedEmoteImage(data: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest > 256) {
        val scale = 256.0 / largest
        bitmap = Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
        )
    }
    val output = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) || output.size() == 0) return null
    return output.toByteArray()
}

private fun pixelSize(data: ByteArray): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return bounds.outWidth to bounds.outHeight
}
