package com.wax.module.notifications

import com.wax.module.platform.ChatKind
import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.jsonStrings
import com.wax.module.platform.string
import com.wax.module.platform.stringList
import java.time.DayOfWeek
import java.time.LocalTime

/** Notification importance, mirroring the platform's concept without depending on it. */
enum class NotificationImportance {
    LOW,
    DEFAULT,
    HIGH,
    URGENT,
}

/** How much a notification reveals (T136). */
enum class NotificationPrivacyLevel {
    /** Sender and content visible. */
    FULL,

    /** Sender visible, content hidden. */
    HIDE_CONTENT,

    /** Sender hidden, content hidden. */
    HIDE_SENDER,

    /** The notification is replaced by a generic one. */
    HIDE_ALL,
    ;

    /** Whether the sender may be shown. */
    val showsSender: Boolean get() = this == FULL || this == HIDE_CONTENT

    /** Whether the message text may be shown. */
    val showsText: Boolean get() = this == FULL
}

/** Privacy controls for one chat's notifications. */
data class NotificationPrivacy(
    val level: NotificationPrivacyLevel = NotificationPrivacyLevel.FULL,
    /** Hide image/video previews even when content is allowed. */
    val hideMediaPreview: Boolean = false,
    /** Hide everything while the device is locked, regardless of [level]. */
    val hideOnLockScreen: Boolean = false,
) {
    /** Whether the sender may be shown. */
    fun showsSender(): Boolean = level.showsSender

    /** Whether the text may be shown. */
    fun showsText(): Boolean = level.showsText

    /** Whether a media preview may be shown. */
    fun showsMediaPreview(): Boolean = level == NotificationPrivacyLevel.FULL && !hideMediaPreview

    /** Whether content may be shown on the lock screen. */
    fun showsOnLockScreen(): Boolean = !hideOnLockScreen && level != NotificationPrivacyLevel.HIDE_ALL
}

/**
 * A quiet-hours window (T139).
 *
 * A window whose end is earlier than its start crosses midnight, and weekday selection
 * applies to the *start* of the window: Friday 22:00–07:00 includes Saturday morning
 * because the quiet period began on Friday. Getting that backwards is the classic quiet-
 * hours bug, so it is defined here once and tested.
 */
data class QuietHours(
    val start: LocalTime,
    val end: LocalTime,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val enabled: Boolean = true,
) {
    /** Whether [time] on [day] falls inside the window. */
    fun isQuietAt(
        time: LocalTime,
        day: DayOfWeek,
    ): Boolean {
        if (!enabled || days.isEmpty()) return false
        return if (start <= end) {
            day in days && time >= start && time < end
        } else {
            (day in days && time >= start) || (day.minus(1) in days && time < end)
        }
    }

    /** One line for the settings summary. */
    fun toDisplayLine(): String = "$start-$end on " + days.joinToString(",") { it.name.take(3) } + if (enabled) "" else " (off)"
}

/** Per-chat notification controls (T135). */
data class NotificationProfile(
    val chatId: String,
    val kind: ChatKind,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val importance: NotificationImportance = NotificationImportance.DEFAULT,
    val privacy: NotificationPrivacy = NotificationPrivacy(),
    val quietHours: QuietHours? = null,
    val customActions: Set<String> = emptySet(),
) {
    /** One line for the profile list; the chat id is not included. */
    fun toDisplayLine(): String = "notifications: ${importance.name.lowercase()}, privacy ${privacy.level.name.lowercase()}"
}

/**
 * Stores notification profiles and the global quiet-hours schedule.
 *
 * Profiles are keyed by kind and chat id, in the same shape the privacy overrides use, so the
 * two features cannot disagree about which chat a stored key refers to. Reading a chat with
 * no stored profile returns a default profile rather than null: every notification path
 * needs *some* settings, and "no profile" should mean "stock behaviour", not "unknown".
 */
