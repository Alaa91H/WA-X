package com.wax.module.notifications

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.array
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonArray
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.jsonStrings
import com.wax.module.platform.long
import com.wax.module.platform.string
import com.wax.module.platform.stringList
import java.time.DayOfWeek
import java.time.LocalTime

/** One condition in a call rule. */
sealed interface CallCondition {
    /** The caller is not in the contact list. */
    data object UnknownCaller : CallCondition

    /** The caller is exactly this contact. */
    data class FromContact(
        val contactId: String,
    ) : CallCondition

    /** The call arrives inside this window (end earlier than start crosses midnight). */
    data class TimeWindow(
        val start: LocalTime,
        val end: LocalTime,
    ) : CallCondition

    /** The call arrives on one of these days. */
    data class Weekdays(
        val days: Set<DayOfWeek>,
    ) : CallCondition

    /** The caller already called within [withinMillis]. */
    data class RepeatCaller(
        val withinMillis: Long,
    ) : CallCondition
}

/** What happens to a matching call. */
enum class CallAction {
    ALLOW,
    MUTE,
    REJECT,
}

/** One call rule. */
data class CallRule(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val conditions: List<CallCondition>,
    val action: CallAction,
) {
    /** One line for the rules list. */
    fun toDisplayLine(): String = "$name -> ${action.name.lowercase()} (priority $priority)"
}

/** One incoming call to decide about. */
data class CallEvent(
    val callerId: String?,
    val isKnownContact: Boolean,
    val time: LocalTime,
    val dayOfWeek: DayOfWeek,
    val previousAttemptMillis: Long?,
    val nowMillis: Long,
)

/** What the engine decided, and why. */
data class CallDecision(
    val action: CallAction,
    val ruleId: String?,
    val reason: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "${action.name.lowercase()}: $reason"
}

/** Evaluates one call condition. */
fun CallCondition.matches(event: CallEvent): Boolean =
    when (this) {
        CallCondition.UnknownCaller -> {
            !event.isKnownContact
        }

        is CallCondition.FromContact -> {
            event.callerId == contactId
        }

        is CallCondition.TimeWindow -> {
            if (start <= end) {
                event.time >= start && event.time < end
            } else {
                event.time >= start || event.time < end
            }
        }

        is CallCondition.Weekdays -> {
            event.dayOfWeek in days
        }

        is CallCondition.RepeatCaller -> {
            event.previousAttemptMillis?.let {
                event.nowMillis - it in 0..withinMillis
            } ?: false
        }
    }

/**
 * Decides how an incoming call is handled (T140).
 *
 * Rules are evaluated by priority, highest first, first match wins; with no match the call is
 * allowed. Allow-as-default is the only safe default: a call blocklist that fails closed
 * would silently reject calls whenever the engine had a bad day.
 */
