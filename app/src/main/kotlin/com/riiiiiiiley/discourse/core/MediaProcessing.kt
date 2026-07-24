package com.riiiiiiiley.discourse.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Outgoing-media preprocessing: strip location metadata, generate the
 * thumbnails encrypted rooms need to paint a preview (no server thumbnailing
 * for E2EE), and extract a video poster frame. All work runs off-main.
 */
object MediaProcessing {
    data class ProcessedImage(
        val data: ByteArray,
        val mimetype: String,
        val width: ULong,
        val height: ULong,
    )

    data class Thumbnail(
        val data: ByteArray,
        val mimetype: String,
        val width: ULong,
        val height: ULong,
    )

    data class VideoAttributes(
        val duration: Double? = null,
        val width: ULong? = null,
        val height: ULong? = null,
        val thumbnail: Thumbnail? = null,
    )

    /**
     * Reads dimensions and mimetype without touching the bytes — the
     * "don't strip location" path, so GPS EXIF survives. null if the bytes
     * can't be read (caller falls back to a file send).
     */
    fun imageAttributes(data: ByteArray): ProcessedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return ProcessedImage(
            data = data,
            mimetype = bounds.outMimeType ?: "application/octet-stream",
            width = bounds.outWidth.toULong(),
            height = bounds.outHeight.toULong(),
        )
    }

    /** Every GPS IFD tag ExifInterface exposes — the whole block goes. */
    private val GPS_TAGS = arrayOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
    )

    /**
     * Multi-frame detection matching the iOS `CGImageSourceGetCount > 1`
     * pass-through: GIF, APNG (`acTL` chunk), and animated WebP (`ANIM`
     * chunk) must go out untouched or the animation flattens.
     */
    private fun isMultiFrame(data: ByteArray, mimetype: String): Boolean = when (mimetype) {
        "image/gif" -> true
        "image/png" -> containsMarker(data, "acTL")
        "image/webp" -> containsMarker(data, "ANIM")
        else -> false
    }

    /** Byte scan for a 4CC/chunk name; both live well before any pixel data. */
    private fun containsMarker(data: ByteArray, marker: String): Boolean {
        val bytes = marker.toByteArray(Charsets.US_ASCII)
        val limit = min(data.size, 64 * 1024)
        outer@ for (i in 0..(limit - bytes.size)) {
            for (j in bytes.indices) {
                if (data[i + j] != bytes[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * Nulls the GPS block while preserving the original container, encoding
     * and all non-GPS EXIF (no generation loss) — the
     * `CGImageDestinationAddImageFromSource(…, GPS: null)` analogue. Animated
     * images pass through untouched. A container ExifInterface can't rewrite
     * (e.g. HEIC) that still carries GPS falls back to a decode + re-encode:
     * privacy wins over fidelity there. Returns null only if the bytes can't
     * be read.
     */
    fun sanitizedImage(data: ByteArray): ProcessedImage? {
        val attrs = imageAttributes(data) ?: return null
        if (isMultiFrame(data, attrs.mimetype)) return attrs

        // Nothing to strip: the original bytes go out as-is.
        val hasGps = runCatching {
            val exif = ExifInterface(ByteArrayInputStream(data))
            GPS_TAGS.any { exif.getAttribute(it) != null }
        }.getOrDefault(false)
        if (!hasGps) return attrs

        // In-place rewrite for the containers ExifInterface can save
        // (JPEG/PNG/WebP): same pixels, same format, GPS gone.
        if (attrs.mimetype in setOf("image/jpeg", "image/png", "image/webp")) {
            val rewritten = runCatching {
                // On Android java.io.tmpdir is the app cache dir.
                val temp = File.createTempFile("discourse-sanitize", null)
                try {
                    temp.writeBytes(data)
                    val exif = ExifInterface(temp.path)
                    for (tag in GPS_TAGS) exif.setAttribute(tag, null)
                    exif.saveAttributes()
                    temp.readBytes()
                } finally {
                    temp.delete()
                }
            }.getOrNull()
            if (rewritten != null && rewritten.isNotEmpty()) {
                return attrs.copy(data = rewritten)
            }
        }

        // Last resort (unwritable container that has GPS): re-encode, baking
        // in orientation. Original bytes go out when even that fails,
        // matching the iOS destination-failure fallback.
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return attrs
        val bitmap = applyExifOrientation(decoded, data)
        val hasAlpha = bitmap.hasAlpha()
        val output = ByteArrayOutputStream()
        val ok = if (hasAlpha) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        if (!ok || output.size() == 0) return attrs
        return ProcessedImage(
            data = output.toByteArray(),
            mimetype = if (hasAlpha) "image/png" else "image/jpeg",
            width = bitmap.width.toULong(),
            height = bitmap.height.toULong(),
        )
    }

    /**
     * Downsampled thumbnail (≤`maxPixelSize` px) for the message's
     * ImageInfo/VideoInfo. JPEG unless the source has alpha (then PNG).
     */
    fun thumbnail(data: ByteArray, maxPixelSize: Int = 800): Thumbnail? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixelSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        var bitmap = applyExifOrientation(decoded, data)
        val largest = max(bitmap.width, bitmap.height)
        if (largest > maxPixelSize) {
            val scale = maxPixelSize.toDouble() / largest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true,
            )
        }
        return encode(bitmap)
    }

    /**
     * Duration, dimensions, and poster-frame thumbnail for a video staged as
     * bytes. Writes to a temp file (MediaMetadataRetriever wants one for
     * reliable seeking) and cleans up.
     */
    suspend fun videoAttributes(
        context: Context,
        data: ByteArray,
        filename: String,
    ): VideoAttributes = withContext(Dispatchers.IO) {
        val ext = filename.substringAfterLast('.', "").ifEmpty { "mov" }
        val tempFile = File(context.cacheDir, "discourse-upload-${UUID.randomUUID()}.$ext")
        try {
            tempFile.writeBytes(data)
        } catch (_: Exception) {
            return@withContext VideoAttributes()
        }
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.path)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val duration = durationMs?.let { it / 1000.0 }
                var width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull()
                var height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull()
                // Display size: bake the container rotation into the reported
                // dimensions (the iOS preferredTransform application).
                val rotation = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val w = width
                    width = height
                    height = w
                }
                // Seek in a little; frame 0 is often all black.
                val seekSeconds = min(1.0, (duration ?: 2.0) / 2)
                val frame = runCatching {
                    retriever.getFrameAtTime(
                        (seekSeconds * 1_000_000).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull()
                val thumbnail = frame?.let { full ->
                    val largest = max(full.width, full.height)
                    val scaled = if (largest > 800) {
                        val scale = 800.0 / largest
                        Bitmap.createScaledBitmap(
                            full,
                            max(1, (full.width * scale).toInt()),
                            max(1, (full.height * scale).toInt()),
                            true,
                        )
                    } else {
                        full
                    }
                    encode(scaled)
                }
                VideoAttributes(
                    duration = duration,
                    width = width?.takeIf { it > 0 }?.toULong(),
                    height = height?.takeIf { it > 0 }?.toULong(),
                    thumbnail = thumbnail,
                )
            } finally {
                runCatching { retriever.release() }
            }
        } catch (_: Exception) {
            VideoAttributes()
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Rotates/flips per the EXIF orientation tag — BitmapFactory ignores it,
     * so a re-encode without this would ship the image sideways.
     */
    private fun applyExifOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(data))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun encode(bitmap: Bitmap): Thumbnail? {
        val hasAlpha = bitmap.hasAlpha()
        val output = ByteArrayOutputStream()
        val ok = if (hasAlpha) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)
        }
        if (!ok || output.size() == 0) return null
        return Thumbnail(
            data = output.toByteArray(),
            mimetype = if (hasAlpha) "image/png" else "image/jpeg",
            width = bitmap.width.toULong(),
            height = bitmap.height.toULong(),
        )
    }
}