class NotificationProfileStore(
    private val store: KeyValueStore,
) {
    /** The profile for a chat, or the default profile. */
    fun profileFor(
        chatId: String,
        kind: ChatKind,
    ): NotificationProfile {
        val text = store.getString(keyFor(chatId, kind)) ?: return defaultProfile(chatId, kind)
        val fields = (MiniJson.parse(text) as? JsonValue.Obj)?.fields ?: return defaultProfile(chatId, kind)
        return decodeProfile(chatId, kind, fields) ?: defaultProfile(chatId, kind)
    }

    /** Stores a profile. */
    fun save(profile: NotificationProfile) {
        store.putString(keyFor(profile.chatId, profile.kind), MiniJson.write(encodeProfile(profile)))
    }

    /** Removes a chat's profile so it follows defaults again. */
    fun remove(
        chatId: String,
        kind: ChatKind,
    ): Boolean {
        val key = keyFor(chatId, kind)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** Every stored profile. */
    fun profiles(): List<NotificationProfile> =
        store
            .keys(KEY_PROFILE)
            .mapNotNull { key ->
                val parsed = decodeKey(key) ?: return@mapNotNull null
                store.getString(key)?.let { text ->
                    (MiniJson.parse(text) as? JsonValue.Obj)?.fields?.let { fields ->
                        decodeProfile(parsed.first, parsed.second, fields)
                    }
                }
            }

    /** The global quiet-hours window, or null. */
    fun globalQuietHours(): QuietHours? {
        val text = store.getString(KEY_GLOBAL_QUIET) ?: return null
        return decodeQuietHours(MiniJson.parse(text))
    }

    /** Sets or clears the global quiet-hours window. */
    fun setGlobalQuietHours(hours: QuietHours?) {
        if (hours == null) {
            store.remove(KEY_GLOBAL_QUIET)
        } else {
            store.putString(KEY_GLOBAL_QUIET, MiniJson.write(encodeQuietHours(hours)))
        }
    }

    /**
     * Whether a chat is in quiet hours now.
     *
     * A chat's own window wins over the global one; the global window is the default, not an
     * additional restriction, so a chat can be excluded from a global night schedule by
     * giving it a disabled window of its own.
     */
    fun isQuietNow(
        chatId: String,
        kind: ChatKind,
        time: LocalTime,
        day: DayOfWeek,
    ): Boolean {
        val profile = profileFor(chatId, kind)
        val window = profile.quietHours ?: globalQuietHours() ?: return false
        return window.isQuietAt(time, day)
    }

    /** Drops every profile and the global window. Used by tests and factory reset. */
    fun clear() {
        store.keys(KEY_PROFILE).forEach { store.remove(it) }
        store.remove(KEY_GLOBAL_QUIET)
    }

    private fun defaultProfile(
        chatId: String,
        kind: ChatKind,
    ): NotificationProfile = NotificationProfile(chatId, kind)

    private fun keyFor(
        chatId: String,
        kind: ChatKind,
    ): String = "$KEY_PROFILE${kind.name}.$chatId"

    /** Splits a profile key into (chatId, kind), or null when malformed. */
    private fun decodeKey(key: String): Pair<String, ChatKind>? {
        val remainder = key.removePrefix(KEY_PROFILE)
        val separator = remainder.indexOf('.')
        if (separator <= 0) return null
        val kind = ChatKind.entries.firstOrNull { it.name == remainder.substring(0, separator) } ?: return null
        val chatId = remainder.substring(separator + 1)
        return if (chatId.isBlank()) null else chatId to kind
    }

    private fun encodeProfile(profile: NotificationProfile): JsonValue.Obj =
        jsonObject(
            "sound" to jsonBoolean(profile.soundEnabled),
            "vibration" to jsonBoolean(profile.vibrationEnabled),
            "importance" to jsonString(profile.importance.name),
            "privacyLevel" to jsonString(profile.privacy.level.name),
            "hideMediaPreview" to jsonBoolean(profile.privacy.hideMediaPreview),
            "hideOnLockScreen" to jsonBoolean(profile.privacy.hideOnLockScreen),
            "quiet" to profile.quietHours?.let { encodeQuietHours(it) },
            "actions" to jsonStrings(profile.customActions),
        )

    private fun decodeProfile(
        chatId: String,
        kind: ChatKind,
        fields: Map<String, JsonValue>,
    ): NotificationProfile? {
        val privacyLevel =
            NotificationPrivacyLevel.entries.firstOrNull { it.name == fields.string("privacyLevel") }
                ?: NotificationPrivacyLevel.FULL
        val importance =
            NotificationImportance.entries.firstOrNull { it.name == fields.string("importance") }
                ?: NotificationImportance.DEFAULT
        return NotificationProfile(
            chatId = chatId,
            kind = kind,
            soundEnabled = fields.boolean("sound") ?: true,
            vibrationEnabled = fields.boolean("vibration") ?: true,
            importance = importance,
            privacy =
                NotificationPrivacy(
                    level = privacyLevel,
                    hideMediaPreview = fields.boolean("hideMediaPreview") ?: false,
                    hideOnLockScreen = fields.boolean("hideOnLockScreen") ?: false,
                ),
            quietHours = decodeQuietHours(fields["quiet"]),
            customActions = fields.stringList("actions").toSet(),
        )
    }

    private fun encodeQuietHours(hours: QuietHours): JsonValue.Obj =
        jsonObject(
            "start" to jsonString(hours.start.toString()),
            "end" to jsonString(hours.end.toString()),
            "days" to jsonStrings(hours.days.map { it.name }),
            "enabled" to jsonBoolean(hours.enabled),
        )

    private fun decodeQuietHours(value: JsonValue?): QuietHours? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val start = fields.string("start")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val end = fields.string("end")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        val days =
            fields
                .stringList("days")
                .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                .toSet()
        return QuietHours(
            start = start,
            end = end,
            days = days.ifEmpty { DayOfWeek.entries.toSet() },
            enabled = fields.boolean("enabled") ?: true,
        )
    }

    companion object {
        /** Storage key prefix for per-chat profiles. */
        const val KEY_PROFILE: String = "wae.notifications.profile."

        /** Storage key for the global quiet-hours window. */
        const val KEY_GLOBAL_QUIET: String = "wae.notifications.quiet.global"
    }
}