class CallRuleEngine(
    private val store: KeyValueStore,
) {
    /** Every stored rule, declaration order preserved. */
    fun rules(): List<CallRule> {
        val text = store.getString(KEY_RULES) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decodeRule(it) }
    }

    /** Adds a rule. Returns a reason when refused. */
    fun addRule(
        name: String,
        conditions: List<CallCondition>,
        action: CallAction,
        priority: Int = 0,
    ): String? {
        if (name.isBlank()) return "A call rule needs a name."
        if (conditions.isEmpty()) return "A call rule needs at least one condition."
        val rule =
            CallRule(
                id = nextId(),
                name = name.trim(),
                enabled = true,
                priority = priority,
                conditions = conditions,
                action = action,
            )
        writeRules(rules() + rule)
        return null
    }

    /** Removes a rule. */
    fun removeRule(id: String): Boolean {
        val current = rules()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        writeRules(remaining)
        return true
    }

    /** Enables or disables a rule. */
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ): Boolean {
        val current = rules()
        if (current.none { it.id == id }) return false
        writeRules(current.map { if (it.id == id) it.copy(enabled = enabled) else it })
        return true
    }

    /** The decision for [event]. */
    fun decide(event: CallEvent): CallDecision {
        val rule =
            rules()
                .filter { it.enabled }
                .sortedByDescending { it.priority }
                .firstOrNull { candidate -> candidate.conditions.all { it.matches(event) } }
                ?: return CallDecision(CallAction.ALLOW, null, "no rule matched")
        return CallDecision(rule.action, rule.id, "rule \"${rule.name}\" matched")
    }

    /** Drops every rule. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_RULES)
    }

    private fun writeRules(rules: List<CallRule>) {
        store.putString(KEY_RULES, MiniJson.write(jsonArray(rules.map { encodeRule(it) })))
    }

    private fun nextId(): String {
        var candidate = "call.${System.currentTimeMillis()}"
        var counter = 1
        while (rules().any { it.id == candidate }) {
            candidate = "call.${System.currentTimeMillis()}.$counter"
            counter++
        }
        return candidate
    }

    private fun encodeRule(rule: CallRule): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(rule.id),
            "name" to jsonString(rule.name),
            "enabled" to jsonBoolean(rule.enabled),
            "priority" to jsonNumber(rule.priority.toLong()),
            "action" to jsonString(rule.action.name),
            "conditions" to jsonArray(rule.conditions.map { encodeCondition(it) }),
        )

    private fun encodeCondition(condition: CallCondition): JsonValue.Obj =
        when (condition) {
            CallCondition.UnknownCaller -> {
                jsonObject("type" to jsonString("unknown"))
            }

            is CallCondition.FromContact -> {
                jsonObject("type" to jsonString("contact"), "contact" to jsonString(condition.contactId))
            }

            is CallCondition.TimeWindow -> {
                jsonObject(
                    "type" to jsonString("time"),
                    "start" to jsonString(condition.start.toString()),
                    "end" to jsonString(condition.end.toString()),
                )
            }

            is CallCondition.Weekdays -> {
                jsonObject(
                    "type" to jsonString("weekdays"),
                    "days" to jsonStrings(condition.days.map { it.name }),
                )
            }

            is CallCondition.RepeatCaller -> {
                jsonObject("type" to jsonString("repeat"), "within" to jsonNumber(condition.withinMillis))
            }
        }

    private fun decodeRule(value: JsonValue): CallRule? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val id = fields.string("id") ?: return null
        val name = fields.string("name") ?: return null
        val action = CallAction.entries.firstOrNull { it.name == fields.string("action") } ?: return null
        val conditions = fields.array("conditions").orEmpty().mapNotNull { decodeCondition(it) }
        if (conditions.isEmpty()) return null
        return CallRule(
            id = id,
            name = name,
            enabled = fields.boolean("enabled") ?: true,
            priority = (fields.long("priority") ?: 0L).toInt(),
            conditions = conditions,
            action = action,
        )
    }

    private fun decodeCondition(value: JsonValue): CallCondition? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return when (fields.string("type")) {
            "unknown" -> {
                CallCondition.UnknownCaller
            }

            "contact" -> {
                fields.string("contact")?.let { CallCondition.FromContact(it) }
            }

            "time" -> {
                val start =
                    fields.string("start")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                        ?: return null
                val end =
                    fields.string("end")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                        ?: return null
                CallCondition.TimeWindow(start, end)
            }

            "weekdays" -> {
                val days =
                    fields
                        .stringList("days")
                        .mapNotNull { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                        .toSet()
                if (days.isEmpty()) null else CallCondition.Weekdays(days)
            }

            "repeat" -> {
                fields.long("within")?.let { CallCondition.RepeatCaller(it) }
            }

            else -> {
                null
            }
        }
    }

    companion object {
        /** Storage key for call rules. */
        const val KEY_RULES: String = "wae.calls.rules"
    }
}

/** A call direction. */
enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED,
}

/** One call record (T141). */
data class CallHistoryEntry(
    val id: String,
    val contactId: String?,
    val direction: CallDirection,
    val startedAtMillis: Long,
    val durationSeconds: Long,
    val note: String? = null,
) {
    /** One line that omits the contact id. */
    fun toDisplayLine(): String = "${direction.name.lowercase()} call, ${durationSeconds}s" + if (note.isNullOrBlank()) "" else " (note)"
}

