package com.wax.module.health

/**
 * The smallest JSON reader and writer that can carry the health document.
 *
 * `org.json` ships with Android and is not on the classpath of a plain JVM unit test, and
 * the stored format has to be verifiable in CI, so this is hand rolled for the same reason
 * the failure-report codec is. It knows nothing about the health model: it produces maps,
 * lists, strings, longs, booleans and nulls, and leaves meaning to the codec above it.
 *
 * The reader never throws to its caller. A stored document is read at startup, and a
 * corrupt file must degrade to "nothing recorded" rather than to a crash loop — the reason
 * the reader is a separate, tested thing rather than five lines inside the store.
 */
internal object HealthJson {
    /** Reads a JSON document, or returns null when it cannot be read at all. */
    fun read(text: String?): Any? {
        if (text.isNullOrBlank()) return null
        return runCatching { Reader(text).readDocument() }.getOrNull()
    }

    /** Writes [value] as JSON text. Maps, lists, strings, longs, ints, booleans and nulls. */
    fun write(value: Any?): String =
        buildString {
            appendValue(value)
        }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> {
                append("null")
            }

            is String -> {
                appendQuoted(value)
            }

            is Boolean -> {
                append(if (value) "true" else "false")
            }

            is Int -> {
                append(value.toString())
            }

            is Long -> {
                append(value.toString())
            }

            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, entryValue) in value) {
                    if (key !is String) continue
                    if (!first) append(',')
                    first = false
                    appendQuoted(key)
                    append(':')
                    appendValue(entryValue)
                }
                append('}')
            }

            is Iterable<*> -> {
                append('[')
                var first = true
                for (item in value) {
                    if (!first) append(',')
                    first = false
                    appendValue(item)
                }
                append(']')
            }

            else -> {
                appendQuoted(value.toString())
            }
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        for (char in value) {
            when {
                char == '"' -> {
                    append("\\\"")
                }

                char == '\\' -> {
                    append("\\\\")
                }

                char == '\n' -> {
                    append("\\n")
                }

                char == '\r' -> {
                    append("\\r")
                }

                char == '\t' -> {
                    append("\\t")
                }

                char < ' ' -> {
                    append("\\u")
                    append(HEX[(char.code shr 12) and 0xF])
                    append(HEX[(char.code shr 8) and 0xF])
                    append(HEX[(char.code shr 4) and 0xF])
                    append(HEX[char.code and 0xF])
                }

                else -> {
                    append(char)
                }
            }
        }
        append('"')
    }

    private const val MAX_DEPTH = 32

    private val HEX = "0123456789abcdef".toCharArray()

    private class ParseException(
        message: String,
    ) : RuntimeException(message)

    private class Reader(
        private val text: String,
        private var index: Int = 0,
    ) {
        fun readDocument(): Any? {
            val value = readValue(0)
            skipWhitespace()
            return value
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("nesting is deeper than $MAX_DEPTH")
            skipWhitespace()
            return when (peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                null -> throw ParseException("unexpected end of document")
                else -> readNumber()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val fields = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return fields
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return fields
                    else -> throw ParseException("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                next()
                return items
            }
            while (true) {
                items.add(readValue(depth + 1))
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return items
                    else -> throw ParseException("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> {
                        return out.toString()
                    }

                    char == '\\' -> {
                        when (val escaped = next()) {
                            '"' -> {
                                out.append('"')
                            }

                            '\\' -> {
                                out.append('\\')
                            }

                            '/' -> {
                                out.append('/')
                            }

                            'b' -> {
                                out.append('\b')
                            }

                            'f' -> {
                                out.append('\u000C')
                            }

                            'n' -> {
                                out.append('\n')
                            }

                            'r' -> {
                                out.append('\r')
                            }

                            't' -> {
                                out.append('\t')
                            }

                            'u' -> {
                                val code = text.substring(index, index + 4).toInt(16)
                                index += 4
                                out.append(code.toChar())
                            }

                            else -> {
                                throw ParseException("unknown escape \\$escaped")
                            }
                        }
                    }

                    else -> {
                        out.append(char)
                    }
                }
            }
        }

        private fun readNumber(): Long {
            val start = index
            if (peek() == '-') next()
            while (peek()?.isDigit() == true) next()
            val scaled = peek() == '.'
            if (scaled) {
                next()
                while (peek()?.isDigit() == true) next()
            }
            val slice = text.substring(start, index)
            if (slice.isEmpty() || slice == "-") throw ParseException("not a number at $start")
            // The document only ever stores whole numbers; a fractional value is read as
            // its integer part rather than rejected, because a version written by a future
            // revision must not make today's reader discard the whole record.
            return if (scaled) slice.substringBefore('.').toLongOrNull() ?: 0L else slice.toLong()
        }

        private fun <T> readLiteral(
            literal: String,
            value: T,
        ): T {
            if (!text.startsWith(literal, index)) throw ParseException("expected $literal at $index")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun next(): Char = if (index < text.length) text[index++] else throw ParseException("unexpected end")

        private fun expect(expected: Char) {
            skipWhitespace()
            val actual = next()
            if (actual != expected) throw ParseException("expected '$expected' but got '$actual'")
        }
    }
}
