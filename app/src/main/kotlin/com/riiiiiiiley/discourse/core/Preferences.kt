package com.riiiiiiiley.discourse.core

import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Every user-facing customization option, persisted in SharedPreferences and
 * observed app-wide as a StateFlow. Key-for-key port of the iOS Preferences
 * (same "pref.*" names and defaults), so behavior matches exactly.
 */
class Preferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

    private val resolver = context.applicationContext.contentResolver

    /** Animator scale 0 = the user disabled animations ("Remove animations"). */
    private fun readSystemReduceMotion(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    data class Snapshot(
        // Appearance
        val appearance: AppearanceMode = AppearanceMode.SYSTEM,
        val accentColor: AccentChoice = AccentChoice.APP_DEFAULT,
        val tintedWindow: Boolean = true,
        val messageDensity: MessageDensity = MessageDensity.COMFORTABLE,
        val use24HourTime: Boolean = false,
        val coloredSenderNames: Boolean = true,
        val showAvatarsInTimeline: Boolean = true,
        /** Timeline text scale (0.8…1.4), on top of system font scale. */
        val chatFontScale: Float = 1.0f,
        // Chat behavior
        val jumboEmoji: Boolean = true,
        val animatedEmotes: Boolean = true,
        val showReadReceipts: Boolean = true,
        val showTypingIndicators: Boolean = true,
        /** Minutes within which same-sender messages group under one header. */
        val groupingWindowMinutes: Int = 5,
        val sendOnEnter: Boolean = true,
        val confirmBeforeDeleting: Boolean = false,
        val sendMessageHaptic: Boolean = true,
        /** Shows the "not encrypted" banner above the composer in unencrypted rooms. */
        val warnUnencrypted: Boolean = true,
        // Privacy
        val sendReadReceipts: Boolean = true,
        val sendTypingNotifications: Boolean = true,
        val sharePresence: Boolean = true,
        val stripLocationMetadata: Boolean = true,
        // Media & storage
        val autoDownloadImages: Boolean = true,
        // Notifications
        val notificationPreview: NotificationPreview = NotificationPreview.FULL,
        val notificationSound: Boolean = true,
        // Accessibility
        val alwaysShowTimestamps: Boolean = false,
        val reduceTimelineMotion: Boolean = false,
        val largerTapTargets: Boolean = false,
        // Advanced
        val showEventIds: Boolean = false,
        // OS-level accessibility mirrors, not persisted (re-read from the
        // system): "Remove animations" / animator scale 0 on Android.
        val systemReduceMotion: Boolean = false,
        val systemReduceTransparency: Boolean = false,
    ) {
        /** Combined with the in-app toggle, like iOS `prefs.reduceMotion`. */
        val reduceMotion: Boolean get() = reduceTimelineMotion || systemReduceMotion

        /** System-only on iOS; Android has no user-facing equivalent yet. */
        val reduceTransparency: Boolean get() = systemReduceTransparency
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Snapshot> = _state

    init {
        // Track the OS animation setting live, like the iOS
        // UIAccessibility.reduceMotionStatusDidChangeNotification observer.
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        _state.value = _state.value.copy(systemReduceMotion = readSystemReduceMotion())
                    }
                },
            )
        }
    }

    val value: Snapshot get() = _state.value

    fun update(transform: (Snapshot) -> Snapshot) {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
    }

    fun resetToDefaults() = update { Snapshot() }

    private fun load(): Snapshot {
        val d = Snapshot()
        return Snapshot(
            appearance = enum("pref.appearance", d.appearance),
            accentColor = enum("pref.accentColor", d.accentColor),
            tintedWindow = prefs.getBoolean("pref.tintedWindow", d.tintedWindow),
            messageDensity = enum("pref.messageDensity", d.messageDensity),
            use24HourTime = prefs.getBoolean("pref.use24HourTime", d.use24HourTime),
            coloredSenderNames = prefs.getBoolean("pref.coloredSenderNames", d.coloredSenderNames),
            showAvatarsInTimeline = prefs.getBoolean("pref.showAvatarsInTimeline", d.showAvatarsInTimeline),
            chatFontScale = prefs.getFloat("pref.chatFontScale", d.chatFontScale),
            jumboEmoji = prefs.getBoolean("pref.jumboEmoji", d.jumboEmoji),
            animatedEmotes = prefs.getBoolean("pref.animatedEmotes", d.animatedEmotes),
            showReadReceipts = prefs.getBoolean("pref.showReadReceipts", d.showReadReceipts),
            showTypingIndicators = prefs.getBoolean("pref.showTypingIndicators", d.showTypingIndicators),
            groupingWindowMinutes = prefs.getInt("pref.groupingWindowMinutes", d.groupingWindowMinutes),
            sendOnEnter = prefs.getBoolean("pref.sendOnEnter", d.sendOnEnter),
            confirmBeforeDeleting = prefs.getBoolean("pref.confirmBeforeDeleting", d.confirmBeforeDeleting),
            sendMessageHaptic = prefs.getBoolean("pref.sendMessageHaptic", d.sendMessageHaptic),
            warnUnencrypted = prefs.getBoolean("pref.warnUnencrypted", d.warnUnencrypted),
            sendReadReceipts = prefs.getBoolean("pref.sendReadReceipts", d.sendReadReceipts),
            sendTypingNotifications = prefs.getBoolean("pref.sendTypingNotifications", d.sendTypingNotifications),
            sharePresence = prefs.getBoolean("pref.sharePresence", d.sharePresence),
            stripLocationMetadata = prefs.getBoolean("pref.stripLocationMetadata", d.stripLocationMetadata),
            autoDownloadImages = prefs.getBoolean("pref.autoDownloadImages", d.autoDownloadImages),
            notificationPreview = enum("pref.notificationPreview", d.notificationPreview),
            notificationSound = prefs.getBoolean("pref.notificationSound", d.notificationSound),
            alwaysShowTimestamps = prefs.getBoolean("pref.alwaysShowTimestamps", d.alwaysShowTimestamps),
            reduceTimelineMotion = prefs.getBoolean("pref.reduceTimelineMotion", d.reduceTimelineMotion),
            largerTapTargets = prefs.getBoolean("pref.largerTapTargets", d.largerTapTargets),
            showEventIds = prefs.getBoolean("pref.showEventIds", d.showEventIds),
            systemReduceMotion = readSystemReduceMotion(),
            systemReduceTransparency = false,
        )
    }

    private fun persist(s: Snapshot) = prefs.edit {
        putString("pref.appearance", s.appearance.raw)
        putString("pref.accentColor", s.accentColor.raw)
        putBoolean("pref.tintedWindow", s.tintedWindow)
        putString("pref.messageDensity", s.messageDensity.raw)
        putBoolean("pref.use24HourTime", s.use24HourTime)
        putBoolean("pref.coloredSenderNames", s.coloredSenderNames)
        putBoolean("pref.showAvatarsInTimeline", s.showAvatarsInTimeline)
        putFloat("pref.chatFontScale", s.chatFontScale)
        putBoolean("pref.jumboEmoji", s.jumboEmoji)
        putBoolean("pref.animatedEmotes", s.animatedEmotes)
        putBoolean("pref.showReadReceipts", s.showReadReceipts)
        putBoolean("pref.showTypingIndicators", s.showTypingIndicators)
        putInt("pref.groupingWindowMinutes", s.groupingWindowMinutes)
        putBoolean("pref.sendOnEnter", s.sendOnEnter)
        putBoolean("pref.confirmBeforeDeleting", s.confirmBeforeDeleting)
        putBoolean("pref.sendMessageHaptic", s.sendMessageHaptic)
        putBoolean("pref.warnUnencrypted", s.warnUnencrypted)
        putBoolean("pref.sendReadReceipts", s.sendReadReceipts)
        putBoolean("pref.sendTypingNotifications", s.sendTypingNotifications)
        putBoolean("pref.sharePresence", s.sharePresence)
        putBoolean("pref.stripLocationMetadata", s.stripLocationMetadata)
        putBoolean("pref.autoDownloadImages", s.autoDownloadImages)
        putString("pref.notificationPreview", s.notificationPreview.raw)
        putBoolean("pref.notificationSound", s.notificationSound)
        putBoolean("pref.alwaysShowTimestamps", s.alwaysShowTimestamps)
        putBoolean("pref.reduceTimelineMotion", s.reduceTimelineMotion)
        putBoolean("pref.largerTapTargets", s.largerTapTargets)
        putBoolean("pref.showEventIds", s.showEventIds)
    }

    // MARK: Per-account notifications
    // Stored as a disabled-set (not in the Snapshot: keyed per user), same
    // key + semantics as iOS ("pref.notificationDisabledUserIds").

    fun notificationsEnabled(forUserId: String): Boolean =
        forUserId !in (prefs.getStringSet("pref.notificationDisabledUserIds", emptySet()) ?: emptySet())

    fun setNotificationsEnabled(enabled: Boolean, forUserId: String) {
        // Copy before mutating: SharedPreferences returns its internal set.
        val disabled = (prefs.getStringSet("pref.notificationDisabledUserIds", emptySet()) ?: emptySet())
            .toMutableSet()
        if (enabled) disabled.remove(forUserId) else disabled.add(forUserId)
        prefs.edit { putStringSet("pref.notificationDisabledUserIds", disabled) }
    }

    private inline fun <reified T> enum(key: String, default: T): T
        where T : kotlin.Enum<T>, T : RawValued {
        val raw = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.raw == raw } ?: default
    }
}

