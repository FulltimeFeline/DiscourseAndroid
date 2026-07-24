package com.riiiiiiiley.discourse.features.timeline.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riiiiiiiley.discourse.core.media.MediaLoader
import com.riiiiiiiley.discourse.models.AudioItem
import com.riiiiiiiley.discourse.models.MediaSourceBox
import com.riiiiiiiley.discourse.ui.theme.LocalDiscourseColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Owns voice-message playback for a whole timeline so audio keeps playing while
 * its row scrolls out of the lazy viewport (the row is destroyed, this isn't).
 * Keyed by timeline item id — two events can share one mxc URL.
 *
 * Main-thread confined (the iOS @MainActor analogue).
 */
class AudioPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _activeItemId = MutableStateFlow<String?>(null)
    val activeItemId: StateFlow<String?> = _activeItemId

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress

    /** Seconds, from the decoder (more accurate than the event's metadata). */
    private val _playerDuration = MutableStateFlow<Double?>(null)
    val playerDuration: StateFlow<Double?> = _playerDuration

    /**
     * In-flight / failed download, surfaced per row so the spinner and retry
     * survive row recycling.
     */
    private val _loadingItemId = MutableStateFlow<String?>(null)
    val loadingItemId: StateFlow<String?> = _loadingItemId

    private val _failedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val failedItemIds: StateFlow<Set<String>> = _failedItemIds

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    fun isActive(itemId: String): Boolean = _activeItemId.value == itemId

    fun toggle(itemId: String, source: MediaSourceBox, loader: MediaLoader) {
        val player = this.player
        if (_activeItemId.value == itemId && player != null) {
            if (_isPlaying.value) {
                player.pause()
                _isPlaying.value = false
                progressJob?.cancel()
                abandonAudioFocus()
            } else {
                requestAudioFocus()
                // After completion MediaPlayer.start() restarts from the top.
                player.start()
                _isPlaying.value = true
                startProgressTimer()
            }
            return
        }
        if (_loadingItemId.value != null) return
        // Switching items: silence the current one before loading the next.
        stopAll()
        _loadingItemId.value = itemId
        _failedItemIds.value = _failedItemIds.value - itemId
        scope.launch {
            try {
                val data = loader.fullContent(source)
                val newPlayer = data?.let { createPlayer(it, source.url) }
                if (newPlayer == null) {
                    _failedItemIds.value = _failedItemIds.value + itemId
                    return@launch
                }
                newPlayer.setOnCompletionListener {
                    scope.launch { finishPlayback() }
                }
                this@AudioPlaybackController.player = newPlayer
                _activeItemId.value = itemId
                _playerDuration.value =
                    newPlayer.duration.takeIf { it > 0 }?.let { it / 1000.0 }
                _progress.value = 0.0
                requestAudioFocus()
                newPlayer.start()
                _isPlaying.value = true
                startProgressTimer()
            } finally {
                _loadingItemId.value = null
            }
        }
    }

    /**
     * Stops playback and tears the focus down. Called on a hard stop and on
     * room-leave / thread-dismiss / park.
     */
    fun stopAll() {
        progressJob?.cancel()
        progressJob = null
        val wasPlaying = _isPlaying.value
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        _activeItemId.value = null
        _playerDuration.value = null
        _isPlaying.value = false
        _progress.value = 0.0
        if (wasPlaying) abandonAudioFocus()
    }

    /**
     * MediaPlayer wants a seekable source; write the (decrypted) bytes to a
     * cache file keyed by URL so replays and other events on the same media
     * reuse it. Runs off-main; null on any failure.
     */
    private suspend fun createPlayer(data: ByteArray, url: String): MediaPlayer? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(
                    appContext.cacheDir,
                    "discourse-audio-${url.hashCode().toLong().absoluteValue}",
                )
                if (!file.exists() || file.length() != data.size.toLong()) {
                    file.writeBytes(data)
                }
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(file.path)
                    prepare()
                }
            }.getOrNull()
        }

    // Audio focus so other media pauses/ducks while a voice message plays —
    // the AVAudioSession activate/deactivate analogue.

    /**
     * Focus loss (incoming phone call, another app starting playback) pauses
     * us, the analogue of the AVAudioSession interruption that pauses the iOS
     * AVAudioPlayer. The 100ms poll then sees !isPlaying and resets the UI.
     */
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> scope.launch {
                val player = this@AudioPlaybackController.player ?: return@launch
                if (_isPlaying.value) {
                    runCatching { player.pause() }
                    _isPlaying.value = false
                    progressJob?.cancel()
                    abandonAudioFocus()
                }
            }
            else -> Unit
        }
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun finishPlayback() {
        if (!_isPlaying.value) return
        _isPlaying.value = false
        _progress.value = 0.0
        progressJob?.cancel()
        abandonAudioFocus()
    }

    private fun startProgressTimer() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(100)
                val player = this@AudioPlaybackController.player ?: continue
                val duration = runCatching { player.duration }.getOrDefault(0)
                _progress.value = if (duration > 0) {
                    runCatching { player.currentPosition }.getOrDefault(0).toDouble() / duration
                } else {
                    0.0
                }
                // Belt-and-braces alongside the completion listener (the iOS
                // polling path): a player that stopped underneath us resets.
                val stillPlaying = runCatching { player.isPlaying }.getOrDefault(false)
                if (!stillPlaying && _isPlaying.value) {
                    finishPlayback()
                }
            }
        }
    }
}

