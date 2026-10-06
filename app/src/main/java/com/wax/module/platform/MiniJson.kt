package com.wax.module.platform

/**
 * A minimal, total JSON model used by every persistence format the platform adds.
 *
 * `org.json` is Android-only and unavailable in plain unit tests, and the module already
 * hand-rolls its report codec for exactly that reason. Rather than repeat a parser in each
 * new engine, the platform keeps one small implementation whose contract is: parsing never
 * throws and never half-succeeds. A malformed document yields null, which every caller can
 * turn into "reject this file" without a try/catch and without the risk of applying a
 * partially decoded state.
 *
 * The model is intentionally small: objects, arrays, strings, numbers, booleans and null.
 * Nothing else is needed by themes, backups, profiles or rules, and every avoided feature
 * (comments, duplicate keys, big numbers) is one less way for stored data to be
 * misinterpreted.
 */
sealed interface JsonValue {
    /** A JSON object. Field order is preserved so re-encoding a decoded document is stable. */
    data class Obj(
        val fields: Map<String, JsonValue>,
    ) : JsonValue

    /** A JSON array. */
    data class Arr(
        val items: List<JsonValue>,
    ) : JsonValue

    /** A JSON string. */
    data class Str(
        val value: String,
    ) : JsonValue

    /** A JSON number, held as a double because that is the most a JSON number guarantees. */
    data class Num(
        val value: Double,
    ) : JsonValue

    /** A JSON boolean. */
    data class Flag(
        val value: Boolean,
    ) : JsonValue

    /** JSON null. */
    data object Null : JsonValue
}

/** Parses a document, returning null rather than throwing when anything is malformed. */
object MiniJson {
    /** Parses [text], or returns null when it is blank or not valid JSON. */
    fun parse(text: String?): JsonValue? {
        if (text.isNullOrBlank()) return null
        return runCatching { Reader(text).readDocument() }.getOrNull()
    }

    /** Encodes [value] back to text. The output of [parse] re-encodes without loss. */
    fun write(value: JsonValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                append('{')
                var first = true
                for ((key, field) in value.fields) {
                    if (!first) append(',')
                    first = false
                    appendQuoted(key)
                    append(':')
                    appendValue(field)
                }
                append('}')
            }

            is JsonValue.Arr -> {
                append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }

            is JsonValue.Str -> {
                appendQuoted(value.value)
            }

            is JsonValue.Flag -> {
                append(if (value.value) "true" else "false")
            }

            is JsonValue.Num -> {
                appendNumber(value.value)
            }

            JsonValue.Null -> {
                append("null")
            }
        }
    }

    /**
     * Writes a number in the shortest faithful form.
     *
     * Integral values are written without a trailing `.0` because every timestamp and
     * version code the platform stores is integral, and `1.0` round-tripping into text a
     * human reads is noise. Non-finite values cannot be represented in JSON and are written
     * as `null`, which is honest about the loss rather than emitting `NaN`.
     */
    private fun StringBuilder.appendNumber(value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            append("null")
            return
        }
        val asLong = value.toLong()
        if (asLong.toDouble() == value && asLong != Long.MIN_VALUE) {
            append(asLong)
        } else {
            append(value)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> {
                    append("\\\"")
                }

                '\\' -> {
                    append("\\\\")
                }

                '\n' -> {
                    append("\\n")
                }

                '\r' -> {
                    append("\\r")
                }

                '\t' -> {
                    append("\\t")
                }

                '\b' -> {
                    append("\\b")
                }

                '\u000C' -> {
                    append("\\f")
                }

                else -> {
                    if (char < ' ') {
                        append("\\u")
                        append(HEX[(char.code shr 12) and 0xF])
                        append(HEX[(char.code shr 8) and 0xF])
                        append(HEX[(char.code shr 4) and 0xF])
                        append(HEX[char.code and 0xF])
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private class Reader(
        private val text: String,
    ) {
        private var index = 0

        fun readDocument(): JsonValue {
            skipWhitespace()
            val value = readValue()
            skipWhitespace()
            if (index != text.length) throw Malformed("trailing content")
            return value
        }

        private fun readValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Flag(true))
                'f' -> readLiteral("false", JsonValue.Flag(false))
                'n' -> readLiteral("null", JsonValue.Null)
                null -> throw Malformed("unexpected end")
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(fields)
                    else -> throw Malformed("malformed object")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                next()
                return JsonValue.Arr(items)
            }
            while (true) {
                items.add(readValue())
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> throw Malformed("malformed array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                when (val char = next()) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> builder.append(char)
                }
            }
        }

        private fun readEscape(): Char =
            when (next()) {
                '"' -> {
                    '"'
                }

                '\\' -> {
                    '\\'
                }

                '/' -> {
                    '/'
                }

                'n' -> {
                    '\n'
                }

                'r' -> {
                    '\r'
                }

                't' -> {
                    '\t'
                }

                'b' -> {
                    '\b'
                }

                'f' -> {
                    '\u000C'
                }

                'u' -> {
                    if (index + 4 > text.length) throw Malformed("truncated unicode escape")
                    val hex = text.substring(index, index + 4)
                    index += 4
                    hex.toIntOrNull(16)?.toChar() ?: throw Malformed("bad unicode escape")
                }

                else -> {
                    throw Malformed("bad escape")
                }
            }

        private fun readNumber(): JsonValue.Num {
            val start = index
            if (peek() == '-') next()
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) {
                index++
            }
            if (start == index) throw Malformed("expected a number")
            val literal = text.substring(start, index)
            val value = literal.toDoubleOrNull() ?: throw Malformed("bad number")
            return JsonValue.Num(value)
        }

        private fun readLiteral(
            literal: String,
            value: JsonValue,
        ): JsonValue {
            if (index + literal.length > text.length) throw Malformed("truncated literal")
            if (text.substring(index, index + literal.length) != literal) {
                throw Malformed("expected $literal")
            }
            index += literal.length
            return value
        }

        /** Returns the current character without consuming it. */
        private fun peek(): Char? = if (index < text.length) text[index] else null

        /** Consumes and returns the current character, failing on unexpected end. */
        private fun next(): Char {
            val char = peek() ?: throw Malformed("unexpected end")
            index++
            return char
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (next() != expected) throw Malformed("expected '$expected'")
        }

        private class Malformed(
            message: String,
        ) : RuntimeException(message)
    }
}

