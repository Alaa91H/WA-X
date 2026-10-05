package com.wax.module.history

/**
 * What a message offers, used to decide which long-press actions apply.
 *
 * Capabilities are computed by the caller (which knows the message) and filtered here
 * (which knows the catalog). Keeping the two apart means the decision logic is testable
 * without a message: "an OTP action appears exactly when text contains a code" is a unit
 * test, not a device test.
 */
enum class ContextCapability {
    /** The message carries text. */
    TEXT,

    /** The message carries media the user can save. */
    MEDIA,

    /** The text contains at least one link. */
    LINK,

    /** The text contains a verification code. */
    OTP,

    /** The module has local history for this sender. */
    SENDER_HISTORY,
}

/** Where a context action belongs in the menu. */
enum class ContextActionCategory {
    TRANSLATE,
    COPY,
    NOTE,
    BOOKMARK,
    SCHEDULE,
    SEARCH,
    SAVE,
}

/**
 * One long-press action.
 *
 * @param requiredCapabilities every capability that must be present; an empty set means the
 *   action is always available
 */
data class ContextAction(
    val id: String,
    val label: String,
    val category: ContextActionCategory,
    val requiredCapabilities: Set<ContextCapability>,
) {
    /** Whether this action applies to a message with [capabilities]. */
    fun appliesTo(capabilities: Set<ContextCapability>): Boolean = capabilities.containsAll(requiredCapabilities)
}

/**
 * The T95 smart context actions.
 *
 * The catalog is a value, not a wiring: the Android menu asks for the actions that apply and
 * renders them in the returned order. That order is fixed by category so the menu cannot
 * shuffle between builds — muscle memory matters in a long-press menu.
 */
object ContextActions {
    const val TRANSLATE = "context.translate"
    const val COPY_OTP = "context.copy_otp"
    const val ADD_NOTE = "context.add_note"
    const val BOOKMARK = "context.bookmark"
    const val SCHEDULE_REPLY = "context.schedule_reply"
    const val SEARCH_SENDER = "context.search_sender"
    const val SAVE_MEDIA = "context.save_media"

    /** Every action, in menu order. */
    val all: List<ContextAction> =
        listOf(
            ContextAction(TRANSLATE, "Translate", ContextActionCategory.TRANSLATE, setOf(ContextCapability.TEXT)),
            ContextAction(COPY_OTP, "Copy code", ContextActionCategory.COPY, setOf(ContextCapability.OTP)),
            ContextAction(ADD_NOTE, "Add note", ContextActionCategory.NOTE, setOf(ContextCapability.TEXT)),
            ContextAction(BOOKMARK, "Bookmark", ContextActionCategory.BOOKMARK, setOf(ContextCapability.TEXT)),
            ContextAction(
                SCHEDULE_REPLY,
                "Schedule reply",
                ContextActionCategory.SCHEDULE,
                setOf(ContextCapability.TEXT),
            ),
            ContextAction(
                SEARCH_SENDER,
                "Search this sender",
                ContextActionCategory.SEARCH,
                setOf(ContextCapability.SENDER_HISTORY),
            ),
            ContextAction(SAVE_MEDIA, "Save media", ContextActionCategory.SAVE, setOf(ContextCapability.MEDIA)),
        )

    /** The actions that apply to a message with [capabilities], in menu order. */
    fun forCapabilities(capabilities: Set<ContextCapability>): List<ContextAction> = all.filter { it.appliesTo(capabilities) }

    /** Looks up an action by id. */
    fun byId(id: String): ContextAction? = all.firstOrNull { it.id == id }

    /** Human-readable labels for [capabilities], used by diagnostics. */
    fun describeCapabilities(capabilities: Set<ContextCapability>): String =
        if (capabilities.isEmpty()) "none" else capabilities.joinToString(", ") { it.name.lowercase() }
}
