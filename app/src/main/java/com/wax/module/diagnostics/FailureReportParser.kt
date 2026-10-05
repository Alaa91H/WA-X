package com.wax.module.diagnostics

/**
 * Reads back the JSON emitted by [FailureReportCodec].
 *
 * This is a deliberately small parser rather than a general JSON library: the input is
 * always this module's own output, so a full parser would add surface without adding
 * safety. Keeping it here means the encode/decode pair can be round-trip tested in CI,
 * which is what actually guarantees the format stays self-consistent.
 *
 * Anything unexpected yields an empty list rather than an exception: a corrupt history
 * file must never be able to break startup.
 */
object FailureReportParser {
    /**
     * Parses the top-level array written by [FailureReportCodec.encodeAll].
     *
     * A malformed entry does not discard the entries before it: a history file that is
     * truncated by a crash should still yield the reports that were written intact.
     */
    fun parseAll(text: String?): List<FeatureFailureReport> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { parse(text) }.getOrDefault(emptyList())
    }

    /**
     * Parses a single object written by [FailureReportCodec.encode].
     *
     * An array input is accepted too and yields its first entry, so a caller holding an
     * either-shaped document does not have to know which one it has.
     */
    fun parseOne(text: String?): FeatureFailureReport? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return parseAll(trimmed).firstOrNull()
        return runCatching { parse("[$trimmed]") }.getOrNull()?.firstOrNull()
    }

    private fun parse(text: String): List<FeatureFailureReport> {
        val reader = ReportReader(text)
        val reports = ArrayList<FeatureFailureReport>()

        // The array has to open, otherwise there is nothing to salvage.
        reader.expect('[')
        reader.skipWhitespace()
        if (reader.peek() == ']') {
            reader.next()
            return reports
        }

        while (true) {
            // One bad entry ends the scan but keeps everything already recovered.
            val report = runCatching { reader.readReport() }.getOrNull() ?: return reports
            reports.add(report)
            reader.skipWhitespace()
            when (reader.next()) {
                ',' -> reader.skipWhitespace()
                ']' -> return reports
                else -> return reports
            }
        }
    }

    private class ParseException(
        message: String,
    ) : RuntimeException(message)

    private class ReportReader(
        private val text: String,
        private var index: Int = 0,
    ) {
        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun peek(): Char? = if (index < text.length) text[index] else null

        fun next(): Char = if (index < text.length) text[index++] else throw ParseException("unexpected end")

        fun expect(expected: Char) {
            skipWhitespace()
            val actual = next()
            if (actual != expected) {
                throw ParseException("expected '$expected' but got '$actual'")
            }
        }

        fun readReport(): FeatureFailureReport {
            expect('{')
            val fields = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return build(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return build(fields)
                    else -> throw ParseException("malformed object")
                }
            }
        }

        private fun readValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '"' -> readString()
                '[' -> readStringArray()
                '{' -> readObject()
                else -> readNumber()
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
            when (val char = next()) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> '\u000C'
                'u' -> {
                    if (index + 4 > text.length) throw ParseException("truncated unicode escape")
                    val hex = text.substring(index, index + 4)
                    index += 4
                    hex.toIntOrNull(16)?.toChar() ?: throw ParseException("bad unicode escape")
                }

                else -> throw ParseException("bad escape")
            }

        private fun readStringArray(): List<String> {
            expect('[')
            val items = ArrayList<String>()
            skipWhitespace()
            if (peek() == ']') {
                next()
                return items
            }
            while (true) {
                items.add(readString())
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    ']' -> return items
                    else -> throw ParseException("malformed array")
                }
            }
        }

        /** Nested objects only appear in a hand-edited file; parsed so it can be skipped. */
        private fun readObject(): Map<String, Any?> {
            expect('{')
            val nested = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                next()
                return nested
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                expect(':')
                nested[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> Unit
                    '}' -> return nested
                    else -> throw ParseException("malformed nested object")
                }
            }
        }

        private fun readNumber(): Long {
            val start = index
            if (peek() == '-') next()
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) {
                index++
            }
            if (start == index) throw ParseException("expected a number")
            return text.substring(start, index).toLongOrNull()
                ?: throw ParseException("not an integral number")
        }

        private fun build(fields: Map<String, Any?>): FeatureFailureReport {
            val feature =
                fields["feature"] as? String
                    ?: throw ParseException("missing feature")
            val codeName =
                fields["code"] as? String
                    ?: throw ParseException("missing code")
            val code =
                FailureCode.entries.firstOrNull { it.name == codeName }
                    ?: throw ParseException("unknown code")
            val frames = (fields["frames"] as? List<*>).orEmpty().mapNotNull { it as? String }
            return FeatureFailureReport(
                featureId = feature,
                code = code,
                moduleVersion = fields["moduleVersion"] as? String ?: "",
                whatsappVersion = fields["whatsappVersion"] as? String ?: "",
                packageName = fields["package"] as? String ?: "",
                resolver = fields["resolver"] as? String,
                exceptionClass = fields["exception"] as? String,
                message = fields["message"] as? String,
                frames = frames,
                timestampMillis = fields["timestamp"] as? Long ?: 0L,
                threadName = fields["thread"] as? String,
            )
        }
    }
}