/**
 * Local call history with search.
 *
 * Only metadata the app can already see is stored — direction, time, duration — and the
 * contact reference is whatever local identifier the caller uses. Nothing is uploaded, and
 * search runs over that local data only.
 */
class CallHistoryStore(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 2000,
) {
    /** Adds a record, pruning the oldest when the bound is exceeded. */
    fun add(
        contactId: String?,
        direction: CallDirection,
        startedAtMillis: Long,
        durationSeconds: Long,
    ): CallHistoryEntry {
        val entry =
            CallHistoryEntry(
                id = nextId(),
                contactId = contactId,
                direction = direction,
                startedAtMillis = startedAtMillis,
                durationSeconds = durationSeconds.coerceAtLeast(0L),
            )
        write((all() + entry).takeLast(maxEntries))
        return entry
    }

    /** Every record, newest first. */
    fun all(): List<CallHistoryEntry> {
        val text = store.getString(KEY_HISTORY) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }.sortedByDescending { it.startedAtMillis }
    }

    /** Records matching [query] against contact id or note. */
    fun search(query: String): List<CallHistoryEntry> {
        if (query.isBlank()) return emptyList()
        return all().filter {
            it.contactId?.contains(query, ignoreCase = true) == true ||
                it.note?.contains(query, ignoreCase = true) == true
        }
    }

    /** Records for one contact. */
    fun byContact(contactId: String): List<CallHistoryEntry> = all().filter { it.contactId == contactId }

    /** Attaches a local note to a record. */
    fun setNote(
        id: String,
        note: String?,
    ): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        write(current.map { if (it.id == id) it.copy(note = note?.takeIf { value -> value.isNotBlank() }) else it })
        return true
    }

    /** The number of stored records. */
    fun count(): Int = all().size

    /** Drops the history. Used by tests and factory reset. */
    fun clear() {
        store.remove(KEY_HISTORY)
    }

    private fun write(entries: List<CallHistoryEntry>) {
        store.putString(KEY_HISTORY, MiniJson.write(jsonArray(entries.map { encode(it) })))
    }

    private fun nextId(): String = "call-entry.${now()}.${count()}"

    private fun encode(entry: CallHistoryEntry): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(entry.id),
            "contactId" to entry.contactId?.let { jsonString(it) },
            "direction" to jsonString(entry.direction.name),
            "startedAt" to jsonNumber(entry.startedAtMillis),
            "duration" to jsonNumber(entry.durationSeconds),
            "note" to entry.note?.let { jsonString(it) },
        )

    private fun decode(value: JsonValue): CallHistoryEntry? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return CallHistoryEntry(
            id = fields.string("id") ?: return null,
            contactId = fields.string("contactId"),
            direction =
                CallDirection.entries.firstOrNull { it.name == fields.string("direction") }
                    ?: return null,
            startedAtMillis = fields.long("startedAt") ?: 0L,
            durationSeconds = fields.long("duration") ?: 0L,
            note = fields.string("note"),
        )
    }

    companion object {
        /** Storage key for the call history. */
        const val KEY_HISTORY: String = "wae.calls.history"
    }
}

/** One recorded call (T142). */
data class RecordingEntry(
    val id: String,
    val fileName: String,
    val recordedAtMillis: Long,
    val durationSeconds: Long,
    val note: String? = null,
    val archived: Boolean = false,
) {
    /** One line for the recording list. */
    fun toDisplayLine(): String = "$fileName (${durationSeconds}s)" + if (archived) " [archived]" else ""
}

/**
 * Organises call recordings that already exist on the device.
 *
 * The library only names and annotates files it is told about; it never records anything
 * itself and never decides where a recording is stored. [legalNotice] is part of the API
 * because T142 requires the UI to remind users that recording laws vary — making the string
 * impossible to forget is better than remembering to add it to a screen.
 */
