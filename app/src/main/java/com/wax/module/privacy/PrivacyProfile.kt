package com.wax.module.privacy

/**
 * One privacy control a profile can set.
 *
 * The list is the intersection of what the existing module toggles and what the roadmap's
 * profiles promise to control. Keeping it an enum means a profile can never reference a
 * preference that no longer exists: the compiler and the tests know the full set.
 */
enum class PrivacyField {
    /** Read receipts (blue ticks). */
    READ_RECEIPTS,

    /** Typing indicator. */
    TYPING,

    /** Recording (microphone) indicator. */
    RECORDING,

    /** Online presence. */
    ONLINE,

    /** Last seen. */
    LAST_SEEN,

    /** Status-viewed receipts. */
    STATUS_VIEWS,

    /** What happens to incoming calls. */
    CALLS,

    /** How notifications for this account are rendered. */
    NOTIFICATIONS,
}

/** A visibility choice for one field. There is deliberately no third "default" state. */
enum class Visibility {
    /** Behave like stock WhatsApp. */
    SHOW,

    /** Suppress the signal. */
    HIDE,
    ;

    /** The opposite choice, used by profile inversion and tests. */
    fun inverted(): Visibility = if (this == SHOW) HIDE else SHOW
}

/** What happens to an incoming call under a profile. */
enum class CallBehavior {
    /** Ring normally. */
    ALLOW,

    /** Ring silently. */
    SILENCE,

    /** Reject outright. */
    REJECT,
}

/** How much of a notification a profile exposes. */
enum class NotificationPrivacy {
    /** Sender and content visible. */
    FULL,

    /** Sender visible, content hidden. */
    HIDE_CONTENT,

    /** Sender hidden, content hidden. */
    HIDE_SENDER,

    /** Nothing shown; the notification is replaced by a generic one. */
    HIDE_ALL,
}

/**
 * A reusable bundle of privacy choices.
 *
 * Profiles are values, not preference handles: applying a profile is a decision made by
 * [PrivacyProfileStore], which can validate the whole bundle before anything is written.
 * That is what makes "switching applies compatible settings atomically" implementable —
 * a profile that fails validation never reaches the write step at all.
 *
 * [visibility] maps only the fields the profile talks about; a field that is absent falls
 * back to [Visibility.SHOW], which keeps old stored profiles forward compatible when a new
 * field is added.
 */
data class PrivacyProfile(
    val id: String,
    val name: String,
    val builtIn: Boolean,
    val visibility: Map<PrivacyField, Visibility>,
    val callBehavior: CallBehavior,
    val notificationPrivacy: NotificationPrivacy,
) {
    /** The choice for [field], defaulting to SHOW for fields this profile does not mention. */
    fun choiceFor(field: PrivacyField): Visibility = visibility[field] ?: Visibility.SHOW

    /** Fields this profile sets to HIDE. */
    val hiddenFields: Set<PrivacyField> get() = PrivacyField.entries.filter { choiceFor(it) == Visibility.HIDE }.toSet()

    /** A copy with every visibility inverted. Used by tests and by "maximum" style profiles. */
    fun inverted(): PrivacyProfile =
        copy(
            name = name,
            visibility = PrivacyField.entries.associateWith { choiceFor(it).inverted() },
        )

    /** One line for diagnostics; contains no user data. */
    fun toDisplayLine(): String = "$name (${if (builtIn) "built-in" else "custom"}, hidden: ${hiddenFields.size})"
}

/**
 * The six profiles T76 requires by name.
 *
 * Built-ins are ordinary [PrivacyProfile] values with [PrivacyProfile.builtIn] set. They
 * can be switched to and duplicated but not edited or deleted, so a user can always return
 * to a known-good configuration — which is the entire point of shipping them.
 */
object BuiltInPrivacyProfiles {
    const val NORMAL = "builtin.normal"
    const val GHOST = "builtin.ghost"
    const val WORK = "builtin.work"
    const val NIGHT = "builtin.night"
    const val MAXIMUM_PRIVACY = "builtin.maximum_privacy"

    private fun visibility(hidden: Set<PrivacyField>): Map<PrivacyField, Visibility> =
        PrivacyField.entries.associateWith { field ->
            if (field in hidden) Visibility.HIDE else Visibility.SHOW
        }

    /** Stock behaviour, with everything visible. */
    val normal =
        PrivacyProfile(
            id = NORMAL,
            name = "Normal",
            builtIn = true,
            visibility = visibility(emptySet()),
            callBehavior = CallBehavior.ALLOW,
            notificationPrivacy = NotificationPrivacy.FULL,
        )

    /**
     * Disappear from presence without rejecting calls or hiding notification content.
     *
     * This is the "read without being seen" profile: every presence signal is off, but the
     * phone still works normally.
     */
    val ghost =
        PrivacyProfile(
            id = GHOST,
            name = "Ghost",
            builtIn = true,
            visibility =
                visibility(
                    setOf(
                        PrivacyField.READ_RECEIPTS,
                        PrivacyField.TYPING,
                        PrivacyField.RECORDING,
                        PrivacyField.ONLINE,
                        PrivacyField.LAST_SEEN,
                        PrivacyField.STATUS_VIEWS,
                    ),
                ),
            callBehavior = CallBehavior.SILENCE,
            notificationPrivacy = NotificationPrivacy.FULL,
        )

