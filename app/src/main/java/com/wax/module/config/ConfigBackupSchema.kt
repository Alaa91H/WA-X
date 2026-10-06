package com.wax.module.config

/**
 * The configuration backup format.
 *
 * A backup document is a flat object of `key -> { type, value }` envelopes:
 *
 * ```json
 * { "antirevoke": { "type": "Boolean", "value": true },
 *   "thememode": { "type": "String",  "value": "1" } }
 * ```
 *
 * This object owns the type vocabulary on both sides, so the export names and the
 * import parser cannot drift apart. Parsing is deliberately total: an unrecognised
 * type or a mismatched value yields null rather than throwing, which lets a caller
 * reject a whole document instead of applying half of it.
 */
object ConfigBackupSchema {
    const val FIELD_TYPE: String = "type"
    const val FIELD_VALUE: String = "value"

    const val TYPE_STRING: String = "String"
    const val TYPE_BOOLEAN: String = "Boolean"
    const val TYPE_INTEGER: String = "Integer"
    const val TYPE_LONG: String = "Long"
    const val TYPE_FLOAT: String = "Float"
    const val TYPE_DOUBLE: String = "Double"
    const val TYPE_STRING_SET: String = "JSONArray"

    /**
     * The type name written for [value], or null when the value cannot be backed up.
     *
     * String sets are reported as [TYPE_STRING_SET] because that is what the historical
     * exporter wrote after flattening the set, and the importer still keys off it.
     */
    fun typeNameOf(value: Any?): String? =
        when (value) {
            null -> null
            is Boolean -> TYPE_BOOLEAN
            is Int -> TYPE_INTEGER
            is Long -> TYPE_LONG
            is Float -> TYPE_FLOAT
            is Double -> TYPE_DOUBLE
            is CharSequence -> TYPE_STRING
            is Set<*> -> TYPE_STRING_SET
            else -> null
        }

    /**
     * Parses one envelope into a storable value.
     *
     * @param typeName the declared type, tolerating the lower-case aliases that older
     *   exports and hand-edited files use
     * @param raw the decoded value; collections must be passed as a [List]
     * @return the value, or null when the type is unknown or the value does not match it
     */
    fun decode(
        typeName: String?,
        raw: Any?,
    ): ConfigValue? {
        if (typeName == null) return null
        return when (typeName) {
            TYPE_STRING_SET -> {
                decodeStringSet(raw)
            }

            TYPE_STRING -> {
                (raw as? CharSequence)?.let { ConfigValue.Text(it.toString()) }
            }

            TYPE_BOOLEAN, "boolean" -> {
                (raw as? Boolean)?.let { ConfigValue.Flag(it) }
            }

            TYPE_INTEGER, "int" -> {
                (raw as? Number)?.let { ConfigValue.Whole(it.toInt()) }
            }

            TYPE_LONG, "long" -> {
                (raw as? Number)?.let { ConfigValue.Wide(it.toLong()) }
            }

            TYPE_FLOAT, "float", TYPE_DOUBLE, "double" -> {
                (raw as? Number)?.let { ConfigValue.Decimal(it.toFloat()) }
            }

            else -> {
                null
            }
        }
    }

    /**
     * Decodes a set of values, mirroring the exporter's `JSONArray` representation.
     *
     * Entries are stringified rather than rejected, which preserves the previous
     * behaviour of a mixed-type array losing its non-string members.
     */
    private fun decodeStringSet(raw: Any?): ConfigValue.Texts? {
        val items = raw as? List<*> ?: return null
        return ConfigValue.Texts(items.map { it?.toString().orEmpty() }.toSet())
    }

    /**
     * Decodes a whole document.
     *
     * @param entries the document's `key -> (declaredType, rawValue)` pairs
     * @return every entry decoded, or null if any single entry is unusable
     */
    fun decodeAll(entries: List<BackupEntry>): List<Pair<String, ConfigValue>>? {
        val decoded = ArrayList<Pair<String, ConfigValue>>(entries.size)
        for (entry in entries) {
            val value = decode(entry.typeName, entry.rawValue) ?: return null
            decoded.add(entry.key to value)
        }
        return decoded
    }
}

/** One `key -> { type, value }` pair read from a backup document. */
data class BackupEntry(
    val key: String,
    val typeName: String?,
    val rawValue: Any?,
)
