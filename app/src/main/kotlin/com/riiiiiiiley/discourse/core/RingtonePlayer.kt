package com.riiiiiiiley.discourse.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Loops a synthesized telephone ring (440+480 Hz, 2s on / 4s off) for
 * incoming calls, generated in code so we ship no audio asset.
 * Port of iOS RingtonePlayer; main-thread confined like its @MainActor.
 */
object RingtonePlayer {
    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val samples = ringSamples
        // USAGE_NOTIFICATION_RINGTONE follows the ring volume/DND, the Android
        // analogue of iOS `.playback` ringing through the silent switch.
        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // Static mode: the whole 6s cycle fits in one buffer and the
                // hardware loops it without a feeder thread.
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        }.getOrNull() ?: return
        built.write(samples, 0, samples.size)
        built.setLoopPoints(0, samples.size, -1)
        built.setVolume(0.6f)
        built.play()
        track = built
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private const val SAMPLE_RATE = 22050

    /**
     * One 6-second ring cycle as 16-bit mono PCM. iOS wraps the same samples
     * in a WAV container for AVAudioPlayer; AudioTrack takes raw PCM directly.
     */
    private val ringSamples: ShortArray by lazy {
        val sampleRate = SAMPLE_RATE.toDouble()
        val toneSeconds = 2.0
        val totalSeconds = 6.0
        val frameCount = (sampleRate * totalSeconds).toInt()
        val toneFrames = (sampleRate * toneSeconds).toInt()
        val fadeFrames = (sampleRate * 0.02).toInt()

        val samples = ShortArray(frameCount)
        for (i in 0 until toneFrames) {
            val t = i / sampleRate
            var value = 0.22 * sin(2 * PI * 440 * t) + 0.22 * sin(2 * PI * 480 * t)
            // Short fades avoid clicks at the tone edges.
            if (i < fadeFrames) value *= i.toDouble() / fadeFrames
            if (i > toneFrames - fadeFrames) value *= (toneFrames - i).toDouble() / fadeFrames
            samples[i] = (value.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        samples
    }
}
