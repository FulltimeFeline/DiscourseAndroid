package com.riiiiiiiley.discourse.core

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import java.io.File
import java.security.SecureRandom
import android.util.Base64

/**
 * Everything needed to restore a Matrix session after relaunch. Mirrors the
 * FFI `Session` record (not serializable itself) plus our local store secrets.
 * Field-for-field port of the iOS RestorationToken.
 */
@Serializable
data class RestorationToken(
    val session: SessionData,
    val storePassphrase: String,
    val dataPath: String,
    val cachePath: String,
) {
    @Serializable
    data class SessionData(
        val accessToken: String,
        val refreshToken: String? = null,
        val userId: String,
        val deviceId: String,
        val homeserverUrl: String,
        val oauthData: String? = null,
        val slidingSyncVersion: String,
    ) {
        constructor(session: Session) : this(
            accessToken = session.accessToken,
            refreshToken = session.refreshToken,
            userId = session.userId,
            deviceId = session.deviceId,
            homeserverUrl = session.homeserverUrl,
            oauthData = session.oauthData,
            slidingSyncVersion = if (session.slidingSyncVersion == SlidingSyncVersion.NATIVE) "native" else "none",
        )

        val ffiSession: Session
            get() = Session(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = userId,
                deviceId = deviceId,
                homeserverUrl = homeserverUrl,
                oauthData = oauthData,
                slidingSyncVersion = if (slidingSyncVersion == "native") SlidingSyncVersion.NATIVE else SlidingSyncVersion.NONE,
            )
    }
}

/**
 * Persists restoration tokens (one per account) in encrypted preferences
 * (Android's keychain analogue: an AES-encrypted prefs file keyed by the
 * hardware-backed keystore) and owns the per-session store directories.
 */
class SessionStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "discourse-sessions",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plainPrefs by lazy {
        context.getSharedPreferences("discourse", Context.MODE_PRIVATE)
    }

    companion object {
        private const val ACCOUNT = "sessions"
        private const val ACTIVE_USER_KEY = "activeUserId"

        /**
         * Serializes the token array's read-modify-write. OAuth refreshes
         * arrive on Rust threads and can overlap another save; without this a
         * load→mutate→save race drops a freshly-rotated refresh token.
         */
        private val lock = Any()

        fun randomPassphrase(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    fun loadAll(): List<RestorationToken> = synchronized(lock) { loadAllLocked() }

    private fun loadAllLocked(): List<RestorationToken> {
        val raw = securePrefs.getString(ACCOUNT, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<RestorationToken>>(raw) }
            .getOrDefault(emptyList())
    }

    fun saveAll(tokens: List<RestorationToken>) = synchronized(lock) { saveAllLocked(tokens) }

    private fun saveAllLocked(tokens: List<RestorationToken>) {
        securePrefs.edit { putString(ACCOUNT, json.encodeToString(tokens)) }
    }

    /** Read-modify-write the token array under the lock. */
    fun mutate(transform: (List<RestorationToken>) -> List<RestorationToken>) =
        synchronized(lock) { saveAllLocked(transform(loadAllLocked())) }

    fun clearAll() {
        synchronized(lock) { securePrefs.edit { remove(ACCOUNT) } }
        activeUserId = null
    }

    var activeUserId: String?
        get() = plainPrefs.getString(ACTIVE_USER_KEY, null)
        set(value) = plainPrefs.edit { putString(ACTIVE_USER_KEY, value) }

    // MARK: Store directories

    /**
     * Creates (if needed) and returns the store directories for a session.
     * `id` is minted at login (before the user ID is known) and persisted in
     * the restoration token.
     */
    fun makeSessionDirectories(id: String): Pair<String, String> {
        val data = File(context.filesDir, "Sessions/$id").apply { mkdirs() }
        val cache = File(context.cacheDir, "Sessions/$id").apply { mkdirs() }
        return data.absolutePath to cache.absolutePath
    }

    /**
     * Re-resolves a token's store paths against the current container; only
     * the directory name is treated as stable, matching iOS behavior.
     */
    fun currentSessionDirectories(token: RestorationToken): Pair<String, String> =
        makeSessionDirectories(File(token.dataPath).name)

    fun removeSessionDirectories(token: RestorationToken) {
        val (data, cache) = currentSessionDirectories(token)
        File(data).deleteRecursively()
        File(cache).deleteRecursively()
    }
}
