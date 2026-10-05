package com.wax.module.storage

import com.wax.module.platform.JsonValue
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.obj
import com.wax.module.platform.string

/** A decoded backup. */
data class BackupDocument(
    val schemaVersion: Int,
    val waxVersion: String,
    val createdAtMillis: Long,
    val packageProfile: String,
    /** Section id -> object of key/value entries. */
    val sections: Map<String, JsonValue>,
) {
    /** Every key across every section, for the restore planner. */
    fun keys(): List<String> =
        sections.values
            .mapNotNull { (it as? JsonValue.Obj)?.fields?.keys }
            .flatten()

    /** One line for the backup list. */
    fun toDisplayLine(): String = "schema $schemaVersion, $waxVersion, $packageProfile, ${keys().size} key(s)"
}

/** The outcome of decoding a backup. */
sealed interface BackupDecodeResult {
    /** The document decoded. */
    data class Decoded(
        val document: BackupDocument,
    ) : BackupDecodeResult

    /** The document was refused; [reason] explains. */
    data class Rejected(
        val reason: String,
    ) : BackupDecodeResult
}

/**
 * The Backup 3.0 codec (T148).
 *
 * Every document carries `schemaVersion`, `waxVersion`, `createdAt` and `packageProfile`,
 * because those four fields are what make a restore decision possible *before* touching any
 * setting. A newer schema is refused outright — a v3 app cannot know what a v4 field means,
 * and guessing would corrupt the restore; an older schema is accepted and handed to the
 * restore planner, which is where the per-key migration decisions live.
 */
object BackupV3Codec {
    /** The schema this version writes. */
    const val CURRENT_SCHEMA: Int = 3

    /** Encodes [document] as JSON. */
    fun encode(document: BackupDocument): String =
        MiniJson.write(
            jsonObject(
                "schemaVersion" to jsonNumber(document.schemaVersion.toLong()),
                "waxVersion" to jsonString(document.waxVersion),
                "createdAt" to jsonNumber(document.createdAtMillis),
                "packageProfile" to jsonString(document.packageProfile),
                "sections" to document.sections.toJson(),
            ),
        )

    /** Decodes a document, refusing anything this version cannot understand. */
    fun decode(text: String?): BackupDecodeResult {
        val root =
            MiniJson.parse(text)?.let { (it as? JsonValue.Obj)?.fields }
                ?: return BackupDecodeResult.Rejected("The file is not a valid backup document.")
        val schema =
            root.long("schemaVersion")
                ?: return BackupDecodeResult.Rejected("The backup does not declare a schema version.")
        if (schema > CURRENT_SCHEMA) {
            return BackupDecodeResult.Rejected(
                "This backup uses schema $schema, but this version understands schema $CURRENT_SCHEMA. " +
                    "Update WA X before restoring.",
            )
        }
        val sections =
            root.obj("sections")
                ?: return BackupDecodeResult.Rejected("The backup has no sections.")
        return BackupDecodeResult.Decoded(
            BackupDocument(
                schemaVersion = schema.toInt(),
                waxVersion = root.string("waxVersion").orEmpty(),
                createdAtMillis = root.long("createdAt") ?: 0L,
                packageProfile = root.string("packageProfile").orEmpty(),
                sections = sections,
            ),
        )
    }

    /** Encodes and encrypts a document (T149). */
    fun encodeEncrypted(
        document: BackupDocument,
        password: CharArray,
        iterations: Int = VaultCrypto.DEFAULT_ITERATIONS,
    ): ByteArray = VaultCrypto.encrypt(encode(document).toByteArray(Charsets.UTF_8), password, iterations)

    /** Decrypts and decodes an encrypted backup. */
    fun decodeEncrypted(
        bytes: ByteArray,
        password: CharArray,
    ): BackupDecodeResult {
        val plaintext =
            VaultCrypto.decrypt(bytes, password)
                ?: return BackupDecodeResult.Rejected("The password is wrong or the backup file is damaged.")
        return decode(String(plaintext, Charsets.UTF_8))
    }

    private fun Map<String, JsonValue>.toJson(): JsonValue = JsonValue.Obj(this)
}

/**
 * What a backup may contain (T148).
 *
 * "Do not include sensitive access tokens by default" is enforced by exclusion rather than
 * by the caller remembering: anything matching a sensitive prefix is dropped while the
 * backup is built, and the same policy is exposed so the UI can state what is excluded.
 */
object BackupPolicy {
    /** Key prefixes that never enter a backup. */
    val EXCLUDED_PREFIXES: List<String> =
        listOf(
            "wae.tasker.token_hash",
            "wae.tasker.created_at",
            "wae.tasker.expires_at",
            "wae.vault.",
        )

    /** Whether [key] is excluded from backups. */
    fun isExcluded(key: String): Boolean = EXCLUDED_PREFIXES.any { key.startsWith(it) }