    /**
     * Keep receipts working (so colleagues are not left on delivered) while hiding
     * availability and recording state.
     */
    val work =
        PrivacyProfile(
            id = WORK,
            name = "Work",
            builtIn = true,
            visibility =
                visibility(
                    setOf(
                        PrivacyField.RECORDING,
                        PrivacyField.ONLINE,
                        PrivacyField.LAST_SEEN,
                    ),
                ),
            callBehavior = CallBehavior.ALLOW,
            notificationPrivacy = NotificationPrivacy.FULL,
        )

    /** Everything hidden, calls silenced, lock screen shows nothing. */
    val night =
        PrivacyProfile(
            id = NIGHT,
            name = "Night",
            builtIn = true,
            visibility =
                visibility(
                    setOf(
                        PrivacyField.READ_RECEIPTS,
                        PrivacyField.TYPING,
                        PrivacyField.RECORDING,
                        PrivacyField.ONLINE,
                        PrivacyField.LAST_SEEN,
                        PrivacyField.STATUS_VIEWS,
                    ),
                ),
            callBehavior = CallBehavior.SILENCE,
            notificationPrivacy = NotificationPrivacy.HIDE_ALL,
        )

    /** The strongest built-in: nothing visible, calls rejected, notifications scrubbed. */
    val maximumPrivacy =
        PrivacyProfile(
            id = MAXIMUM_PRIVACY,
            name = "Maximum Privacy",
            builtIn = true,
            visibility = visibility(PrivacyField.entries.toSet()),
            callBehavior = CallBehavior.REJECT,
            notificationPrivacy = NotificationPrivacy.HIDE_ALL,
        )

    /** Every built-in profile, in the order the UI should show them. */
    val all: List<PrivacyProfile> = listOf(normal, ghost, work, night, maximumPrivacy)

    /** The profile a fresh install starts on. */
    val default: PrivacyProfile = normal

    /** Finds a built-in by id. */
    fun byId(id: String): PrivacyProfile? = all.firstOrNull { it.id == id }
}

/** One reason a profile cannot be saved, with a message that says what to change. */
data class PrivacyValidationProblem(
    val code: String,
    val message: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$code: $message"
}

/**
 * Structural validation for a profile, independent of storage.
 *
 * "Invalid combinations are rejected" is the T76 acceptance criterion, and it needs a
 * concrete definition or it means nothing. Two kinds of rule are enforced here:
 *
 * 1. **Structural**: a name must exist, be unique among the profiles it will live with, and
 *    be short enough to display.
 * 2. **Contradictory**: hiding online presence while showing last-seen still reveals the
 *    same information, so the combination is either a mistake or a false sense of privacy.
 *    It is rejected with an explanation rather than silently accepted.
 */
object PrivacyProfileValidation {
    /** The longest accepted profile name; longer names cannot render in the profile picker. */
    const val MAX_NAME_LENGTH: Int = 40

    /**
     * Validates [profile].
     *
     * @param existingNames names already in use by other profiles, compared case-insensitively
     * @param ownName when editing, the name this profile already has, excluded from the clash check
     */
    fun validate(
        profile: PrivacyProfile,
        existingNames: Set<String> = emptySet(),
        ownName: String? = null,
    ): List<PrivacyValidationProblem> {
        val problems = ArrayList<PrivacyValidationProblem>()

        val trimmed = profile.name.trim()
        if (trimmed.isEmpty()) {
            problems.add(PrivacyValidationProblem("name_blank", "The profile name cannot be empty."))
        }
        if (trimmed.length > MAX_NAME_LENGTH) {
            problems.add(
                PrivacyValidationProblem(
                    "name_too_long",
                    "The profile name must be at most $MAX_NAME_LENGTH characters.",
                ),
            )
        }
        if (trimmed.any { it.isISOControl() }) {
            problems.add(PrivacyValidationProblem("name_control_chars", "The profile name contains control characters."))
        }
        val normalised = trimmed.lowercase()
        val clashes =
            existingNames
                .filterNot { ownName != null && it.equals(ownName, ignoreCase = true) }
                .any { it.trim().lowercase() == normalised }
        if (trimmed.isNotEmpty() && clashes) {
            problems.add(
                PrivacyValidationProblem(
                    "name_duplicate",
                    "A profile named \"$trimmed\" already exists.",
                ),
            )
        }

        if (profile.choiceFor(PrivacyField.ONLINE) == Visibility.HIDE &&
            profile.choiceFor(PrivacyField.LAST_SEEN) == Visibility.SHOW
        ) {
            problems.add(
                PrivacyValidationProblem(
                    "contradiction_last_seen",
                    "Last seen cannot be shown while online presence is hidden: both reveal the " +
                        "same activity. Hide last seen as well, or show online presence.",
                ),
            )
        }

        return problems
    }

    /** Renders validation problems as a message a caller can show directly. */
    fun describe(problems: List<PrivacyValidationProblem>): String = problems.joinToString(separator = "\n") { it.toDisplayLine() }
}
