package com.riiiiiiiley.discourse.features.timeline.media

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.riiiiiiiley.discourse.core.Blurhash
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.models.VideoItem
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Inline video: a poster frame with a play control and duration badge. Tapping
 * downloads (and decrypts) the file, then plays it in a full-screen media3
 * player, which gives scrubbing and fullscreen for free. Mirrors
 * [InlineImageView].
 */
@Composable
fun VideoAttachmentView(
    video: VideoItem,
    loader: MediaLoader,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thumbnailScale = LocalDensity.current.density.coerceIn(1f, 3f)

    var poster by remember(video.source.url) { mutableStateOf<Bitmap?>(null) }
    var blurhash by remember(video.source.url) { mutableStateOf<Bitmap?>(null) }
    /** Already downloaded this session: reopen without re-fetching. */
    var playerFile by remember(video.source.url) { mutableStateOf<File?>(null) }
    var showPlayer by remember(video.source.url) { mutableStateOf(false) }
    var isDownloading by remember(video.source.url) { mutableStateOf(false) }
    var downloadFailed by remember(video.source.url) { mutableStateOf(false) }

    LaunchedEffect(video.source.url) {
        val thumb = video.thumbnailSource
        if (thumb == null || poster != null) return@LaunchedEffect
        poster = loader.thumbnail(
            thumb,
            pixelSize = max(video.displaySize.width.value, video.displaySize.height.value) *
                thumbnailScale,
        )
    }
    LaunchedEffect(video.blurhash ?: "") {
        val hash = video.blurhash
        if (blurhash != null || poster != null || hash.isNullOrEmpty()) return@LaunchedEffect
        val size = video.displaySize
        val h = max(1, (24 * size.height.value / max(size.width.value, 1f)).roundToInt())
        blurhash = withContext(Dispatchers.Default) { Blurhash.decode(hash, 24, h) }
    }

    fun open() {
        if (isDownloading) return
        if (playerFile != null) {
            showPlayer = true
            return
        }
        isDownloading = true
        downloadFailed = false
        scope.launch {
            try {
                val file = videoTemporaryFile(video, loader, context.cacheDir)
                if (file == null) {
                    downloadFailed = true
                    return@launch
                }
                playerFile = file
                showPlayer = true
            } finally {
                isDownloading = false
            }
        }
    }

    val accessibilityText =
        if (video.filename.isEmpty()) "Video" else "Video, ${video.filename}"

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(video.displaySize.width, video.displaySize.height)
                .clip(RoundedCornerShape(10.dp))
                .semantics {
                    role = Role.Button
                    contentDescription = accessibilityText
                }
                .clickable { open() },
        ) {
            // Poster body.
            val posterImage = poster
            val hash = blurhash
            when {
                posterImage != null -> Image(
                    bitmap = posterImage.asImageBitmap(),
                    contentDescription = null,
                    contentScale = if (video.hasKnownSize) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                hash != null -> Image(
                    bitmap = hash.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Box(
                    // No poster frame supplied: a neutral film placeholder.
                    modifier = Modifier.fillMaxSize().background(colors.mediaPlaceholderFill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            // Play overlay: a single translucent scrim disc with a centered
            // glyph — a clean Material media affordance. The disc stays a fixed
            // 56dp across all three states so it never jumps between them.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = if (downloadFailed) {
                                Icons.Rounded.RestartAlt
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }

            // Duration badge.
            video.durationText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        val caption = video.caption
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

    val file = playerFile
    if (showPlayer && file != null) {
        VideoPlayerDialog(file = file, filename = video.filename, onDismiss = { showPlayer = false })
    }
}

/**
 * Downloads the full video into a stable cache file (named by source, so
 * repeat opens reuse it), with an extension the player understands.
 */
suspend fun videoTemporaryFile(video: VideoItem, loader: MediaLoader, cacheDir: File): File? {
    val data = loader.fullContent(video.source) ?: return null
    val name = "discourse-${video.source.url.hashCode().toLong().absoluteValue}" +
        ".${videoFileExtension(video)}"
    val file = File(cacheDir, name)
    // Loader work stays on the main thread; the multi-MB write hops off.
    val wrote = withContext(Dispatchers.IO) {
        runCatching { file.writeBytes(data) }.isSuccess
    }
    return if (wrote) file else null
}

private fun videoFileExtension(video: VideoItem): String {
    val fromName = video.filename.substringAfterLast('.', "")
    if (fromName.isNotEmpty()) return fromName
    return when (video.mimeType) {
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        else -> "mp4"
    }
}

/**
 * Full-screen media3 player over the downloaded file — the QuickLook player
 * stand-in. Takes audio focus for the duration of playback.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerDialog(file: File, filename: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // Pause when the app backgrounds: the dialog composition stays alive with
    // the paused activity, so the audio would otherwise keep playing (the iOS
    // QuickLook player pauses automatically on resign-active).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                // QuickLook's share sheet / save analogues.
                IconButton(onClick = {
                    scope.launch {
                        val data = withContext(Dispatchers.IO) {
                            runCatching { file.readBytes() }.getOrNull()
                        } ?: return@launch
                        MediaExport.share(context, data, filename.ifEmpty { file.name })
                    }
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share video", tint = Color.White)
                }
                IconButton(onClick = {
                    scope.launch {
                        val data = withContext(Dispatchers.IO) {
                            runCatching { file.readBytes() }.getOrNull()
                        } ?: return@launch
                        MediaExport.saveToGallery(context, data, filename.ifEmpty { file.name })
                    }
                }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Save video", tint = Color.White)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