    /** Drops excluded keys from a section. */
    fun filter(entries: Map<String, JsonValue>): Map<String, JsonValue> = entries.filterKeys { !isExcluded(it) }

    /** The sentence shown in the backup screen. */
    fun statement(): String =
        "Backups include settings, profiles, rules, themes and selected metadata. " +
            "Access tokens and vault contents are never included."
}

/** How one key in an incoming backup is handled (T150). */
enum class RestoreKeyClass {
    /** Copied as-is. */
    COMPATIBLE,

    /** Copied under a new name. */
    MIGRATED,

    /** Accepted but obsolete; copied only when no newer value exists. */
    DEPRECATED,

    /** Refused; not copied. */
    REJECTED,
}

/** The classification of one key. */
data class RestoreKeyPlan(
    val key: String,
    val classification: RestoreKeyClass,
    val detail: String? = null,
) {
    /** One line for the restore preview. */
    fun toDisplayLine(): String = "$key -> ${classification.name.lowercase()}" + if (detail != null) " ($detail)" else ""
}

/** The full plan, shown before any restore happens. */
data class RestorePlan(
    val entries: List<RestoreKeyPlan>,
    val backupSchemaVersion: Int,
    val currentSchemaVersion: Int,
    val packageProfile: String,
) {
    /** Whether the backup's schema is understood. */
    val schemaSupported: Boolean get() = backupSchemaVersion <= currentSchemaVersion

    /** How many keys fall into each class. */
    val counts: Map<RestoreKeyClass, Int>
        get() = RestoreKeyClass.entries.associateWith { target -> entries.count { it.classification == target } }

    /** Whether anything may be restored. */
    val isRestorable: Boolean
        get() = schemaSupported && counts[RestoreKeyClass.REJECTED] == 0 && entries.isNotEmpty()

    /** The preview rendering. */
    fun render(): String =
        buildString {
            appendLine("Backup schema: $backupSchemaVersion (current $currentSchemaVersion)")
            appendLine("Package profile: $packageProfile")
            RestoreKeyClass.entries.forEach { target ->
                appendLine("${target.name.lowercase().replaceFirstChar { it.uppercase() }} keys: ${counts[target] ?: 0}")
            }
            entries
                .filter { it.classification != RestoreKeyClass.COMPATIBLE }
                .forEach { appendLine("* ${it.toDisplayLine()}") }
        }.trimEnd()
}

/**
 * Classifies an incoming backup before restoring it (T150).
 *
 * The planner is deliberately strict: a key it does not recognise is rejected rather than
 * copied, because writing an unknown key into preferences is how old data silently
 * reappears in a new version. Migrations are explicit, deprecated keys are listed by the
 * caller, and a too-new schema stops the whole restore at the door.
 */
class CrossVersionRestorePlanner(
    private val knownKeys: Set<String>,
    private val migrations: Map<String, String> = emptyMap(),
    private val deprecatedKeys: Set<String> = emptySet(),
    private val currentSchema: Int = BackupV3Codec.CURRENT_SCHEMA,
) {
    /** Classifies every key in [document]. */
    fun plan(document: BackupDocument): RestorePlan =
        RestorePlan(
            entries =
                document.keys().sorted().map { key ->
                    when {
                        knownKeys.contains(key) -> RestoreKeyPlan(key, RestoreKeyClass.COMPATIBLE)
                        migrations.containsKey(key) ->
                            RestoreKeyPlan(
                                key,
                                RestoreKeyClass.MIGRATED,
                                "renamed to ${migrations[key]}",
                            )

                        deprecatedKeys.contains(key) -> RestoreKeyPlan(key, RestoreKeyClass.DEPRECATED)
                        else -> RestoreKeyPlan(key, RestoreKeyClass.REJECTED, "unknown to this version")
                    }
                },
            backupSchemaVersion = document.schemaVersion,
            currentSchemaVersion = currentSchema,
            packageProfile = document.packageProfile,
        )

    /**
     * The entries a restore would actually write, under their current names.
     *
     * Rejected keys are dropped, migrated keys are renamed, and deprecated keys are kept
     * last so a newer value wins when both exist.
     */
    fun restorableEntries(document: BackupDocument): Map<String, JsonValue> {
        val plan = plan(document)
        val result = LinkedHashMap<String, JsonValue>()
        plan.entries
            .filter { it.classification != RestoreKeyClass.REJECTED }
            .sortedBy { it.classification == RestoreKeyClass.DEPRECATED } // false first, true last
            .forEach { entry ->
                val value = valueOf(document, entry.key) ?: return@forEach
                val target = migrations[entry.key] ?: entry.key
                if (entry.classification != RestoreKeyClass.DEPRECATED || !result.containsKey(target)) {
                    result[target] = value
                }
            }
        return result
    }

    private fun valueOf(
        document: BackupDocument,
        key: String,
    ): JsonValue? =
        document.sections.values
            .mapNotNull { (it as? JsonValue.Obj)?.fields }
            .firstNotNullOfOrNull { it[key] }
}