interface RawValued { val raw: String }

enum class AppearanceMode(override val raw: String) : RawValued {
    SYSTEM("system"), LIGHT("light"), DARK("dark");
}

enum class MessageDensity(override val raw: String) : RawValued {
    COMFORTABLE("comfortable"), COMPACT("compact");
}

enum class NotificationPreview(override val raw: String) : RawValued {
    FULL("full"), NAME_ONLY("nameOnly"), HIDDEN("hidden");
}

/**
 * Same choices as iOS. On Android, SYSTEM follows Material You's dynamic
 * color (the platform's real OS accent) — resolved at the theme layer since
 * it needs a Context; `color` is null for it and for APP_DEFAULT the theme
 * substitutes AppAccent.
 */
enum class AccentChoice(override val raw: String) : RawValued {
    APP_DEFAULT("default"), SYSTEM("system"),
    BLUE("blue"), INDIGO("indigo"), PURPLE("purple"), PINK("pink"), RED("red"),
    ORANGE("orange"), YELLOW("yellow"), GREEN("green"), TEAL("teal"),
    MINT("mint"), BROWN("brown"), GRAY("gray");

    /** Concrete tint, or null when the theme layer resolves it (default/system). */
    val color: Color?
        get() = when (this) {
            APP_DEFAULT, SYSTEM -> null
            BLUE -> Color(0xFF3B82F6)
            INDIGO -> Color(0xFF6366F1)
            PURPLE -> Color(0xFFA855F7)
            PINK -> Color(0xFFEC4899)
            RED -> Color(0xFFEF4444)
            ORANGE -> Color(0xFFF97316)
            YELLOW -> Color(0xFFEAB308)
            GREEN -> Color(0xFF22C55E)
            TEAL -> Color(0xFF14B8A6)
            MINT -> Color(0xFF2DD4BF)
            BROWN -> Color(0xFFA16207)
            GRAY -> Color(0xFF6B7280)
        }
}