/** Inline voice-message (and audio file) player: play/pause, waveform, time. */
@Composable
fun VoiceMessageView(
    itemId: String,
    audio: AudioItem,
    loader: MediaLoader,
    controller: AudioPlaybackController,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current

    val activeItemId by controller.activeItemId.collectAsStateWithLifecycle()
    val controllerPlaying by controller.isPlaying.collectAsStateWithLifecycle()
    val controllerProgress by controller.progress.collectAsStateWithLifecycle()
    val playerDuration by controller.playerDuration.collectAsStateWithLifecycle()
    val loadingItemId by controller.loadingItemId.collectAsStateWithLifecycle()
    val failedItemIds by controller.failedItemIds.collectAsStateWithLifecycle()

    val isActive = activeItemId == itemId
    val isPlaying = isActive && controllerPlaying
    val progress = if (isActive) controllerProgress else 0.0
    val isLoading = loadingItemId == itemId
    val loadFailed = itemId in failedItemIds

    val duration = (if (isActive) playerDuration else null) ?: audio.duration ?: 0.0
    val remainingSeconds = if (isPlaying || progress > 0) duration * (1 - progress) else duration
    val timeLabel = String.format(
        Locale.US, "%d:%02d", remainingSeconds.toInt() / 60, remainingSeconds.toInt() % 60,
    )

    val playLabel = when {
        loadFailed -> "Couldn't load audio. Retry"
        isPlaying -> "Pause"
        audio.isVoiceMessage -> "Play voice message"
        else -> "Play audio"
    }
    // Spelled-out duration; the visible "1:23" reads poorly when spoken.
    val spelled = spelledDuration(remainingSeconds.roundToLong())
    val timeValue = if (isPlaying || progress > 0) "$spelled remaining" else spelled

    Row(
        modifier = modifier
            .background(colors.mediaPlaceholderFill, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 44dp touch target around the 32dp visible control.
        Box(
            modifier = Modifier
                .size(44.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = playLabel
                    // Time rides the button as its value; the visible time text
                    // and waveform are hidden so they don't double-read.
                    stateDescription = timeValue
                }
                .clickable { controller.toggle(itemId, audio.source, loader) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.accent.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.textOnAccent,
                    )
                    loadFailed -> Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = colors.textOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    else -> Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = colors.textOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        WaveformBars(
            samples = audio.waveform,
            progress = progress,
            modifier = Modifier.size(140.dp, 26.dp).clearAndSetSemantics { },
        )

        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = colors.textSecondary,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/** Static waveform with played-portion tinting. */
@Composable
fun WaveformBars(
    samples: List<Float>,
    progress: Double = 0.0,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDiscourseColors.current
    val playedColor = colors.accent
    val restColor = colors.textSecondary.copy(alpha = 0.5f)
    Canvas(modifier) {
        val barCount = 36
        val display = resampledWaveform(samples, barCount)
        val slot = size.width / barCount
        val barWidth = slot * 0.65f
        val minHeight = 3.dp.toPx()
        for (index in 0 until barCount) {
            val played = index.toDouble() / barCount < progress
            val barHeight = max(minHeight, size.height * display[index])
            drawRoundRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(index * slot, (size.height - barHeight) / 2),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private fun resampledWaveform(samples: List<Float>, count: Int): List<Float> {
    if (samples.isEmpty()) {
        return (0 until count).map {
            (0.25 + 0.6 * abs(sin(it * 12.9898))).toFloat()
        }
    }
    return (0 until count).map { index ->
        val position = index.toFloat() / count * samples.size
        val sample = samples[min(samples.size - 1, position.toInt())]
        max(0.12f, min(1f, sample))
    }
}

/** "2 minutes, 5 seconds" for accessibility (the iOS wide units formatter). */
private fun spelledDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val parts = mutableListOf<String>()
    if (minutes > 0) parts.add(if (minutes == 1L) "1 minute" else "$minutes minutes")
    if (seconds > 0 || minutes == 0L) parts.add(if (seconds == 1L) "1 second" else "$seconds seconds")
    return parts.joinToString(", ")
}
