package com.wax.module.scheduler

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.string
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The only variables a template may contain.
 *
 * The allowlist is the safety mechanism: templates are plain text, a variable is only
 * expanded when the named value is available, and nothing else — no expressions, no
 * lookups into message content, no replacement chaining. A template is therefore fully
 * deterministic for a given context, which is what "variables only when deterministic and
 * safe" requires.
 */
enum class TemplateVariable(
    val token: String,
    val displayName: String,
) {
    CONTACT_NAME("{name}", "Contact name"),
    MY_NAME("{me}", "My name"),
    DATE("{date}", "Today's date"),
    TIME("{time}", "Current time"),
    WEEKDAY("{weekday}", "Weekday"),
}

/** The values a template may be expanded with. Anything absent is left unresolved. */
data class TemplateContext(
    val contactName: String? = null,
    val myName: String? = null,
    val now: ZonedDateTime? = null,
)

/** The result of expanding one template. */
data class RenderedTemplate(
    val text: String,
    val expanded: Set<TemplateVariable>,
    val unresolved: Set<TemplateVariable>,
) {
    /** Whether every variable in the template had a value. */
    val isComplete: Boolean get() = unresolved.isEmpty()
}

/**
 * Expands allowlisted variables in one pass.
 *
 * Both "expand once" and "leave unknown tokens alone" are deliberate: expanding once means
 * a value that itself contains `{date}` cannot trigger a second replacement (a template
 * injection), and leaving unknown tokens visible means the user sees that a variable did
 * not resolve instead of silently sending an empty string to a contact.
 */
object TemplateRenderer {
    /** The variables used by [template]. */
    fun variablesIn(template: String): Set<TemplateVariable> = TemplateVariable.entries.filter { template.contains(it.token) }.toSet()

    /** Tokens of the form `{...}` that are not allowlisted. */
    fun unknownTokens(template: String): List<String> {
        val tokens = TOKEN_REGEX.findAll(template).map { it.value }.toList()
        return tokens.filterNot { token -> TemplateVariable.entries.any { it.token == token } }.distinct()
    }

    /**
     * Expands [template] against [context] in a single pass.
     *
     * The regex walks the *original* template only, and inserted values are never rescanned,
     * so a value that itself contains `{date}` is sent literally instead of being expanded —
     * the template-injection case this object exists to prevent.
     */
    fun render(
        template: String,
        context: TemplateContext,
    ): RenderedTemplate {
        val expanded = LinkedHashSet<TemplateVariable>()
        val unresolved = LinkedHashSet<TemplateVariable>()
        val text =
            TOKEN_REGEX.replace(template) { match ->
                val token = match.value
                val variable = TemplateVariable.entries.firstOrNull { it.token == token }
                if (variable == null) {
                    token
                } else {
                    val value = valueOf(variable, context)
                    if (value == null) {
                        unresolved.add(variable)
                        token
                    } else {
                        expanded.add(variable)
                        value
                    }
                }
            }
        return RenderedTemplate(text = text, expanded = expanded, unresolved = unresolved)
    }

    private val TOKEN_REGEX = Regex("\\{[a-zA-Z0-9_]+}")

    private fun valueOf(
        variable: TemplateVariable,
        context: TemplateContext,
    ): String? =
        when (variable) {
            TemplateVariable.CONTACT_NAME -> context.contactName?.takeIf { it.isNotBlank() }
            TemplateVariable.MY_NAME -> context.myName?.takeIf { it.isNotBlank() }
            TemplateVariable.DATE -> context.now?.format(DateTimeFormatter.ISO_LOCAL_DATE)
            TemplateVariable.TIME -> context.now?.format(DateTimeFormatter.ofPattern("HH:mm"))
            TemplateVariable.WEEKDAY ->
                context.now
                    ?.dayOfWeek
                    ?.name
                    ?.lowercase()
                    ?.replaceFirstChar { it.uppercase() }
        }
}

