package com.riiiiiiiley.discourse.features.timeline

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlin.math.max
import kotlin.math.roundToInt

// Parsing (InlineEmotes / MentionParser) lives in EmoteParsing.kt; this file
// is the rendering half of iOS EmoteViews.swift.

/**
 * The loader surface these views need: a synchronous cache probe plus an
 * async fetch, both keyed by mxc URL. MediaLoader (media phase) implements
 * this — the method names mirror its iOS API so that port is mechanical.
 */
interface EmoteAssetLoader {
    /** Synchronous cache hit so recycled rows paint on the first frame. */
    fun cachedImage(mxcUrl: String, pixelSize: Float): Bitmap?
    suspend fun avatar(mxcUrl: String, pixelSize: Float): Bitmap?
}

/**
 * Emote bitmaps scaled to an exact pixel height so they can sit inline in
 * `Text` (whose inline placeholders need a fixed size up front). Keyed
 * url+height. Main-thread confined like the iOS @MainActor cache; the
 * synchronized blocks only guard against stray background callers.
 */
object EmoteRasterCache {
    private val cache = LruCache<String, ImageBitmap>(256)
    private val inFlight = mutableMapOf<String, Deferred<ImageBitmap?>>()

    fun cached(url: String, heightPx: Int): ImageBitmap? =
        synchronized(cache) { cache.get("$url#$heightPx") }

    suspend fun image(url: String, heightPx: Int, loader: EmoteAssetLoader): ImageBitmap? {
        val key = "$url#$heightPx"
        synchronized(cache) { cache.get(key) }?.let { return it }
        val running = synchronized(inFlight) { inFlight[key] }
        if (running != null) return running.await()
        val deferred = CompletableDeferred<ImageBitmap?>()
        synchronized(inFlight) { inFlight[key] = deferred }
        // The finally block is load-bearing: a failed/cancelled fetch (a row
        // scrolling away cancels its LaunchedEffect mid-flight) must still
        // clear the in-flight entry and complete the deferred, or every later
        // request for this url#height awaits forever (iOS Task always
        // completes with nil and clears inFlight).
        var image: ImageBitmap? = null
        try {
            val source = loader.avatar(mxcUrl = url, pixelSize = heightPx * 3f)
            image = source?.let { rasterize(it, heightPx) }
            if (image != null) synchronized(cache) { cache.put(key, image) }
        } finally {
            synchronized(inFlight) { inFlight.remove(key) }
            deferred.complete(image)
        }
        return image
    }

    /**
     * Redraws to fit a (1.8×height, height) box preserving aspect ratio, so
     * wide banners scale down whole instead of being squashed into the cap.
     */
    private fun rasterize(source: Bitmap, heightPx: Int): ImageBitmap? {
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        var targetWidth = heightPx.toFloat() * sourceWidth / sourceHeight
        var targetHeight = heightPx.toFloat()
        if (targetWidth > heightPx * 1.8f) {
            val scale = heightPx * 1.8f / targetWidth
            targetWidth *= scale
            targetHeight *= scale
        }
        return Bitmap.createScaledBitmap(
            source,
            max(1, targetWidth.roundToInt()),
            max(1, targetHeight.roundToInt()),
            true,
        ).asImageBitmap()
    }
}

/** iOS baseline: 21pt inline emote cap on the phone layout. */
private const val BASE_INLINE_HEIGHT = 21f

private fun clampedScale(fontScale: Float): Float = fontScale.coerceIn(0.8f, 1.4f)

/**
 * Message body text with `:shortcode:` tokens swapped for their emote
 * images: small inline with text, large (jumbo) when the message is nothing
 * but emotes. Falls back to the literal token until the image lands.
 *
 * `suffix` is the trailing decoration, e.g. the "(edited)" tag.
 * `jumboEmoji=false` keeps an all-emote message inline; mirrors the unicode
 * jumbo gate in MessageRow. `fontScale` is the chat text-size preference
 * (0.8…1.4); scales the inline emote height so emotes track the body text.
 */
@Composable
fun EmoteBodyText(
    body: String,
    emotes: Map<String, String>,
    loader: EmoteAssetLoader?,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    suffix: AnnotatedString? = null,
    jumboEmoji: Boolean = true,
    fontScale: Float = 1.0f,
) {
    val segments = remember(body, emotes) { InlineEmotes.segments(of = body, emotes = emotes) }
    val jumbo = if (jumboEmoji) jumboEmotes(segments) else null
    if (jumbo != null) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((token, url) in jumbo) {
                EmoteImageView(
                    url = url,
                    size = (44f * clampedScale(fontScale)).dp,
                    loader = loader,
                    contentDescription = token,
                )
            }
            if (suffix != null && suffix.isNotEmpty()) Text(suffix)
        }
    } else {
        InlineEmoteText(
            segments = segments,
            loader = loader,
            modifier = modifier,
            style = style,
            suffix = suffix,
            fontScale = fontScale,
        )
    }
}

