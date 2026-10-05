package com.wax.module.diagnostics

/**
 * Removes user data from free-form text before it enters a failure report.
 *
 * A failure report is meant to be shareable, and WhatsApp-derived exception messages are
 * not safe to share: a resolver that fails while handling a message can easily carry a
 * JID, a phone number or message text in its message or in a `Caused by:` line. This is
 * the single place that guarantee is enforced, so it is deliberately aggressive: it
 * prefers losing detail over leaking an identifier.
 *
 * The rules below run in order and each one replaces the whole match, so a JID is not
 * first partially rewritten into something that still exposes digits.
 */
object ReportRedactor {
    /** Placeholder written in place of anything that could identify a user. */
    const val PLACEHOLDER: String = "[redacted]"

    /**
     * Ordered redaction rules.
     *
     * Identifiers are matched before bare digit runs, so `4915112345678@s.whatsapp.net`
     * becomes a single placeholder rather than a placeholder followed by leftover digits.
     */
    private val RULES: List<Regex> =
        listOf(
            // Any local@domain token: WhatsApp JIDs (s.whatsapp.net, g.us, lid, broadcast,
            // newsletter) as well as ordinary email addresses.
            Regex("[A-Za-z0-9_+.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            // Explicitly tagged identifiers, e.g. jid=... or phone:...
            Regex("(?i)\\b(jid|phone|number|msisdn|sender|recipient|chat|contact)\\s*[=:]\\s*\\S+"),
            // International or separated phone numbers, with an optional leading plus.
            Regex("\\+?\\d[\\d\\s().-]{6,}\\d"),
            // Any remaining run of six or more digits.
            Regex("\\b\\d{6,}\\b"),
            // Message-like payloads: a bracketed or quoted run. Message content cannot be pattern
            // matched, so this is length based. The threshold is deliberately low: quoted or
            // bracketed text in an exception message is almost always user content rather than
            // a class name or a diagnostic token, and losing detail is preferable to leaking a
            // message into a report the user is invited to share.
            Regex("\\[[^\\]]{$MIN_QUOTED_LENGTH,}\\]"),
            Regex("\"[^\"]{$MIN_QUOTED_LENGTH,}\""),
        )

    /** Shortest bracketed or quoted run treated as message content rather than a token. */
    const val MIN_QUOTED_LENGTH: Int = 16

    /** Longest text kept from a single field; anything beyond is dropped, not truncated mid-token. */
    const val MAX_FIELD_LENGTH: Int = 200

    /** Number of stack frames retained. Enough to locate a fault, short enough to read. */
    const val MAX_FRAMES: Int = 12

    /**
     * Dotted numeric tokens that must survive redaction.
     *
     * A WhatsApp version (`2.26.40.21`), an Android API level and a module version are
     * exactly the context a failure report exists to carry, yet they match the shape of a
     * phone number. They are therefore lifted out before the identifier rules run and put
     * back afterwards, so a report can always say which build it came from.
     */
    private val VERSION_LIKE = Regex("\\b\\d+(?:\\.\\d+){1,3}(?:[-+][A-Za-z0-9]+)?\\b")

    /** Sentinel wrapper for a protected token. Uses control characters no rule matches. */
    private const val TOKEN_PREFIX: String = "\u0001"
    private const val TOKEN_SUFFIX: String = "\u0002"

    /**
     * Returns [text] with every recognised identifier replaced by [PLACEHOLDER].
     *
     * Null and blank input are returned unchanged so callers can pass optional fields
     * straight through. Version-shaped tokens are preserved; see [VERSION_LIKE].
     */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return ""

        val preserved = ArrayList<String>()
        val protectedText =
            VERSION_LIKE.replace(text) { match ->
                val token = TOKEN_PREFIX + preserved.size + TOKEN_SUFFIX
                preserved.add(match.value)
                token
            }

        var result = protectedText
        for (rule in RULES) {
            result = rule.replace(result, PLACEHOLDER)
        }

        for (index in preserved.indices.reversed()) {
            result = result.replace(TOKEN_PREFIX + index + TOKEN_SUFFIX, preserved[index])
        }
        return result
    }

    /**
     * Redacts [text] and bounds its length.
     *
     * Truncation happens after redaction so the kept prefix is always safe, and an
     * ellipsis marks that something was removed.
     */
    fun redactAndBound(
        text: String?,
        maxLength: Int = MAX_FIELD_LENGTH,
    ): String {
        val redacted = redact(text)
        if (redacted.length <= maxLength) return redacted
        return redacted.take(maxLength).trimEnd() + "..."
    }

    /**
     * Reduces a throwable to `class.method` frame labels, redacted and bounded.
     *
     * File names and line numbers are dropped: they add noise for a user filing a report
     * and can embed paths. Frames from the platform and from the module's own injected
     * runtime are dropped too, because they are noise on every single report.
     */
    fun summariseStackTrace(throwable: Throwable): List<String> {
        val frames =
            throwable.stackTrace
                .asSequence()
                .filterNot { frame ->
                    val owner = frame.className
                    owner.startsWith("android.") ||
                        owner.startsWith("androidx.") ||
                        owner.startsWith("java.") ||
                        owner.startsWith("kotlin.") ||
                        owner.startsWith("dalvik.") ||
                        owner.startsWith("com.android.")
                }.take(MAX_FRAMES)
                .map { frame ->
                    val method = frame.methodName.ifEmpty { "<init>" }
                    redact("${frame.className}.$method")
                }.toList()

        val causedBy = throwable.cause
        if (causedBy != null && causedBy !== throwable) {
            val causeLabel = redactAndBound(causedBy.javaClass.name, MAX_FIELD_LENGTH)
            return frames + "caused by: $causeLabel"
        }
        return frames
    }
}