/** One custom notification action (T137). */
data class NotificationAction(
    val id: String,
    val label: String,
    val requiresText: Boolean = false,
    val requiresMedia: Boolean = false,
    val requiresOtp: Boolean = false,
) {
    /** Whether this action applies to a notification with the given content. */
    fun appliesTo(
        hasText: Boolean,
        hasMedia: Boolean,
        hasOtp: Boolean,
    ): Boolean = (!requiresText || hasText) && (!requiresMedia || hasMedia) && (!requiresOtp || hasOtp)
}

/** The custom notification actions T137 lists. */
object NotificationActions {
    const val TRANSLATE = "notification.translate"
    const val COPY_OTP = "notification.copy_otp"
    const val MARK_LATER = "notification.mark_later"
    const val SAVE_MEDIA = "notification.save_media"
    const val QUICK_REPLY = "notification.quick_reply"

    /** Every action, in display order. */
    val all: List<NotificationAction> =
        listOf(
            NotificationAction(TRANSLATE, "Translate", requiresText = true),
            NotificationAction(COPY_OTP, "Copy code", requiresText = true, requiresOtp = true),
            NotificationAction(MARK_LATER, "Mark later", requiresText = true),
            NotificationAction(SAVE_MEDIA, "Save media", requiresMedia = true),
            NotificationAction(QUICK_REPLY, "Quick reply", requiresText = true),
        )

    /** The actions that apply to a notification. */
    fun available(
        hasText: Boolean,
        hasMedia: Boolean,
        hasOtp: Boolean,
    ): List<NotificationAction> = all.filter { it.appliesTo(hasText, hasMedia, hasOtp) }

    /** Looks up an action by id. */
    fun byId(id: String): NotificationAction? = all.firstOrNull { it.id == id }
}

/**
 * Detects verification codes locally (T138).
 *
 * The detector is intentionally conservative: a run of 4–8 digits only counts as a code when
 * a verification keyword appears immediately before it. Matching every digit run would
 * mislabel phone numbers, prices and dates as codes, and the action's whole value is that it
 * is right when it appears. Arabic-Indic and Persian digits are normalised so the feature
 * works on Arabic messages, and the returned code is always ASCII digits.
 */
object OtpDetector {
    private val CODE = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    private val KEYWORDS =
        listOf(
            "code",
            "otp",
            "one-time",
            "one time",
            "verification",
            "verify",
            "pin",
            "password",
            "passcode",
            "رمز",
            "كود",
            "التحقق",
            "تحقق",
            "تأكيد",
            "تاكيد",
        )

    /** The verification code in [text], or null when there is none. */
    fun findCode(text: String): String? {
        if (text.isBlank()) return null
        val normalised = normaliseDigits(text)
        return CODE
            .findAll(normalised)
            .firstOrNull { match -> hasKeywordBefore(normalised, match.range.first) }
            ?.groupValues
            ?.get(1)
    }

    /** Every code-like match that has a keyword in front of it. */
    fun findCodes(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalised = normaliseDigits(text)
        return CODE
            .findAll(normalised)
            .filter { hasKeywordBefore(normalised, it.range.first) }
            .map { it.groupValues[1] }
            .toList()
    }

    /** Whether [text] contains a verification code. */
    fun containsCode(text: String): Boolean = findCode(text) != null

    /** Replaces Arabic-Indic and Persian digits with ASCII digits. */
    fun normaliseDigits(text: String): String =
        buildString(text.length) {
            text.forEach { char -> append(if (char in ARABIC_DIGITS) ARABIC_DIGITS[char] ?: char else char) }
        }

    /** Whether any keyword appears in the 48 characters before [index]. */
    private fun hasKeywordBefore(
        text: String,
        index: Int,
    ): Boolean {
        val window = text.substring((index - KEYWORD_WINDOW).coerceAtLeast(0), index).lowercase()
        return KEYWORDS.any { window.contains(it) }
    }

    private const val KEYWORD_WINDOW = 48

    private val ARABIC_DIGITS: Map<Char, Char> =
        buildMap {
            val zero = '\u0660' // ٠
            for (i in 0..9) put(zero + i, '0' + i)
            val persianZero = '\u06F0' // ۰
            for (i in 0..9) put(persianZero + i, '0' + i)
        }
}
