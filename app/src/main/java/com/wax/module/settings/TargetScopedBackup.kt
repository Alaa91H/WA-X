package com.wax.module.settings

import com.wax.module.platform.JsonValue
import com.wax.module.platform.TargetApp
import com.wax.module.storage.BackupDocument
import com.wax.module.storage.BackupV3Codec

/**
 * Maps the three settings scopes onto the existing backup document.
 *
 * Deliberately not a new backup format. [BackupV3Codec] already carries a `sections`
 * map keyed by a section id, which is exactly the shape a target-scoped backup needs,
 * so the scope codes become the section ids:
 *
 * ```json
 * { "schemaVersion": 3, "sections": { "global": {}, "whatsapp": {}, "business": {} } }
 * ```
 *
 * Writing a second, parallel backup format would mean two codecs, two restore paths
 * and two things to keep in step, and would leave the older one unable to restore a
 * target-scoped file.
 */
object TargetScopedBackup {
    /** The section id used for the concrete global defaults. */
    const val GLOBAL_SECTION: String = "global"

    /** The section id for a target's overrides. */
    fun sectionFor(app: TargetApp): String = app.code

    /** Every section id a current document should carry. */
    fun sectionIds(): List<String> = listOf(GLOBAL_SECTION) + TargetApp.entries.map { it.code }

    /**
     * Builds a backup document from [store].
     *
     * Empty scopes are omitted rather than written as empty objects, so a document
     * records only what the user actually configured and the restore can tell the
     * difference between "never set" and "explicitly cleared".
     */
    fun toDocument(
        store: SettingsStore,
        waxVersion: String,
        createdAtMillis: Long,
    ): BackupDocument {
        val sections = LinkedHashMap<String, JsonValue>()

        val globalKeys = store.keysWithOverrides(SettingsScope.Global)
        if (globalKeys.isNotEmpty()) {
            sections[GLOBAL_SECTION] = sectionOf(store, SettingsScope.Global, globalKeys)
        }

        for (app in TargetApp.entries) {
            val scope = SettingsScope.Target(app)
            val keys = store.keysWithOverrides(scope)
            if (keys.isNotEmpty()) sections[sectionFor(app)] = sectionOf(store, scope, keys)
        }

        return BackupDocument(
            schemaVersion = BackupV3Codec.CURRENT_SCHEMA,
            waxVersion = waxVersion,
            createdAtMillis = createdAtMillis,
            packageProfile = "all-targets",
            sections = sections,
        )
    }

    /**
     * Restores [document] into [store], all-or-nothing.
     *
     * The whole document is validated and flattened before anything is written, so a
     * malformed file cannot leave the user with some targets restored and others
     * wiped, which is the failure the previous typed import already fixed once.
     */
    fun restore(
        store: SettingsStore,
        document: BackupDocument,
    ): RestoreResult {
        val parsed = parse(document)
        if (parsed is RestoreResult.Rejected) return parsed

        val (global, targets) =
            (parsed as RestoreResult.Valid).let {
                it.global to it.targets
            }
        store.replaceAll(global, targets)
        return RestoreResult.Restored(
            globalKeys = global.size,
            perTargetKeys = targets.mapValues { it.value.size },
        )
    }

    /** Validates and flattens [document] without writing anything. */
    fun parse(document: BackupDocument): RestoreResult {
        val unknown =
            document.sections.keys.filter { key ->
                key != GLOBAL_SECTION && TargetApp.fromCode(key) == null
            }
        if (unknown.isNotEmpty()) {
            return RestoreResult.Rejected("Unknown settings section(s): ${unknown.joinToString(", ")}")
        }

        val global = LinkedHashMap<String, String>()
        val targets = LinkedHashMap<TargetApp, Map<String, String>>()

        for ((sectionId, section) in document.sections) {
            val obj =
                (section as? JsonValue.Obj)?.fields
                    ?: return RestoreResult.Rejected("Section '$sectionId' is not an object.")

            val values = LinkedHashMap<String, String>()
            for ((key, value) in obj) {
                val text =
                    when (value) {
                        is JsonValue.Str -> value.value

                        is JsonValue.Num -> value.value.toString()

                        is JsonValue.Flag -> value.value.toString()

                        // A null or nested value has no meaning for a preference and is a
                        // sign the file is not a settings backup.
                        else -> return RestoreResult.Rejected(
                            "Section '$sectionId' holds an unsupported value for '$key'.",
                        )
                    }
                values[key] = text
            }

            if (sectionId == GLOBAL_SECTION) {
                global.putAll(values)
            } else {
                val app = TargetApp.fromCode(sectionId)!!
                targets[app] = values
            }
        }

        return RestoreResult.Valid(global, targets)
    }

    /** The outcome of restoring a backup. */
    sealed interface RestoreResult {
        /** Validated and applied. */
        data class Restored(
            val globalKeys: Int,
            val perTargetKeys: Map<TargetApp, Int>,
        ) : RestoreResult

        /** Refused; nothing was written. */
        data class Rejected(
            val reason: String,
        ) : RestoreResult

        /** Parsed but not yet written. */
        data class Valid(
            val global: Map<String, String>,
            val targets: Map<TargetApp, Map<String, String>>,
        ) : RestoreResult
    }

    private fun sectionOf(
        store: SettingsStore,
        scope: SettingsScope,
        keys: Collection<String>,
    ): JsonValue =
        JsonValue.Obj(
            keys.sorted().associateWith { key ->
                JsonValue.Str(store.readString(scope, key) ?: "")
            },
        )

    /** Encodes [store] as a backup JSON string. */
    fun encode(
        store: SettingsStore,
        waxVersion: String,
        createdAtMillis: Long,
    ): String = BackupV3Codec.encode(toDocument(store, waxVersion, createdAtMillis))

    /** Renders the section ids for diagnostics, without the values. */
    fun describeSections(document: BackupDocument): String = document.sections.keys.joinToString(", ")
}
