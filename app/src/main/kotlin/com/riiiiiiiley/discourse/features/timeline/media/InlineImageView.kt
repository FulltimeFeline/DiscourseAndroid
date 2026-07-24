package com.riiiiiiiley.discourse.features.timeline.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.Blurhash
import com.riiiiiiiley.discourse.core.Preferences
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.models.ImageItem
import com.riiiiiiiley.discourse.ui.theme.DiscourseColors
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** ≈ the iOS `.quaternary.opacity(0.5)` placeholder fill. */
internal val DiscourseColors.mediaPlaceholderFill: Color
    get() = textSecondary.copy(alpha = 0.12f)

/**
 * Inline image: fixed footprint from the event's ImageInfo, filled by the
 * SDK thumbnail when it arrives. Tap opens the full image in an in-app
 * full-screen viewer (the QuickLook analogue).
 */
@Composable
fun InlineImageView(
    image: ImageItem,
    loader: MediaLoader,
    preferences: Preferences,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val scope = rememberCoroutineScope()
    // Clamped so extreme densities don't fragment cache keys (iOS parity).
    val thumbnailScale = LocalDensity.current.density.coerceIn(1f, 3f)
    val pixelSize =
        max(image.displaySize.width.value, image.displaySize.height.value) * thumbnailScale

    var loaded by remember(image.source.url) { mutableStateOf<Bitmap?>(null) }
    /** The thumbnail fetch came back empty; show a broken-image state. */
    var loadFailed by remember(image.source.url) { mutableStateOf(false) }
    /** Bumped by tap-to-retry to re-fire the load task. */
    var loadAttempt by remember(image.source.url) { mutableIntStateOf(0) }
    /**
     * Set when the user taps a data-saver placeholder; consulted only when
     * auto-download is off (stickers ignore the gate).
     */
    var manuallyRequested by remember(image.source.url) { mutableStateOf(false) }
    /**
     * Decoded blurhash, shown behind the spinner so images fade in from their
     * colors instead of a grey box.
     */
    var blurhash by remember(image.source.url) { mutableStateOf<Bitmap?>(null) }
    /** Full-resolution bytes; drives the in-app viewer. */
    var viewerData by remember(image.source.url) { mutableStateOf<ByteArray?>(null) }

    val preferencesState = preferences.state.collectAsStateWithLifecycle().value
    val autoDownloadImages = preferencesState.autoDownloadImages
    val reduceMotion = preferencesState.reduceMotion
    val displayImage = loaded ?: loader.cachedThumbnail(image.source, pixelSize)
    // Data-saver gate: with auto-download off, non-sticker images wait behind
    // a "Tap to load" placeholder. Stickers always load; an already-available
    // image never gates.
    val shouldDefer =
        !autoDownloadImages && !image.isSticker && !manuallyRequested && displayImage == null
    val currentShouldDefer by rememberUpdatedState(shouldDefer)

    LaunchedEffect(loadAttempt, image.source.url) {
        // Data-saver: don't touch the network until the user taps.
        if (currentShouldDefer) return@LaunchedEffect
        val result = loader.thumbnail(image.source, pixelSize)
        loaded = result
        // Not on cancellation (scroll recycling; a cancelled LaunchedEffect
        // never reaches here); only a fetch that came back empty.
        if (result == null && loader.cachedThumbnail(image.source, pixelSize) == null) {
            loadFailed = true
        }
    }

    // Decode the blurhash once (tiny; the view scales it up). Skip if the
    // real image is already available.
    LaunchedEffect(image.blurhash ?: "") {
        val hash = image.blurhash
        if (blurhash != null || displayImage != null || hash.isNullOrEmpty()) return@LaunchedEffect
        val size = image.displaySize
        val w = 24
        val h = max(1, (24 * size.height.value / max(size.width.value, 1f)).roundToInt())
        blurhash = withContext(Dispatchers.Default) { Blurhash.decode(hash, w, h) }
    }

    val accessibilityText = when {
        shouldDefer -> "Image not loaded. Tap to load."
        loadFailed -> "Image failed to load. Tap to retry."
        else -> {
            // "Image, cat.png" rather than a silent tap target. The caption
            // reads as its own element below, so it isn't duplicated here.
            val base = if (image.isSticker) "Sticker" else "Image"
            if (image.filename.isEmpty()) base else "$base, ${image.filename}"
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(image.displaySize.width, image.displaySize.height)
                .clip(RoundedCornerShape(10.dp))
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityText
                }
                // The tap doubles as retry in the failed state.
                .clickable {
                    when {
                        shouldDefer -> {
                            // Data-saver: first tap requests the deferred download.
                            manuallyRequested = true
                            loadAttempt += 1
                        }
                        loadFailed -> {
                            loadFailed = false
                            loadAttempt += 1
                        }
                        else -> scope.launch {
                            loader.fullContent(image.source)?.let { viewerData = it }
                        }
                    }
                },
        ) {
            when {
                displayImage != null -> Image(
                    bitmap = displayImage.asImageBitmap(),
                    contentDescription = null,
                    // The frame matches the declared aspect ratio when there is
                    // one, so Fit only differs when dimensions are missing/wrong,
                    // where Crop would clip. Stickers must never be cropped.
                    contentScale = if (image.isSticker || !image.hasKnownSize) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                shouldDefer -> MediaStatePlaceholder(
                    icon = Icons.Rounded.ArrowCircleDown,
                    text = "Tap to load image",
                )
                loadFailed -> MediaStatePlaceholder(
                    icon = Icons.Rounded.BrokenImage,
                    text = "Tap to retry",
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hash = blurhash
                    if (hash != null) {
                        Image(
                            bitmap = hash.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(colors.mediaPlaceholderFill))
                    }
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        val caption = image.caption
        if (!caption.isNullOrEmpty()) {
            SelectionContainer {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                )
            }
        }
    }

    viewerData?.let { data ->
        FullScreenImageViewer(
            data = data,
            filename = image.filename,
            reduceMotion = reduceMotion,
            onDismiss = { viewerData = null },
        )
    }
}

/** Data-saver / failed placeholder: quiet fill, icon and caption. */
@Composable
internal fun MediaStatePlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    val colors = LocalDiscourseColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(colors.mediaPlaceholderFill),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

/** Conservative GL texture ceiling; larger decodes render blank or OOM. */
private const val MAX_TEXTURE_SIDE = 4096

/**
 * Decodes at the largest sample that fits the texture limit: panoramas and
 * big scans exceed the GL max texture size and would upload blank, and
 * full-size photos risk OOM on low-RAM devices (QuickLook tiles instead).
 */
private fun decodeDownsampled(data: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_TEXTURE_SIDE ||
        bounds.outHeight / sampleSize > MAX_TEXTURE_SIDE
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}

private fun isGif(data: ByteArray): Boolean =
    data.size >= 4 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() &&
        data[2] == 'F'.code.toByte() && data[3] == '8'.code.toByte()

/**
 * Full-screen zoomable viewer over the downloaded full-resolution bytes —
 * the QuickLook stand-in. Decodes off-main; pinch to zoom, drag to pan.
 * Share/save toolbar covers the QuickLook export affordances; GIFs animate
 * via AnimatedImageDrawable (this viewer is the only place they play).
 */
@Composable
private fun FullScreenImageViewer(
    data: ByteArray,
    filename: String,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val animated = remember(data) { isGif(data) }
    val bitmap by produceState<Bitmap?>(initialValue = null, data) {
        if (animated) return@produceState
        value = withContext(Dispatchers.Default) { decodeDownsampled(data) }
    }
    val animatedDrawable by produceState<Drawable?>(initialValue = null, data) {
        if (!animated) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data)))
            }.getOrNull()
        }
    }
    // One-shot confirmation after "Save": iOS Photos shows its own UI.
    var saveNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saveNotice) {
        if (saveNotice != null) {
            kotlinx.coroutines.delay(2_000)
            saveNotice = null
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val image = bitmap
        val drawable = animatedDrawable
        // Intrinsic content aspect ratio, used to clamp pan to the fitted image
        // bounds so an edge can never be dragged inside the container.
        val contentWidth = image?.width ?: drawable?.intrinsicWidth ?: 0
        val contentHeight = image?.height ?: drawable?.intrinsicHeight ?: 0

        // Animated transform: pinch/pan write through it directly (snapTo), and
        // double-tap / release settle animate it. graphicsLayer scales about the
        // box center, so offsets are relative to center.
        val scale = remember { Animatable(1f) }
        val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

        // The fit-centered content rect within the container, at scale 1.
        fun fittedSize(): androidx.compose.ui.geometry.Size {
            val cw = containerSize.width.toFloat()
            val ch = containerSize.height.toFloat()
            if (cw <= 0f || ch <= 0f || contentWidth <= 0 || contentHeight <= 0) {
                return androidx.compose.ui.geometry.Size(cw, ch)
            }
            val contentAspect = contentWidth.toFloat() / contentHeight.toFloat()
            val boxAspect = cw / ch
            return if (contentAspect > boxAspect) {
                androidx.compose.ui.geometry.Size(cw, cw / contentAspect)
            } else {
                androidx.compose.ui.geometry.Size(ch * contentAspect, ch)
            }
        }

        // Largest offset (in each axis) that keeps the scaled content covering
        // the container edges; 0 when the content is smaller than the container.
        fun maxOffsetFor(s: Float): Offset {
            val fitted = fittedSize()
            val overW = (fitted.width * s - containerSize.width) / 2f
            val overH = (fitted.height * s - containerSize.height) / 2f
            return Offset(max(0f, overW), max(0f, overH))
        }

        fun clampOffset(target: Offset, s: Float): Offset {
            val bound = maxOffsetFor(s)
            return Offset(
                target.x.coerceIn(-bound.x, bound.x),
                target.y.coerceIn(-bound.y, bound.y),
            )
        }

        val settleSpec = tween<Float>(durationMillis = if (reduceMotion) 0 else 220)
        val settleOffsetSpec = tween<Offset>(durationMillis = if (reduceMotion) 0 else 220)

        // Double-tap zooms toward the tapped point (or back out to fit), keeping
        // that point fixed under the finger.
        suspend fun doubleTapZoom(tap: Offset) {
            val targetScale = if (scale.value > 1.01f) 1f else 2.5f
            if (targetScale == 1f) {
                scope.launch { scale.animateTo(1f, settleSpec) }
                offset.animateTo(Offset.Zero, settleOffsetSpec)
                return
            }
            val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
            val tapFromCenter = tap - center
            // Keep the tapped point stationary across the scale change.
            val oldScale = scale.value
            val newOffset = offset.value + tapFromCenter * (oldScale - targetScale)
            scope.launch { scale.animateTo(targetScale, settleSpec) }
            offset.animateTo(clampOffset(newOffset, targetScale), settleOffsetSpec)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { containerSize = it }
                .pointerInput(contentWidth, contentHeight) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale.value * zoom).coerceIn(1f, 6f)
                        val newOffset = if (newScale <= 1f) {
                            Offset.Zero
                        } else {
                            clampOffset(offset.value + pan, newScale)
                        }
                        scope.launch { scale.snapTo(newScale) }
                        scope.launch { offset.snapTo(newOffset) }
                    }
                }
                .pointerInput(contentWidth, contentHeight) {
                    detectTapGestures(onDoubleTap = { tap ->
                        scope.launch { doubleTapZoom(tap) }
                    })
                },
        ) {
            val zoomModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
            if (drawable != null) {
                AndroidView(
                    factory = { viewContext ->
                        ImageView(viewContext).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { view ->
                        view.setImageDrawable(drawable)
                        (drawable as? AnimatedImageDrawable)?.start()
                    },
                    onRelease = { (drawable as? AnimatedImageDrawable)?.stop() },
                    modifier = zoomModifier,
                )
            } else if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Image",
                    contentScale = ContentScale.Fit,
                    modifier = zoomModifier,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                IconButton(onClick = {
                    scope.launch { MediaExport.share(context, data, filename) }
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share image", tint = Color.White)
                }
                IconButton(onClick = {
                    scope.launch {
                        val saved = MediaExport.saveToGallery(context, data, filename)
                        saveNotice = if (saved) "Saved to gallery" else "Couldn't save"
                    }
                }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Save image", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            saveNotice?.let { notice ->
                Text(
                    notice,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
