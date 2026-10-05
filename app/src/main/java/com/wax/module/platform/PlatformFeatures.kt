package com.wax.module.platform

/**
 * Stable feature ids for everything the post-2.0 roadmap adds.
 *
 * Ids live in one object so the registry, the kill switch, diagnostics and the docs cannot
 * disagree about a name. They are part of the stored contract — a kill-switch entry and an
 * audit-log line reference a feature by id — so the rule is the same as for resolver ids:
 * they may be added to, never renamed in place.
 */
object PlatformFeatures {
    // --- runtime safety (T80-T85) ------------------------------------------------------
    const val DIAGNOSTICS = "platform.diagnostics"
    const val COMPATIBILITY = "platform.compat"
    const val SETTINGS = "platform.settings"
    const val RECOVERY = "platform.recovery"
    const val KILL_SWITCH = "platform.kill_switch"
    const val SAFE_MODE = "platform.safe_mode"
    const val CANARY = "platform.canary"
    const val COMPATIBILITY_SUMMARY = "platform.compat_summary"

    // --- privacy (T76-T79) -------------------------------------------------------------
    const val PRIVACY_PROFILES = "privacy.profiles"
    const val PRIVACY_CONTACT_OVERRIDES = "privacy.overrides.contacts"
    const val PRIVACY_GROUP_OVERRIDES = "privacy.overrides.groups"
    const val PRIVACY_SCHEDULE = "privacy.schedule"

    // --- message history and scheduling (T86-T96) --------------------------------------
    const val MESSAGE_EDIT_HISTORY = "history.edits"
    const val DELETED_MESSAGE_TIMELINE = "history.deleted"
    const val MESSAGE_TIMELINE = "history.timeline"
    const val MESSAGE_NOTES = "history.notes"
    const val BOOKMARK_COLLECTIONS = "history.bookmarks"
    const val SCHEDULED_MESSAGES = "scheduler.messages"
    const val RECURRING_MESSAGES = "scheduler.recurring"
    const val UNDO_SEND = "scheduler.undo_send"
    const val REPLY_TEMPLATES = "scheduler.templates"
    const val CONTEXT_ACTIONS = "history.context_actions"

    // --- automation (T97-T105) ---------------------------------------------------------
    const val RULES_ENGINE = "automation.rules"
    const val RULE_SIMULATOR = "automation.simulator"
    const val RULE_AUDIT = "automation.audit"
    const val TASKER = "automation.tasker"

    // --- intelligence (T106-T115) ------------------------------------------------------
    const val TRANSLATION = "intelligence.translation"
    const val TRANSCRIPTION = "intelligence.transcription"
    const val CONVERSATION_SUMMARY = "intelligence.summary"

    // --- media (T116-T124) -------------------------------------------------------------
    const val MEDIA_CENTER = "media.center"
    const val DOWNLOAD_MANAGER = "media.downloads"
    const val MEDIA_QUALITY = "media.quality"
    const val MEDIA_DUPLICATES = "media.duplicates"
    const val STATUS_ARCHIVE = "media.status_archive"
    const val MEDIA_CLEANUP = "media.cleanup"

    // --- theme and UI (T125-T134) ------------------------------------------------------
    const val THEME_ENGINE = "theme.engine"
    const val THEME_PACKAGES = "theme.packages"
    const val TYPOGRAPHY = "theme.typography"
    const val ACCESSIBILITY = "theme.accessibility"

    // --- notifications and calls (T135-T143) -------------------------------------------
    const val NOTIFICATION_PROFILES = "notifications.profiles"
    const val NOTIFICATION_ACTIONS = "notifications.actions"
    const val OTP_DETECTOR = "notifications.otp"
    const val QUIET_HOURS = "notifications.quiet_hours"
    const val CALL_RULES = "notifications.calls"

    // --- storage (T144-T151) -----------------------------------------------------------
    const val STORAGE_DASHBOARD = "storage.dashboard"
    const val SMART_CLEANUP = "storage.cleanup"
    const val FILE_DUPLICATES = "storage.duplicates"
    const val PRIVATE_VAULT = "storage.vault"
    const val BACKUP_V3 = "storage.backup"

    // --- multi-package and accounts (T152-T160) ----------------------------------------
    const val PACKAGE_PROFILES = "multi.packages"
    const val MULTI_ACCOUNT = "multi.accounts"

    /**
     * The only features Safe Mode is allowed to load.
     *
     * Safe Mode exists to recover from an incompatible WhatsApp update, so it must load
     * *less* than normal startup, not the same set with a flag flipped. Everything else is
     * deferred until the user leaves Safe Mode.
     */
    val SAFE_MODE_RECOVERY: Set<String> =
        setOf(
            DIAGNOSTICS,
            COMPATIBILITY,
            SETTINGS,
            RECOVERY,
            KILL_SWITCH,
            SAFE_MODE,
        )
}