// --- typed accessors ---------------------------------------------------------------------

/** Returns the object's fields, or null when this value is not an object. */
fun JsonValue.objOrNull(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

/** Returns the string, or null when this value is not a string. */
fun JsonValue.stringOrNull(): String? = (this as? JsonValue.Str)?.value

/** Returns the number, or null when this value is not a number. */
fun JsonValue.numberOrNull(): Double? = (this as? JsonValue.Num)?.value

/** Returns the number as a long, or null when this value is not an integral number. */
fun JsonValue.longOrNull(): Long? = numberOrNull()?.let { if (it % 1.0 == 0.0) it.toLong() else null }

/** Returns the boolean, or null when this value is not a boolean. */
fun JsonValue.booleanOrNull(): Boolean? = (this as? JsonValue.Flag)?.value

/** Returns the array items, or null when this value is not an array. */
fun JsonValue.arrayOrNull(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

/** Reads a string field from an object, or null when absent or the wrong type. */
fun Map<String, JsonValue>.string(key: String): String? = this[key]?.stringOrNull()

/** Reads an integral field from an object, or null when absent or the wrong type. */
fun Map<String, JsonValue>.long(key: String): Long? = this[key]?.longOrNull()

/** Reads a boolean field from an object, or null when absent or the wrong type. */
fun Map<String, JsonValue>.boolean(key: String): Boolean? = this[key]?.booleanOrNull()

/** Reads a nested object field from an object, or null when absent or the wrong type. */
fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue>? = this[key]?.objOrNull()

/** Reads an array field from an object, or null when absent or the wrong type. */
fun Map<String, JsonValue>.array(key: String): List<JsonValue>? = this[key]?.arrayOrNull()

/** Builds an object, skipping entries whose value is null. */
fun jsonObject(vararg entries: Pair<String, JsonValue?>): JsonValue.Obj =
    JsonValue.Obj(
        LinkedHashMap<String, JsonValue>().apply {
            entries.forEach { (key, value) -> if (value != null) put(key, value) }
        },
    )

/** Builds an array. */
fun jsonArray(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

/** Wraps a string. */
fun jsonString(value: String): JsonValue.Str = JsonValue.Str(value)

/** Wraps an integral number. */
fun jsonNumber(value: Long): JsonValue.Num = JsonValue.Num(value.toDouble())

/** Wraps a decimal number. */
fun jsonNumber(value: Double): JsonValue.Num = JsonValue.Num(value)

/** Wraps a boolean. */
fun jsonBoolean(value: Boolean): JsonValue.Flag = JsonValue.Flag(value)

/** Reads a string list field, skipping entries of the wrong type. */
fun Map<String, JsonValue>.stringList(key: String): List<String> = array(key).orEmpty().mapNotNull { it.stringOrNull() }

/** Builds an array of strings. */
fun jsonStrings(values: Collection<String>): JsonValue.Arr = JsonValue.Arr(values.map { JsonValue.Str(it) })