/** One saved reply template. */
data class ReplyTemplate(
    val id: String,
    val category: String,
    val text: String,
    val createdAtMillis: Long,
) {
    /** One line for the template picker. */
    fun toDisplayLine(): String = "[$category] ${text.take(40)}"
}

/** The outcome of saving a template. */
sealed interface TemplateSaveResult {
    /** The template was stored. */
    data class Success(
        val template: ReplyTemplate,
    ) : TemplateSaveResult

    /** The template was refused; [message] says why. */
    data class Rejected(
        val message: String,
    ) : TemplateSaveResult
}

/**
 * Stores reply templates grouped by category.
 *
 * Saving with an [id] updates; saving without one creates. Categories are just names on the
 * templates — there is no separate category entity to keep in sync, so renaming the last
 * template in a category removes the category, which is the behaviour a flat list implies.
 */
class ReplyTemplateStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Every template, grouped order first. */
    fun templates(): List<ReplyTemplate> {
        val text = store.getString(KEY_TEMPLATES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }
    }

    /** Category names in first-seen order. */
    fun categories(): List<String> = templates().map { it.category }.distinct()

    /** Templates in one category. */
    fun templatesFor(category: String): List<ReplyTemplate> = templates().filter { it.category.equals(category, ignoreCase = true) }

    /**
     * Creates or updates a template.
     *
     * @param id the template to update, or null to create a new one
     */
    fun save(
        id: String?,
        category: String,
        text: String,
    ): TemplateSaveResult {
        val cleanCategory = category.trim()
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return TemplateSaveResult.Rejected("A template cannot be empty.")
        }
        if (cleanCategory.isEmpty()) {
            return TemplateSaveResult.Rejected("A template needs a category.")
        }
        if (cleanCategory.length > MAX_CATEGORY_LENGTH) {
            return TemplateSaveResult.Rejected("The category must be at most $MAX_CATEGORY_LENGTH characters.")
        }
        if (cleanText.length > MAX_TEMPLATE_LENGTH) {
            return TemplateSaveResult.Rejected("The template must be at most $MAX_TEMPLATE_LENGTH characters.")
        }
        val current = templates()
        if (id != null && current.none { it.id == id }) {
            return TemplateSaveResult.Rejected("There is no template with id \"$id\".")
        }
        val template =
            ReplyTemplate(
                id = id ?: nextId(),
                category = cleanCategory,
                text = cleanText,
                createdAtMillis = current.firstOrNull { it.id == id }?.createdAtMillis ?: now(),
            )
        val updated = if (id == null) current + template else current.map { if (it.id == id) template else it }
        write(updated)
        return TemplateSaveResult.Success(template)
    }

    /** Deletes a template. */
    fun delete(id: String): Boolean {
        val current = templates()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** Drops every template. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_TEMPLATES)
    }

    private fun write(templates: List<ReplyTemplate>) {
        store.putString(KEY_TEMPLATES, MiniJson.write(jsonArray(templates.map { encode(it) })))
    }

    private fun nextId(): String {
        var candidate = "template.${now()}"
        var counter = 1
        while (templates().any { it.id == candidate }) {
            candidate = "template.${now()}.$counter"
            counter++
        }
        return candidate
    }

    private fun encode(template: ReplyTemplate): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(template.id),
            "category" to jsonString(template.category),
            "text" to jsonString(template.text),
            "createdAt" to jsonNumber(template.createdAtMillis),
        )

    private fun decode(value: JsonValue): ReplyTemplate? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return ReplyTemplate(
            id = fields.string("id") ?: return null,
            category = fields.string("category") ?: return null,
            text = fields.string("text") ?: return null,
            createdAtMillis = fields.long("createdAt") ?: 0L,
        )
    }

    companion object {
        /** Storage key for the template array. */
        const val KEY_TEMPLATES: String = "wae.scheduler.templates"

        /** Longest accepted category name. */
        const val MAX_CATEGORY_LENGTH: Int = 32

        /** Longest accepted template text. */
        const val MAX_TEMPLATE_LENGTH: Int = 1000
    }
}
