package com.wmods.wppenhacer.privacy

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.obj
import com.wmods.wppenhacer.platform.string

/** The outcome of a profile operation, explicit about *why* something was refused. */
sealed interface PrivacyOpResult<out T> {
    /** The operation completed; [value] is the resulting profile or state. */
    data class Success<T>(
        val value: T,
    ) : PrivacyOpResult<T>

    /** The profile failed validation; nothing was written. */
    data class Rejected(
        val problems: List<PrivacyValidationProblem>,
    ) : PrivacyOpResult<Nothing>

    /** No profile with that id exists. */
    data class NotFound(
        val id: String,
    ) : PrivacyOpResult<Nothing>

    /** Applying the profile to preferences failed; the previous state is untouched. */
    data class Failed(
        val message: String,
    ) : PrivacyOpResult<Nothing>

    /** Whether the operation succeeded. */
    val isSuccess: Boolean get() = this is Success
}

/**
 * Applies an accepted profile to the real preferences.
 *
 * The platform layer cannot know the surrounding app's preference keys, so the store takes
 * this port. It is allowed to throw: the store treats any throw as "nothing applied",
 * which is what keeps a partial write from leaving the account in a half-switched state.
 */
fun interface PrivacyProfileApplier {
    fun apply(profile: PrivacyProfile)
}

/**
 * Owns the profile set and the active profile.
 *
 * Built-ins are compiled in; user profiles live as one JSON array under
 * [KEY_CUSTOM_PROFILES] and the active id under [KEY_ACTIVE]. Storing custom profiles as a
 * single document rather than one key per field means a corrupt profile can be dropped
 * individually at decode time, and it keeps the whole set consistent in one write.
 *
 * Switching is two-phase on purpose: validate the profile, apply it through [applier], and
 * only then persist the active id. If any step fails, the previous active profile remains
 * in effect, which is the "applies compatible settings atomically" requirement.
 */
class PrivacyProfileStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    /** Built-ins followed by custom profiles, in stable order. */
    fun profiles(): List<PrivacyProfile> =
        synchronized(lock) {
            BuiltInPrivacyProfiles.all + customProfiles()
        }

    /** The id of the active profile, falling back to the default when it is unknown. */
    fun activeId(): String =
        synchronized(lock) {
            val stored = store.getString(KEY_ACTIVE) ?: return@synchronized BuiltInPrivacyProfiles.default.id
            if (profiles().any { it.id == stored }) stored else BuiltInPrivacyProfiles.default.id
        }

    /** The active profile. */
    fun active(): PrivacyProfile =
        synchronized(lock) {
            profiles().first { it.id == activeId() }
        }

    /** Creates a custom profile. */
    fun create(
        name: String,
        visibility: Map<PrivacyField, Visibility>,
        callBehavior: CallBehavior = CallBehavior.ALLOW,
        notificationPrivacy: NotificationPrivacy = NotificationPrivacy.FULL,
    ): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val profile =
                PrivacyProfile(
                    id = nextCustomId(),
                    name = name.trim(),
                    builtIn = false,
                    visibility = visibility,
                    callBehavior = callBehavior,
                    notificationPrivacy = notificationPrivacy,
                )
            val problems = PrivacyProfileValidation.validate(profile, existingNames())
            if (problems.isNotEmpty()) return@synchronized PrivacyOpResult.Rejected(problems)

            writeCustom(customProfiles() + profile)
            PrivacyOpResult.Success(profile)
        }

    /** Duplicates [sourceId] under [newName]; the copy is always a custom profile. */
    fun duplicate(
        sourceId: String,
        newName: String,
    ): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val source = findProfile(sourceId) ?: return@synchronized PrivacyOpResult.NotFound(sourceId)
            val copy = source.copy(id = nextCustomId(), name = newName.trim(), builtIn = false)
            val problems = PrivacyProfileValidation.validate(copy, existingNames())
            if (problems.isNotEmpty()) return@synchronized PrivacyOpResult.Rejected(problems)

            writeCustom(customProfiles() + copy)
            PrivacyOpResult.Success(copy)
        }

    /** Renames a custom profile. Built-in names are part of the product and cannot change. */
    fun rename(
        id: String,
        newName: String,
    ): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val profile = findProfile(id) ?: return@synchronized PrivacyOpResult.NotFound(id)
            if (profile.builtIn) {
                return@synchronized PrivacyOpResult.Rejected(
                    listOf(
                        PrivacyValidationProblem(
                            "builtin_immutable",
                            "\"${profile.name}\" is a built-in profile. Duplicate it to make changes.",
                        ),
                    ),
                )
            }
            val renamed = profile.copy(name = newName.trim())
            val problems = PrivacyProfileValidation.validate(renamed, existingNames(), ownName = profile.name)
            if (problems.isNotEmpty()) return@synchronized PrivacyOpResult.Rejected(problems)

            writeCustom(customProfiles().map { if (it.id == id) renamed else it })
            PrivacyOpResult.Success(renamed)
        }

    /** Replaces a custom profile's choices. */
    fun update(
        id: String,
        visibility: Map<PrivacyField, Visibility>,
        callBehavior: CallBehavior,
        notificationPrivacy: NotificationPrivacy,
    ): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val profile = findProfile(id) ?: return@synchronized PrivacyOpResult.NotFound(id)
            if (profile.builtIn) {
                return@synchronized PrivacyOpResult.Rejected(
                    listOf(
                        PrivacyValidationProblem(
                            "builtin_immutable",
                            "\"${profile.name}\" is a built-in profile. Duplicate it to make changes.",
                        ),
                    ),
                )
            }
            val updated =
                profile.copy(
                    visibility = visibility,
                    callBehavior = callBehavior,
                    notificationPrivacy = notificationPrivacy,
                )
            val problems = PrivacyProfileValidation.validate(updated, existingNames(), ownName = profile.name)
            if (problems.isNotEmpty()) return@synchronized PrivacyOpResult.Rejected(problems)

            writeCustom(customProfiles().map { if (it.id == id) updated else it })
            PrivacyOpResult.Success(updated)
        }

    /**
     * Deletes a custom profile.
     *
     * Deleting the active profile first switches the account back to the default, so a
     * deletion can never leave the module pointing at a profile that no longer exists.
     */
    fun delete(id: String): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val profile = findProfile(id) ?: return@synchronized PrivacyOpResult.NotFound(id)
            if (profile.builtIn) {
                return@synchronized PrivacyOpResult.Rejected(
                    listOf(
                        PrivacyValidationProblem(
                            "builtin_immutable",
                            "\"${profile.name}\" is a built-in profile and cannot be deleted.",
                        ),
                    ),
                )
            }
            val wasActive = store.getString(KEY_ACTIVE) == id
            val remaining = customProfiles().filterNot { it.id == id }
            writeCustom(remaining)
            if (wasActive) {
                store.putString(KEY_ACTIVE, BuiltInPrivacyProfiles.default.id)
            }
            PrivacyOpResult.Success(profile)
        }

    /**
     * Switches to [id] without touching preferences.
     *
     * Used when the caller applies the profile itself; [switchTo] with an applier is the
     * safe form for real account changes.
     */
    fun switchTo(id: String): PrivacyOpResult<PrivacyProfile> = switchTo(id, applier = null)

    /**
     * Switches to [id], applying it through [applier] before persisting the change.
     *
     * @return [PrivacyOpResult.Failed] when the applier throws; the active profile is then
     *   still the previous one, and the caller can show the error without guessing.
     */
    fun switchTo(
        id: String,
        applier: PrivacyProfileApplier?,
    ): PrivacyOpResult<PrivacyProfile> =
        synchronized(lock) {
            val profile = findProfile(id) ?: return@synchronized PrivacyOpResult.NotFound(id)
            val problems = PrivacyProfileValidation.validate(profile, existingNames(), ownName = profile.name)
            if (problems.isNotEmpty()) return@synchronized PrivacyOpResult.Rejected(problems)

            if (applier != null) {
                try {
                    applier.apply(profile)
                } catch (error: Throwable) {
                    return@synchronized PrivacyOpResult.Failed(
                        "applying the profile failed: ${error.javaClass.simpleName}",
                    )
                }
            }
            store.putString(KEY_ACTIVE, profile.id)
            PrivacyOpResult.Success(profile)
        }

    /** Drops custom profiles and the active selection. Used by tests and factory reset. */
    fun clear() =
        synchronized(lock) {
            store.remove(KEY_CUSTOM_PROFILES)
            store.remove(KEY_ACTIVE)
        }

    private fun findProfile(id: String): PrivacyProfile? = profiles().firstOrNull { it.id == id }

    private fun existingNames(): Set<String> = profiles().map { it.name }.toSet()

    private fun nextCustomId(): String {
        // Monotonic within a millisecond and collision-free across restarts because the
        // timestamp grows; the suffix only disambiguates same-millisecond creations.
        var candidate = "custom.${now()}"
        var counter = 1
        while (profiles().any { it.id == candidate }) {
            candidate = "custom.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun customProfiles(): List<PrivacyProfile> =
        synchronized(lock) {
            val text = store.getString(KEY_CUSTOM_PROFILES) ?: return@synchronized emptyList()
            val array = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return@synchronized emptyList()
            array.mapNotNull { decodeProfile(it) }.filterNot { it.builtIn || it.id.startsWith("builtin.") }
        }

    private fun writeCustom(profiles: List<PrivacyProfile>) {
        val array = JsonValue.Arr(profiles.map { encodeProfile(it) })
        store.putString(KEY_CUSTOM_PROFILES, MiniJson.write(array))
    }

    private fun encodeProfile(profile: PrivacyProfile): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(profile.id),
            "name" to jsonString(profile.name),
            "builtIn" to null,
            "visibility" to
                JsonValue.Obj(
                    profile.visibility.entries.associate { (field, choice) -> field.name to jsonString(choice.name) },
                ),
            "callBehavior" to jsonString(profile.callBehavior.name),
            "notificationPrivacy" to jsonString(profile.notificationPrivacy.name),
        )

    private fun decodeProfile(value: JsonValue): PrivacyProfile? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val id = fields.string("id") ?: return null
        val name = fields.string("name") ?: return null
        val visibility = HashMap<PrivacyField, Visibility>()
        fields.obj("visibility")?.forEach { (key, raw) ->
            val field = PrivacyField.entries.firstOrNull { it.name == key } ?: return@forEach
            val choice = Visibility.entries.firstOrNull { it.name == raw.stringOrNullValue() } ?: return@forEach
            visibility[field] = choice
        }
        val callBehavior =
            CallBehavior.entries.firstOrNull { it.name == fields.string("callBehavior") }
                ?: CallBehavior.ALLOW
        val notificationPrivacy =
            NotificationPrivacy.entries
                .firstOrNull { it.name == fields.string("notificationPrivacy") }
                ?: NotificationPrivacy.FULL
        return PrivacyProfile(
            id = id,
            name = name,
            builtIn = false,
            visibility = visibility,
            callBehavior = callBehavior,
            notificationPrivacy = notificationPrivacy,
        )
    }

    private fun JsonValue.stringOrNullValue(): String? = (this as? JsonValue.Str)?.value

    companion object {
        /** Storage key for the custom profile array. */
        const val KEY_CUSTOM_PROFILES: String = "wae.privacy.profiles"

        /** Storage key for the active profile id. */
        const val KEY_ACTIVE: String = "wae.privacy.active"
    }
}
