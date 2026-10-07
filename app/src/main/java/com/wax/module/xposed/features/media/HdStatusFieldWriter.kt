package com.wax.module.xposed.features.media

import java.lang.reflect.Field

/**
 * Writes quality overrides into a WhatsApp configuration object, and reports what
 * it actually did.
 *
 * The bug this replaces: every override was `fields["videoMaxEdge"]?.setInt(...)`.
 * Two failure modes, both invisible.
 *
 *  * A renamed or removed field made the write a silent no-op. HD Status appeared
 *    to be on and did nothing, and there was no log line to prove it.
 *  * A *mismatched* field was worse. The name-to-field map is derived by parsing the
 *    class `toString()`, so a field added or reordered shifts the pairing. The write
 *    then either lands on the wrong field or, when the field has the wrong type,
 *    throws `IllegalArgumentException` from inside WhatsApp's own constructor and
 *    takes the status screen down with it.
 *
 * So every write here checks that the field exists *and* has the type the value
 * needs, logs the value before and after, and records a rejected write instead of
 * throwing. Losing one override degrades the feature; it must never crash WhatsApp.
 */
class HdStatusFieldWriter(
    /** Label used in diagnostics, for example "ProcessVideoQuality". */
    private val label: String,
    /** The field map produced by the `toString()`-derived resolver. */
    private val fields: Map<String, Field>,
    private val log: (String) -> Unit,
) {
    /** Overrides applied so far. */
    var applied: Int = 0
        private set

    /** Overrides requested but not applied. */
    var skipped: Int = 0
        private set

    /** Primary names that were requested and are absent under every alias. */
    val missingNames: MutableList<String> = ArrayList()

    /** True when at least one requested override did not land. */
    val hasLosses: Boolean get() = skipped > 0

    /**
     * The object writes apply to.
     *
     * Installed for the duration of one hook invocation by [withTarget], which is
     * what lets the write helpers read like the calls they replace.
     */
    private var target: Any? = null

    /** Runs [block] with [instance] installed as the write target. */
    fun <T> withTarget(
        instance: Any?,
        block: HdStatusFieldWriter.() -> T,
    ): T {
        val previous = target
        target = instance
        try {
            return block()
        } finally {
            target = previous
        }
    }

    /** Sets the first present alias of [aliases] whose field is an `int`. */
    fun setInt(
        aliases: List<String>,
        value: Int,
    ): Boolean =
        setPrimitive(aliases, Int::class.javaPrimitiveType!!) { field, instance ->
            field.setInt(instance, value)
        }

    /** Sets the first present alias of [aliases] whose field is a `boolean`. */
    fun setBoolean(
        aliases: List<String>,
        value: Boolean,
    ): Boolean =
        setPrimitive(aliases, Boolean::class.javaPrimitiveType!!) { field, instance ->
            field.setBoolean(instance, value)
        }

    /**
     * Sets a reference-typed override, used to clear the high-bitrate profile URL so
     * WhatsApp stops reusing a cached low-bitrate profile.
     */
    fun setNull(aliases: List<String>): Boolean {
        val field = selectField(aliases, null) ?: return recordMissing(aliases, null)
        val instance = target ?: return recordMissing(aliases, field)
        return try {
            log("$label.${field.name}: ${field.get(instance)} -> null")
            field.set(instance, null)
            applied++
            true
        } catch (t: Throwable) {
            recordFailure(field, t)
            false
        }
    }

    /**
     * Sets an enum-typed override such as the encoder bitrate mode.
     *
     * [constantName] is looked up in the field's own enum class rather than being
     * hard-coded, so the call site does not need the Android constant and the value
     * still resolves against whatever enum WhatsApp actually declares. When the
     * constant is absent, [fallbackOrdinal] is used if the enum is long enough, and
     * the substitution is logged because it changes behaviour.
     */
    fun setEnumConstant(
        aliases: List<String>,
        constantName: String,
        fallbackOrdinal: Int?,
    ): Boolean {
        val field = selectField(aliases, null) ?: return recordMissing(aliases, null)
        val instance = target ?: return recordMissing(aliases, field)
        val fieldType = field.type
        if (!fieldType.isEnum) return recordMissing(aliases, field, "expected an enum, found ${fieldType.simpleName}")
        val constants = fieldType.enumConstants ?: return recordMissing(aliases, field, "enum has no constants")
        val byName = constants.firstOrNull { (it as Enum<*>).name == constantName }
        val chosen =
            byName ?: fallbackOrdinal?.let { constants.getOrNull(it) }
                ?: return recordMissing(aliases, field, "no constant '$constantName'")
        return try {
            if (byName == null) {
                log("$label.${field.name}: '$constantName' absent, using ordinal ${constants.indexOf(chosen)}")
            } else {
                log("$label.${field.name}: set to $constantName")
            }
            field.set(instance, chosen)
            applied++
            true
        } catch (t: Throwable) {
            recordFailure(field, t)
            false
        }
    }

    /** Writes the closing summary: how many overrides landed and what was missing. */
    fun summarise(context: String) {
        val losses =
            buildString {
                append("$applied applied")
                if (skipped > 0) append(", $skipped skipped")
                if (missingNames.isNotEmpty()) append(", missing ${missingNames.distinct().joinToString("/")}")
            }
        log("$context: $losses")
    }

    private inline fun setPrimitive(
        aliases: List<String>,
        requiredType: Class<*>,
        write: (Field, Any) -> Unit,
    ): Boolean {
        val field = selectField(aliases, requiredType) ?: return recordMissing(aliases, null, requiredType.simpleName)
        val instance = target ?: return recordMissing(aliases, field)
        return try {
            val before = field.get(instance)
            write(field, instance)
            log("$label.${field.name}: $before -> ${field.get(instance)}")
            applied++
            true
        } catch (t: Throwable) {
            recordFailure(field, t)
            false
        }
    }

    /**
     * Picks the first alias that exists and, when [requiredType] is given, matches
     * it.
     *
     * The type check is the part that makes this safe against a desynchronised
     * `toString()` pairing: a boolean field offered where an int was asked for is
     * skipped rather than written to.
     */
    private fun selectField(
        aliases: List<String>,
        requiredType: Class<*>?,
    ): Field? =
        aliases
            .asSequence()
            .mapNotNull(fields::get)
            .firstOrNull { field ->
                field.isAccessible = true
                requiredType == null || field.type == requiredType
            }

    private fun recordMissing(
        aliases: List<String>,
        field: Field?,
        detail: String? = null,
    ): Boolean {
        skipped++
        missingNames += aliases.first()
        val suffix = if (detail == null) "" else " ($detail)"
        val name = field?.name ?: "no field"
        log("$label: $name not writable for ${aliases.first()}$suffix")
        return false
    }

    private fun recordFailure(
        field: Field,
        t: Throwable,
    ): Boolean {
        skipped++
        log("$label.${field.name}: write refused (${t.javaClass.simpleName}: ${t.message})")
        return false
    }
}
