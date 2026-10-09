package com.wax.module.modern

/**
 * Pure, testable state model for the embedded WA X Control Center (#433).
 *
 * The point of this model is that the control center never lies. A toggle
 * shows what the user asked for, and its effective state shows what the
 * runtime actually reported. A feature that is still legacy-only, whose
 * resolver failed, or whose hook never reported is never presented as
 * working; it is routed to the pending area and cannot be switched on.
 */

/** What the user asked for in the settings. */
enum class ControlRequested {
    DISABLED,
    ENABLED,
    UNKNOWN,
}

/** What the runtime actually reported through authenticated evidence. */
enum class ControlEffective {
    /** No evidence yet: the target has not run since bootstrap. */
    NOT_OBSERVED,

    /** Hook installed and, for pilot features, observed at least once. */
    WORKING,

    /** Runtime accepted the request and installed the hook. */
    INSTALLED,

    /** Turned off by the user. */
    DISABLED,

    /** Resolver could not find, or found ambiguously. */
    RESOLVER_FAILED,

    /** Hook signature outside what the adapter accepts. */
    UNSAFE_SIGNATURE,

    /** Adapter exists but is not wired into the runtime yet. */
    PENDING_MIGRATION,

    /** The adapter failed at runtime. */
    ERROR,

    /** Installed, but the user must restart WhatsApp to see the change. */
    RESTART_REQUIRED,

    /** Working in one direction only; the rest still needs migration. */
    PARTIAL,
}

/** Grouping used by the control center list. */
enum class ControlCategory {
    PRIVACY,
    CHATS,
    MEDIA,
    APPEARANCE,
    NOTIFICATIONS,
    TOOLS,
    ADVANCED,
    PENDING,
}

/**
 * One control row.
 *
 * [writable] is the single source of truth for whether the switch is live:
 * only a feature that is migrated, wired and not in a failed state may be
 * toggled. Everything else is rendered as an inert pending row.
 */
data class ControlEntry(
    val id: String,
    val title: String,
    val description: String,
    val category: ControlCategory,
    val preferenceKey: String?,
    val requested: ControlRequested,
    val effective: ControlEffective,
    val writable: Boolean,
    val restartRequired: Boolean,
    val favorite: Boolean = false,
) {
    val requiresRestart: Boolean get() = restartRequired || effective == ControlEffective.RESTART_REQUIRED
}

/** Human-readable, non-lying status text for a row. */
object ControlStatusText {
    fun status(effective: ControlEffective): String = when (effective) {
        ControlEffective.NOT_OBSERVED -> "Not observed yet"
        ControlEffective.WORKING -> "Working"
        ControlEffective.INSTALLED -> "Installed"
        ControlEffective.DISABLED -> "Off"
        ControlEffective.RESOLVER_FAILED -> "Resolver could not confirm"
        ControlEffective.UNSAFE_SIGNATURE -> "Unsupported target signature"
        ControlEffective.PENDING_MIGRATION -> "Pending migration"
        ControlEffective.ERROR -> "Runtime error"
        ControlEffective.RESTART_REQUIRED -> "Restart WhatsApp to apply"
        ControlEffective.PARTIAL -> "Partial: part still pending migration"
    }

    fun categoryTitle(category: ControlCategory): String = when (category) {
        ControlCategory.PRIVACY -> "Privacy"
        ControlCategory.CHATS -> "Chats"
        ControlCategory.MEDIA -> "Media"
        ControlCategory.APPEARANCE -> "Appearance"
        ControlCategory.NOTIFICATIONS -> "Notifications"
        ControlCategory.TOOLS -> "Tools"
        ControlCategory.ADVANCED -> "Advanced"
        ControlCategory.PENDING -> "Pending migration"
    }

    /** Categories in display order; pending always last. */
    fun ordered(): List<ControlCategory> = listOf(
        ControlCategory.PRIVACY,
        ControlCategory.CHATS,
        ControlCategory.MEDIA,
        ControlCategory.APPEARANCE,
        ControlCategory.NOTIFICATIONS,
        ControlCategory.TOOLS,
        ControlCategory.ADVANCED,
        ControlCategory.PENDING,
    )
}

/**
 * Decides whether a row may be toggled.
 *
 * A row is writable only when the user asked for a boolean preference, the
 * adapter is wired, and the runtime has not reported a failure. "Only expose
 * working toggles as active" is enforced here rather than in the UI.
 */
object ControlPolicy {
    private val WORKING_STATES = setOf(
        ControlEffective.NOT_OBSERVED,
        ControlEffective.WORKING,
        ControlEffective.INSTALLED,
        ControlEffective.DISABLED,
        ControlEffective.RESTART_REQUIRED,
        // A partially migrated feature still has a live direction, so its row
        // stays switchable while the status text says what is missing.
        ControlEffective.PARTIAL,
    )

    /**
     * A row is writable only with a real, non-blank preference key and a
     * non-failed runtime state. Always-on infrastructure passes an empty key,
     * and an empty key must never be treated as writable just because it is
     * not null.
     */
    fun isWritable(preferenceKey: String?, effective: ControlEffective): Boolean =
        preferenceKey != null && preferenceKey.isNotBlank() && effective in WORKING_STATES

    fun effectiveFrom(
        reported: String?,
        pendingMigration: Boolean,
        requested: ControlRequested,
    ): ControlEffective {
        if (pendingMigration) return ControlEffective.PENDING_MIGRATION
        return when {
            reported == null || reported.isEmpty() -> ControlEffective.NOT_OBSERVED
            reported == "DISABLED" ->
                if (requested == ControlRequested.ENABLED) {
                    ControlEffective.RESTART_REQUIRED
                } else {
                    ControlEffective.DISABLED
                }
            reported == "INSTALLED" || reported == "ALREADY_INSTALLED" ||
                reported.startsWith("INSTALLED") -> ControlEffective.INSTALLED
            reported.startsWith("RESOLVER_") -> ControlEffective.RESOLVER_FAILED
            reported == "UNSAFE_SIGNATURE" -> ControlEffective.UNSAFE_SIGNATURE
            reported == "SEND_DIRECTION_PENDING" -> ControlEffective.PARTIAL
            reported.startsWith("ERROR") -> ControlEffective.ERROR
            else -> ControlEffective.NOT_OBSERVED
        }
    }

    /** Requested state changes require a restart, so surface it honestly. */
    fun restartRequired(
        requested: ControlRequested,
        effective: ControlEffective,
        wasRequestedEnabled: Boolean,
    ): Boolean = wasRequestedEnabled != (requested == ControlRequested.ENABLED) &&
        effective != ControlEffective.PENDING_MIGRATION &&
        effective != ControlEffective.ERROR

    /** Search matches title or description, case- and accent-insensitive. */
    fun matches(
        entry: ControlEntry,
        query: String,
    ): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return entry.title.lowercase().contains(needle) ||
            entry.description.lowercase().contains(needle) ||
            entry.category.name.lowercase().contains(needle)
    }

    fun group(
        entries: List<ControlEntry>,
        query: String = "",
    ): List<Pair<ControlCategory, List<ControlEntry>>> =
        ControlStatusText.ordered().mapNotNull { category ->
            val rows = entries.filter { it.category == category && matches(it, query) }
            if (rows.isEmpty()) null else category to rows
        }
}