/**
 * Emote urls (with tokens) when the message is nothing but emotes and
 * whitespace, rendered jumbo as real image views (a text placeholder can't
 * grow the line box safely at 44pt). Long runs (>6) fall back to inline size
 * rather than overflow the row.
 */
private fun jumboEmotes(segments: List<InlineEmotes.Segment>): List<Pair<String, String>>? {
    val found = mutableListOf<Pair<String, String>>()
    for (segment in segments) {
        when (segment) {
            is InlineEmotes.Segment.Text ->
                if (!segment.text.all { it.isWhitespace() }) return null
            is InlineEmotes.Segment.Emote -> found.add(segment.token to segment.url)
        }
    }
    if (found.isEmpty() || found.size > 6) return null
    return found
}

@Composable
private fun InlineEmoteText(
    segments: List<InlineEmotes.Segment>,
    loader: EmoteAssetLoader?,
    modifier: Modifier,
    style: TextStyle,
    suffix: AnnotatedString?,
    fontScale: Float,
) {
    val colors = LocalDiscourseColors.current
    val density = LocalDensity.current
    // Inline emote cap height, scaled by the chat text-size preference. In sp
    // so it also tracks the system font scale, like text does.
    val heightSp = (BASE_INLINE_HEIGHT * clampedScale(fontScale)).roundToInt()
    val heightPx = with(density) { heightSp.sp.toPx() }.roundToInt()

    // Re-renders as images land; the value itself is unused.
    var loadedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(segments, heightPx, loader) {
        if (loader == null) return@LaunchedEffect
        for (segment in segments) {
            val emote = segment as? InlineEmotes.Segment.Emote ?: continue
            if (EmoteRasterCache.cached(emote.url, heightPx) != null) continue
            if (EmoteRasterCache.image(emote.url, heightPx, loader) != null) loadedCount++
        }
    }

    // Touch the trigger so Compose re-runs this on image load.
    @Suppress("UNUSED_EXPRESSION") loadedCount
    val inlineContent = mutableMapOf<String, InlineTextContent>()
    val text = buildAnnotatedString {
        for (segment in segments) {
            when (segment) {
                is InlineEmotes.Segment.Text ->
                    append(RenderedBody.rendered(segment.text, accent = colors.accent))
                is InlineEmotes.Segment.Emote -> {
                    val image = EmoteRasterCache.cached(segment.url, heightPx)
                    if (image != null) {
                        val id = "emote:${segment.url}"
                        val aspect = image.width.toFloat() / image.height.toFloat()
                        inlineContent[id] = InlineTextContent(
                            Placeholder(
                                width = (heightSp * aspect).sp,
                                height = heightSp.sp,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                        ) {
                            Image(
                                bitmap = image,
                                contentDescription = segment.token,
                                contentScale = ContentScale.Fit,
                            )
                        }
                        appendInlineContent(id, alternateText = segment.token)
                        append("\u200B") // keeps selection/wrapping sane
                    } else {
                        withStyle(SpanStyle(color = colors.textSecondary)) { append(segment.token) }
                    }
                }
            }
        }
        if (suffix != null) append(suffix)
    }
    SelectionContainer {
        Text(text = text, modifier = modifier, style = style, inlineContent = inlineContent)
    }
}

private fun AnnotatedString.Builder.withStyle(style: SpanStyle, block: AnnotatedString.Builder.() -> Unit) {
    val index = pushStyle(style)
    block()
    pop(index)
}

/**
 * A single custom emote image at a fixed size (picker cells, reaction chips,
 * autocomplete rows), with a placeholder tile until the bitmap lands.
 */
@Composable
fun EmoteImageView(
    url: String,
    size: Dp,
    loader: EmoteAssetLoader?,
    contentDescription: String? = null,
) {
    val colors = LocalDiscourseColors.current
    val density = LocalDensity.current
    val pixelSize = with(density) { size.toPx() }
    var image by remember { mutableStateOf<Bitmap?>(null) }
    // Reset on url change (persistent view identity, e.g. a pack avatar
    // updating) so the old bitmap doesn't stick.
    LaunchedEffect(url, loader) {
        if (loader == null) {
            image = null
            return@LaunchedEffect
        }
        image = loader.cachedImage(mxcUrl = url, pixelSize = pixelSize)
        if (image == null) {
            image = loader.avatar(mxcUrl = url, pixelSize = pixelSize)
        }
    }
    // Synchronous cache hit so recycled rows paint on the first frame.
    val display = image ?: loader?.cachedImage(mxcUrl = url, pixelSize = pixelSize)
    if (display != null) {
        Image(
            bitmap = display.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            Modifier
                .size(size)
                .background(
                    colors.textPrimary.copy(alpha = 0.08f),
                    RoundedCornerShape(size * 0.2f),
                ),
        )
    }
}
