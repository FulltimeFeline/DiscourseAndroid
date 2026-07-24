package com.riiiiiiiley.discourse.features.timeline

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Records composer voice messages (AAC/m4a) and samples a waveform. Port of
 * the iOS VoiceRecorder; the RECORD_AUDIO runtime permission is requested by
 * the composer before `start()` (which just returns false without it).
 */
class VoiceRecorder(private val context: Context) {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /** Seconds. */
    private val _duration = MutableStateFlow(0.0)
    val duration: StateFlow<Double> = _duration

    /** 0…1 normalised amplitude samples, one per 50 ms meter tick. */
    private val _levels = MutableStateFlow<List<Float>>(emptyList())
    val levels: StateFlow<List<Float>> = _levels

    /**
     * True when the system stops the recorder out from under us; the composer
     * tears down the recording UI and surfaces an error.
     */
    private val _interrupted = MutableStateFlow(false)
    val interrupted: StateFlow<Boolean> = _interrupted

    private var recorder: MediaRecorder? = null
    private var meterJob: Job? = null
    private var file: File? = null
    private var startedAt: Long = 0

    /** All state mutates on the main dispatcher, like the composer that reads it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start(): Boolean {
        if (_isRecording.value) return false
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (granted != PackageManager.PERMISSION_GRANTED) return false

        val file = File.createTempFile("discourse-voice-", ".m4a", context.cacheDir)
        val recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Same encode settings as iOS: 48 kHz mono AAC at 64 kbps.
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(file.absolutePath)
            // The system can stop the recorder without a normal stop() (call,
            // assistant, resource loss); finalize gracefully instead of
            // freezing a dead bar with a live red dot.
            recorder.setOnErrorListener { _, _, _ -> scope.launch { finalizeInterrupted() } }
            recorder.prepare()
            recorder.start()
        } catch (error: Exception) {
            runCatching { recorder.release() }
            file.delete()
            return false
        }

        this.recorder = recorder
        this.file = file
        _levels.value = emptyList()
        _duration.value = 0.0
        _interrupted.value = false
        _isRecording.value = true
        startedAt = SystemClock.elapsedRealtime()

        meterJob = scope.launch {
            while (isActive) {
                delay(50)
                sampleMeter()
            }
        }
        return true
    }

    private fun sampleMeter() {
        val recorder = this.recorder ?: return
        val amplitude = try {
            recorder.maxAmplitude
        } catch (error: IllegalStateException) {
            // Stopped externally between ticks.
            finalizeInterrupted()
            return
        }
        // Peak since the last tick (0…32767) → dB → the same 50 dB window the
        // iOS meter normalises into.
        val db = if (amplitude <= 0) -160f else 20f * log10(amplitude / 32767f)
        val normalised = ((db + 50f) / 50f).coerceIn(0f, 1f)
        _levels.value = _levels.value + normalised
        _duration.value = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
    }

    private fun finalizeInterrupted() {
        meterJob?.cancel()
        meterJob = null
        recorder?.let { runCatching { it.release() } }
        recorder = null
        _isRecording.value = false
        _interrupted.value = true
        file?.delete()
        file = null
    }

    /** Stops and returns the recording, or null if cancelled/too short. */
    fun stop(cancelled: Boolean = false): VoiceRecording? {
        meterJob?.cancel()
        meterJob = null
        // After an external stop the clock keeps counting; latch the last
        // sampled duration so a partial take still sends.
        val finalDuration = max(
            _duration.value,
            if (recorder != null) (SystemClock.elapsedRealtime() - startedAt) / 1000.0 else 0.0,
        )
        recorder?.let {
            // stop() throws if nothing was captured yet; treat as an empty take.
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        recorder = null
        _isRecording.value = false

        val file = this.file
        this.file = null
        try {
            if (cancelled || finalDuration < 0.5 || file == null || !file.exists()) return null
            val data = runCatching { file.readBytes() }.getOrNull() ?: return null
            if (data.isEmpty()) return null

            // Downsample the level samples to ~100 waveform points.
            val target = 100
            val levels = _levels.value
            val waveform = mutableListOf<Float>()
            if (levels.isEmpty()) {
                repeat(target) { waveform.add(0.5f) }
            } else {
                val bucket = max(1, levels.size / target)
                var start = 0
                while (start < levels.size) {
                    val end = min(start + bucket, levels.size)
                    waveform.add(levels.subList(start, end).max())
                    start += bucket
                }
            }
            return VoiceRecording(data = data, duration = finalDuration, waveform = waveform)
        } finally {
            file?.delete()
        }
    }
}