class RecordingLibrary(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 1000,
) {
    /** Registers a recording, or null for invalid input. */
    fun add(
        fileName: String,
        recordedAtMillis: Long,
        durationSeconds: Long,
    ): RecordingEntry? {
        if (!isSafeFileName(fileName)) return null
        val entry =
            RecordingEntry(
                id = nextId(),
                fileName = fileName.trim(),
                recordedAtMillis = recordedAtMillis,
                durationSeconds = durationSeconds.coerceAtLeast(0L),
            )
        write((all() + entry).takeLast(maxEntries))
        return entry
    }

    /** Renames a recording. Names containing path separators are refused. */
    fun rename(
        id: String,
        newName: String,
    ): Boolean {
        if (!isSafeFileName(newName)) return false
        val current = all()
        if (current.none { it.id == id }) return false
        write(current.map { if (it.id == id) it.copy(fileName = newName.trim()) else it })
        return true
    }

    /** Attaches a note to a recording. */
    fun setNote(
        id: String,
        note: String?,
    ): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        write(current.map { if (it.id == id) it.copy(note = note?.takeIf { value -> value.isNotBlank() }) else it })
        return true
    }

    /** Archives or unarchives a recording. */
    fun setArchived(
        id: String,
        archived: Boolean,
    ): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        write(current.map { if (it.id == id) it.copy(archived = archived) else it })
        return true
    }

    /** Every recording, newest first. */
    fun all(): List<RecordingEntry> {
        val text = store.getString(KEY_RECORDINGS) ?: return emptyList()
        val items = (MiniJson.parse(text) as? JsonValue.Arr)?.items ?: return emptyList()
        return items.mapNotNull { decode(it) }.sortedByDescending { it.recordedAtMillis }
    }

    /** Recordings matching [query] against file name or note. */
    fun search(query: String): List<RecordingEntry> {
        if (query.isBlank()) return emptyList()
        return all().filter {
            it.fileName.contains(query, ignoreCase = true) ||
                it.note?.contains(query, ignoreCase = true) == true
        }
    }

    /** Removes a record; the file itself is untouched. */
    fun remove(id: String): Boolean {
        val current = all()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return false
        write(remaining)
        return true
    }

    /** Drops the library. Recorded files are untouched. */
    fun clear() {
        store.remove(KEY_RECORDINGS)
    }

    private fun isSafeFileName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_FILE_NAME_LENGTH) return false
        if (trimmed.any { it.isISOControl() }) return false
        return !(trimmed.contains('/') || trimmed.contains('\\') || trimmed.contains(".."))
    }

    private fun write(entries: List<RecordingEntry>) {
        store.putString(KEY_RECORDINGS, MiniJson.write(jsonArray(entries.map { encode(it) })))
    }

    private fun nextId(): String = "recording.${now()}.${all().size}"

    private fun encode(entry: RecordingEntry): JsonValue.Obj =
        jsonObject(
            "id" to jsonString(entry.id),
            "fileName" to jsonString(entry.fileName),
            "recordedAt" to jsonNumber(entry.recordedAtMillis),
            "duration" to jsonNumber(entry.durationSeconds),
            "note" to entry.note?.let { jsonString(it) },
            "archived" to jsonBoolean(entry.archived),
        )

    private fun decode(value: JsonValue): RecordingEntry? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        return RecordingEntry(
            id = fields.string("id") ?: return null,
            fileName = fields.string("fileName") ?: return null,
            recordedAtMillis = fields.long("recordedAt") ?: 0L,
            durationSeconds = fields.long("duration") ?: 0L,
            note = fields.string("note"),
            archived = fields.boolean("archived") ?: false,
        )
    }

    companion object {
        /** Storage key for the recording library. */
        const val KEY_RECORDINGS: String = "wae.calls.recordings"

        /** Longest accepted file name. */
        const val MAX_FILE_NAME_LENGTH: Int = 128

        /** The reminder T142 requires before any recording feature is offered. */
        fun legalNotice(): String =
            "Call recording laws vary by jurisdiction. Make sure recording calls is legal where you are before using this feature."
    }
}
