package com.riiiiiiiley.discourse.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** A newer GitHub release than the installed build. */
data class UpdateInfo(
    /** Release tag, normalized without a leading "v". */
    val versionName: String,
    /** Release notes (markdown body), or the release name. */
    val notes: String,
    /** Direct download URL of the release's .apk asset. */
    val apkUrl: String,
)

/**
 * Self-update against the app's own GitHub releases (this build is sideloaded,
 * not from a store). Checks the latest release, and — on the user's confirmation
 * — downloads the APK asset and hands it to the system package installer.
 */
object UpdateChecker {
    private const val RELEASES_URL =
        "https://api.github.com/repos/FulltimeFeline/DiscourseAndroid/releases/latest"

    /**
     * The latest release if it is newer than [currentVersion] and ships an APK
     * asset; null when up to date, offline, or the release has no APK.
     */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = runCatching {
            val conn = (URI(RELEASES_URL).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
        }.getOrNull() ?: return@withContext null

        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val tag = obj.optString("tag_name").removePrefix("v").removePrefix("V").trim()
        if (tag.isBlank() || !isNewer(tag, currentVersion)) return@withContext null

        val assets = obj.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val url = assets.getJSONObject(i).optString("browser_download_url")
            if (url.endsWith(".apk", ignoreCase = true)) {
                apkUrl = url
                break
            }
        }
        val url = apkUrl ?: return@withContext null
        val notes = obj.optString("body").ifBlank { obj.optString("name") }
        UpdateInfo(versionName = tag, notes = notes, apkUrl = url)
    }

    /**
     * Downloads the release APK to the cache, reporting fractional progress
     * (0..1). Returns the file, or null on failure.
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "discourse-${info.versionName}.apk")
        runCatching {
            val conn = (URI(info.apkUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        downloaded += n
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            file
        }.getOrNull()
    }

    /** Whether the user has granted "install unknown apps" for this app. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Sends the user to the system "install unknown apps" screen for this app. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Launches the system package installer on the downloaded APK. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Semver-ish compare of dotted/hyphenated numeric parts. */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = parts(candidate)
        val cur = parts(current)
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(v: String): List<Int> =
        v.split('.', '-', '+').mapNotNull { seg -> seg.takeWhile(Char::isDigit).toIntOrNull() }
}
