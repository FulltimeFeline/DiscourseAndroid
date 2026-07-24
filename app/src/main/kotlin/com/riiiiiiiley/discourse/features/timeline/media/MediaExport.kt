package com.riiiiiiiley.discourse.features.timeline.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Export paths for downloaded media: the system share sheet and save-to-
 * gallery. iOS gets both for free from QuickLook; here they back the
 * message context menu ("Share Image… / Save Image") and the viewer toolbars.
 */
object MediaExport {

    /** Content type from magic bytes first (filenames are user input), then extension. */
    fun mimeType(filename: String, data: ByteArray): String {
        sniffedMime(data)?.let { return it }
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    private fun sniffedMime(data: ByteArray): String? {
        fun startsWith(vararg bytes: Int, offset: Int = 0): Boolean {
            if (data.size < offset + bytes.size) return false
            return bytes.withIndex().all { (i, b) -> data[offset + i].toInt() and 0xFF == b }
        }
        return when {
            startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            startsWith(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            data.size > 12 && startsWith(0x52, 0x49, 0x46, 0x46) &&
                startsWith(0x57, 0x45, 0x42, 0x50, offset = 8) -> "image/webp"
            data.size > 11 && startsWith(0x66, 0x74, 0x79, 0x70, offset = 4) -> "video/mp4"
            else -> null
        }
    }

    /**
     * Writes the bytes to the shared cache directory and returns a
     * FileProvider URI other apps can read (iOS `temporaryFile(for:)`).
     */
    suspend fun temporaryFileUri(context: Context, data: ByteArray, filename: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "shared").apply { mkdirs() }
                val safeName = filename.ifEmpty { "media" }.replace('/', '_')
                val file = File(directory, safeName)
                file.writeBytes(data)
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }

    /** Temp-file → system share sheet, the ACTION_SEND analogue of iOS ShareLink. */
    suspend fun share(context: Context, data: ByteArray, filename: String) {
        val uri = temporaryFileUri(context, data, filename) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(filename, data)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }

    /**
     * Saves to the device gallery via MediaStore (scoped storage, no
     * permission needed on API 29+) — UIImageWriteToSavedPhotosAlbum parity.
     * Returns whether the insert succeeded.
     */
    suspend fun saveToGallery(context: Context, data: ByteArray, filename: String): Boolean =
        withContext(Dispatchers.IO) {
            val mime = mimeType(filename, data)
            val isVideo = mime.startsWith("video/")
            val collection =
                if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    filename.ifEmpty { if (isVideo) "video" else "image" })
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            runCatching {
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(data) } ?: return@runCatching false
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }.getOrDefault(false)
        }
}
