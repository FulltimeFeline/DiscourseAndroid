package com.riiiiiiiley.discourse.core.media

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.exifinterface.media.ExifInterface
import com.riiiiiiiley.discourse.models.MediaSourceBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fetches media through the SDK (E2EE decryption + caching on the Rust side)
 * and memory-caches decoded bitmaps. Thumbnails also persist to a capped
 * per-account disk cache so relaunches paint without the network.
 *
 * Main-thread confined (the iOS @MainActor analogue): all bookkeeping maps
 * mutate on the main dispatcher; decode and file IO hop off-main internally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaLoader(private val client: Client, context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Scale to the device; phones can't spare 256MB for thumbnails. maxMemory
    // is the Java-heap cap (the Android stand-in for physicalMemory / 8).
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(256L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val inFlight = HashMap<String, Deferred<Pair<Bitmap?, Boolean>>>()
    private val inFlightContent = HashMap<String, Deferred<ByteArray?>>()

    /**
     * Pixel sizes per URL — LruCache can't be enumerated cheaply, so this is
     * how [cachedThumbnail] finds a same-URL entry at another size. A stale
     * size just misses the cache.
     */
    private val cachedSizes = HashMap<String, MutableList<Int>>()

    /**
     * Memoized "does the source JSON carry an AES key" check; `toJson()` is
     * too costly to repeat per cache miss.
     */
    private val encryptedByUrl = HashMap<String, Boolean>()

    /**
     * Disk writes since the last trim. [prepareDiskCache] runs only at init,
     * so re-trim every N persists to bound a long-lived session.
     */
    private var diskWritesSinceTrim = 0

    /** Per-account on-disk downsampled thumbnails, so cold launches paint avatars without a round-trip. */
    private val diskCacheDirectory: File

    /** The Android memory-warning observer (iOS didReceiveMemoryWarning). */
    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        override fun onLowMemory() {
            scope.launch { purgeInMemoryCaches() }
        }

        override fun onTrimMemory(level: Int) {
            // UI_HIDDEN just means backgrounded, not pressure; every other
            // level is a memory signal. (The RUNNING_* / BACKGROUND constants
            // are deprecated in API 34, so match on the exception instead.)
            if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
            scope.launch { purgeInMemoryCaches() }
        }
    }

    init {
        // Namespace per account so logout can wipe exactly its thumbnails.
        val userId = runCatching { client.userId() }.getOrNull() ?: "global"
        diskCacheDirectory = thumbnailCacheDirectory(appContext, userId)
        scope.launch(Dispatchers.IO) { prepareDiskCache(diskCacheDirectory) }
        appContext.registerComponentCallbacks(trimCallbacks)
    }

    /** iOS deinit parity: drop the memory-warning observer when the session ends. */
    fun tearDown() {
        appContext.unregisterComponentCallbacks(trimCallbacks)
    }

    /**
     * Memory-warning response: drop the decoded bitmaps and bookkeeping maps.
     * Disk copies survive, so repopulating is cheap.
     */
    private fun purgeInMemoryCaches() {
        cache.evictAll()
        cachedSizes.clear()
        encryptedByUrl.clear()
    }

    /**
     * Keyed on url + rounded pixel size so different sizes of one image don't
     * collide in the cache or in-flight table.
     */
    private fun cacheKey(url: String, side: Int): String = "$url#$side"

    private fun roundedSide(pixelSize: Float): Int = max(1, pixelSize.roundToInt())

    private fun isEncrypted(box: MediaSourceBox): Boolean {
        encryptedByUrl[box.url]?.let { return it }
        val encrypted = box.source.toJson().contains("\"key\"")
        encryptedByUrl[box.url] = encrypted
        return encrypted
    }

    /**
     * Decodes + downsamples off-main. inSampleSize decoding never materializes
     * the full-size bitmap. When [persistTo] is set, also writes the bitmap
     * back to the disk cache while it's still off-main.
     */
    private suspend fun decodeThumbnail(
        data: ByteArray,
        maxPixelSize: Float,
        persistTo: File? = null,
    ): Bitmap? = withContext(Dispatchers.Default) {
        val target = max(1, maxPixelSize.roundToInt())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            ?: return@withContext null
        // Bake in EXIF orientation (the iOS CreateThumbnailWithTransform flag).
        var bitmap = bakeExifOrientation(decoded, data)
        val largest = max(bitmap.width, bitmap.height)
        if (largest > target) {
            val scale = target.toDouble() / largest
            bitmap = Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true,
            )
        }
        if (persistTo != null) {
            encodeForDisk(bitmap)?.let { encoded ->
                // Fire-and-forget; don't make the caller wait on file IO.
                scope.launch(Dispatchers.IO) { writeDiskThumbnail(encoded, persistTo) }
            }
        }
        bitmap
    }

    /**
     * Rotates/flips per the EXIF orientation tag — BitmapFactory ignores it.
     * (Mirror of MediaProcessing's private helper; that one is outbound-only.)
     */
    private fun bakeExifOrientation(bitmap: Bitmap, data: ByteArray): Bitmap {
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

    // MARK: Disk thumbnail cache

    /** SHA-256 of the memory-cache key, so disk and memory key identically. */
    private fun diskFile(url: String, side: Int): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$url#$side".toByteArray(Charsets.UTF_8))
        return File(diskCacheDirectory, digest.joinToString("") { "%02x".format(it) })
    }

    /**
     * Reads + decodes a stored thumbnail off-main. Touches the modification
     * date (for LRU trimming) only when >12h stale — a write per read is pure
     * IO overhead and LRU doesn't need sub-day resolution.
     */
    private suspend fun readDiskThumbnail(
        file: File,
        maxPixelSize: Float,
        persistTo: File? = null,
    ): Bitmap? {
        val data = withContext(Dispatchers.IO) {
            val bytes = runCatching { file.takeIf { it.exists() }?.readBytes() }.getOrNull()
            if (bytes != null) {
                val now = System.currentTimeMillis()
                if (now - file.lastModified() > 12 * 3600 * 1000L) file.setLastModified(now)
            }
            bytes
        } ?: return null
        return decodeThumbnail(data, maxPixelSize, persistTo)
    }

    /** JPEG at 0.8; PNG when the bitmap has alpha, which JPEG would flatten. */
    private fun encodeForDisk(bitmap: Bitmap): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val ok = if (bitmap.hasAlpha()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
        }
        return if (ok && output.size() > 0) output.toByteArray() else null
    }

    private fun writeDiskThumbnail(data: ByteArray, file: File) {
        // Atomic tmp+rename (the iOS .atomic write). cacheDir sits in
        // credential-encrypted storage, so the iOS file-protection flag has no
        // analogue; backups already exclude the cache directory.
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    // MARK: Storage settings

    /** Total bytes of this account's thumbnail cache, summed off-main. */
    suspend fun totalDiskCacheSize(): Long = withContext(Dispatchers.IO) {
        diskCacheDirectory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Wipes both cache tiers (memory + on-disk), then re-prepares the
     * directory so subsequent writes still land.
     */
    fun clearCache() {
        purgeInMemoryCaches()
        diskWritesSinceTrim = 0
        scope.launch(Dispatchers.IO) {
            diskCacheDirectory.listFiles()?.forEach { runCatching { it.delete() } }
            prepareDiskCache(diskCacheDirectory)
        }
    }

    /**
     * Server-side thumbnail for inline display, falling back to full content
     * (no server thumbnailing for encrypted media). [pixelSize] is in pixels.
     */
    suspend fun thumbnail(box: MediaSourceBox, pixelSize: Float): Bitmap? =
        withContext(Dispatchers.Main.immediate) {
            val side = roundedSide(pixelSize)
            val key = cacheKey(box.url, side)
            cache.get(key)?.let { return@withContext it }
            // Concurrent callers share the fetch; a dedup hit just takes the
            // image (only the originator's didPersist drives the trim counter).
            inFlight[key]?.let { return@withContext it.await().first }

            val encrypted = isEncrypted(box)
            val file = diskFile(box.url, side)
            // A larger cached size can be decoded down from disk instead of
            // hitting the network. Smallest-first: cheapest decode >= side.
            val largerFiles = (cachedSizes[box.url] ?: emptyList())
                .filter { it > side }.sorted().map { diskFile(box.url, it) }
            // `didPersist` marks paths that wrote a new disk file, so the trim
            // counter only counts real growth.
            // IO dispatcher: the FFI fetch does real work in the calling
            // context; on Main, a burst of avatar thumbnails ANR'd the app.
            // Bookkeeping (maps/cache) stays Main-confined via the wrapper and
            // the completion handler below.
            val task = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                // Disk before network.
                readDiskThumbnail(file, pixelSize)?.let { return@async it to false }
                for (larger in largerFiles) {
                    readDiskThumbnail(larger, pixelSize, persistTo = file)?.let {
                        return@async it to true
                    }
                }
                // Encrypted sources can't be server-thumbnailed (asking hangs);
                // download + decrypt directly. The url-keyed in-flight table
                // lets concurrent sizes share one full-content download.
                val data: ByteArray? = if (encrypted) {
                    fullContent(box)
                } else {
                    runCatching {
                        client.getMediaThumbnail(box.source, side.toULong(), side.toULong())
                    }.getOrNull() ?: fullContent(box)
                }
                if (data == null) return@async null to false
                val decoded = decodeThumbnail(data, pixelSize, persistTo = file)
                decoded to (decoded != null)
            }
            inFlight[key] = task
            // Bookkeeping rides the task's completion (on the owning scope), so
            // a cancelled caller still lands the cache entry — the iOS
            // non-throwing `await task.value` semantics.
            task.invokeOnCompletion { cause ->
                scope.launch {
                    inFlight.remove(key)
                    if (cause != null) return@launch
                    val (image, didPersist) = task.getCompleted()
                    if (image != null) {
                        cache.put(key, image)
                        val sizes = cachedSizes.getOrPut(box.url) { mutableListOf() }
                        if (side !in sizes) sizes.add(side)
                    }
                    if (didPersist) {
                        diskWritesSinceTrim += 1
                        if (diskWritesSinceTrim >= DISK_TRIM_INTERVAL) {
                            diskWritesSinceTrim = 0
                            launch(Dispatchers.IO) { prepareDiskCache(diskCacheDirectory) }
                        }
                    }
                }
            }
            task.await().first
        }

    /**
     * Bulk-loads disk thumbnails into memory before the sidebar's first paint,
     * so [cachedThumbnail] hits on frame one instead of avatars popping in.
     */
    suspend fun prewarmThumbnails(mxcUrls: List<String>, pixelSize: Float) =
        withContext(Dispatchers.Main.immediate) {
            val side = roundedSide(pixelSize)
            val missing = mxcUrls.filter { cache.get(cacheKey(it, side)) == null }
            if (missing.isEmpty()) return@withContext
            val loaded = missing.map { url ->
                scope.async(Dispatchers.IO) { url to readDiskThumbnail(diskFile(url, side), pixelSize) }
            }.awaitAll()
            for ((url, image) in loaded) {
                if (image == null) continue
                cache.put(cacheKey(url, side), image)
                val sizes = cachedSizes.getOrPut(url) { mutableListOf() }
                if (side !in sizes) sizes.add(side)
            }
        }

    /**
     * Synchronous in-memory hit for seeding views before the async fetch
     * lands; falls back to a same-URL entry at another size. Kicks off no
     * work. Main thread only (call from composition).
     */
    fun cachedThumbnail(source: MediaSourceBox, pixelSize: Float): Bitmap? =
        cachedImage(source.url, pixelSize)

    /** URL-keyed sibling of [cachedThumbnail] (any cached size). Kicks off no work. */
    fun cachedImage(mxcUrl: String, pixelSize: Float): Bitmap? {
        val side = roundedSide(pixelSize)
        cache.get(cacheKey(mxcUrl, side))?.let { return it }
        for (other in cachedSizes[mxcUrl] ?: emptyList()) {
            if (other == side) continue
            cache.get(cacheKey(mxcUrl, other))?.let { return it }
        }
        return null
    }

    /**
     * Full-resolution content (e.g. opening an image externally), deduplicated
     * per URL in flight. Also the download step for encrypted thumbnails.
     */
    suspend fun fullContent(box: MediaSourceBox): ByteArray? =
        withContext(Dispatchers.Main.immediate) {
            inFlightContent[box.url]?.let { return@withContext it.await() }
            // IO: full downloads (and encrypted-media decryption) are far too
            // heavy for the main thread — see thumbnail() above.
            val task = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                runCatching { client.getMediaContent(box.source) }.getOrNull()
            }
            inFlightContent[box.url] = task
            task.invokeOnCompletion { scope.launch { inFlightContent.remove(box.url) } }
            task.await()
        }

    /** Avatar by `mxc://` URL (room avatars, sender profiles, members). */
    suspend fun avatar(mxcUrl: String, pixelSize: Float): Bitmap? =
        withContext(Dispatchers.Main.immediate) {
            cache.get(cacheKey(mxcUrl, roundedSide(pixelSize)))?.let { return@withContext it }
            val source = runCatching { MediaSource.fromUrl(mxcUrl) }.getOrNull()
                ?: return@withContext null
            thumbnail(MediaSourceBox(source), pixelSize)
        }

    companion object {
        private const val DISK_TRIM_INTERVAL = 200

        /** cacheDir/thumbnails/<account>/ of already-downsampled bitmaps. */
        fun thumbnailCacheDirectory(context: Context, userId: String): File =
            File(context.cacheDir, "thumbnails/${filesystemSafe(userId)}")

        /**
         * Deletes an account's disk thumbnails wholesale (logout hygiene).
         * Does file IO; call off-main.
         */
        fun removeDiskCache(context: Context, userId: String) {
            runCatching { thumbnailCacheDirectory(context, userId).deleteRecursively() }
        }

        /**
         * Map filesystem-unfriendly characters (`@`, `:`, …) to `_` so a user
         * ID can name a directory.
         */
        private fun filesystemSafe(name: String): String =
            name.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
                .joinToString("")

        /**
         * One-time init, off-main: create the directory and trim to ~100 MB by
         * deleting least-recently-read files. (cacheDir is already excluded
         * from backups, unlike the iOS Caches directory.)
         */
        private fun prepareDiskCache(directory: File) {
            runCatching { directory.mkdirs() }
            val capBytes = 100L * 1024 * 1024
            val files = directory.listFiles() ?: return
            var entries = files.map { Triple(it, it.lastModified(), it.length()) }
            var total = entries.sumOf { it.third }
            if (total <= capBytes) return
            entries = entries.sortedBy { it.second }
            for ((file, _, size) in entries) {
                if (total <= capBytes) break
                runCatching { file.delete() }
                total -= size
            }
        }
    }
}

// MARK: - Environment plumbing

/** The active session's media loader (the iOS EnvironmentValues.mediaLoader). */
val LocalMediaLoader = staticCompositionLocalOf<MediaLoader?> { null }
