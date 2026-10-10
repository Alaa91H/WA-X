package com.wax.module.diagnostics.selftest

/**
 * Reads a previously exported archive back without trusting it (#170).
 *
 * An imported ZIP is attacker-controlled input: it may have been edited, it may
 * claim a schema this build does not understand, and it may be missing the very
 * files that make it meaningful. So import is a three-step gate, in order:
 *
 * 1. the archive is **verified** — manifest present, every declared digest
 *    matching the bytes actually stored, no oversized entry;
 * 2. the manifest is **parsed defensively** — unknown or malformed fields are
 *    ignored, and nothing is inferred from a field that did not parse;
 * 3. the previous results are compared against the current report, and only
 *    the statuses a verified archive actually carried are used.
 *
 * A file that fails any step is reported as unusable. It is never partially
 * believed, and it is never repaired silently.
 */
class DiagnosticArchiveImporter(
    private val exporter: DiagnosticZipExporter = DiagnosticZipExporter(),
) {
    /** Why an import was refused, or what it contained when it was accepted. */
    sealed interface ImportResult {
        data class Rejected(
            val reason: Reason,
            val detail: String,
        ) : ImportResult

        data class Accepted(
            val manifest: Manifest,
            val previous: Map<String, PreviousResult>,
            val missingSources: List<String>,
        ) : ImportResult

        enum class Reason {
            NOT_A_VALID_ARCHIVE,
            CHECKSUMS_MISSING_OR_MISMATCHED,
            MANIFEST_MISSING,
            MANIFEST_UNREADABLE,
            UNSUPPORTED_SCHEMA,
        }
    }

    /** Only the fields a comparison actually needs. */
    data class Manifest(
        val schemaVersion: String,
        val scanId: String?,
        val whatsappVersion: String?,
        val appBuildSha: String?,
        val finishedUtcMillis: Long?,
        val declaredMissing: List<String>,
    )

    data class PreviousResult(
        val id: String,
        val status: DiagnosticStatus,
        val evidenceLevel: EvidenceLevel,
        val observed: String,
    )

    /** One regression delta between a verified previous scan and this one. */
    data class Delta(
        val id: String,
        val before: DiagnosticStatus,
        val after: DiagnosticStatus,
    ) {
        val isRegression: Boolean
            get() =
                severity(after) > severity(before) ||
                    (after == DiagnosticStatus.PASS && before != DiagnosticStatus.PASS)

        val isImprovement: Boolean
            get() = !isRegression && severity(after) < severity(before)

        private fun severity(status: DiagnosticStatus): Int =
            when (status) {
                DiagnosticStatus.FAIL -> 4
                DiagnosticStatus.BLOCKED -> 3
                DiagnosticStatus.NEEDS_EXTERNAL_VERIFICATION -> 2
                DiagnosticStatus.NOT_TESTED -> 2
                DiagnosticStatus.UNSUPPORTED -> 1
                DiagnosticStatus.RUNNING -> 1
                DiagnosticStatus.PASS -> 0
            }
    }

    fun import(bytes: ByteArray): ImportResult {
        val verification =
            runCatching { exporter.verify(bytes) }.getOrNull()
                ?: return ImportResult.Rejected(
                    ImportResult.Reason.NOT_A_VALID_ARCHIVE,
                    "the file could not be opened as an archive",
                )
        if (!verification.checksumsPresent || !verification.checksumMatches) {
            return ImportResult.Rejected(
                ImportResult.Reason.CHECKSUMS_MISSING_OR_MISMATCHED,
                "declared digests do not match the stored bytes",
            )
        }
        val manifestBytes =
            exporter.readEntry(bytes, "manifest.json")
                ?: return ImportResult.Rejected(
                    ImportResult.Reason.MANIFEST_MISSING,
                    "the archive has no manifest.json",
                )
        val manifest =
            parseManifest(manifestBytes.toString(Charsets.UTF_8))
                ?: return ImportResult.Rejected(
                    ImportResult.Reason.MANIFEST_UNREADABLE,
                    "manifest.json is not the object this build expects",
                )
        if (manifest.schemaVersion != DiagnosticSchema.SCHEMA_VERSION) {
            return ImportResult.Rejected(
                ImportResult.Reason.UNSUPPORTED_SCHEMA,
                "schema ${manifest.schemaVersion} cannot be read by ${DiagnosticSchema.SCHEMA_VERSION}",
            )
        }
        return ImportResult.Accepted(
            manifest = manifest,
            previous = parseResults(bytes),
            missingSources = manifest.declaredMissing,
        )
    }

    /**
     * Compares a verified previous scan against the current one.
     *
     * A check the previous archive never carried is reported as `null` rather
     * than assumed to be new or fixed: absence of evidence is not a delta.
     */
    fun compare(
        previous: Map<String, PreviousResult>,
        current: List<AtomicCheckResult>,
    ): List<Delta> =
        current.mapNotNull { result ->
            val before = previous[result.id]?.status ?: return@mapNotNull null
            Delta(id = result.id, before = before, after = result.status)
        }

    /** A short, honest comparison line for the UI. */
    fun describe(deltas: List<Delta>): String {
        if (deltas.isEmpty()) return "no comparable checks in the previous archive"
        val regressions = deltas.filter { it.isRegression }
        val improvements = deltas.filter { it.isImprovement }
        val summary =
            "${deltas.size} comparable checks: " +
                "${improvements.size} improved, ${regressions.size} regressed"
        if (regressions.isEmpty()) return summary
        return summary + "\n" + regressions.joinToString("\n") { "  ${it.id}: ${it.before} → ${it.after}" }
    }

    private fun parseManifest(text: String): Manifest? {
        val values = MiniJson.parseObject(text) ?: return null
        val schema = values["schema_version"] ?: return null
        return Manifest(
            schemaVersion = schema,
            scanId = values["scan_id"],
            whatsappVersion = values["whatsapp_version"],
            appBuildSha = values["app_build_sha"],
            finishedUtcMillis = values["finished_utc"]?.toLongOrNull(),
            declaredMissing = MiniJson.parseArray(values["declared_missing"]).orEmpty(),
        )
    }

    /**
     * Reads the per-test files and rebuilds the previous statuses.
     *
     * Only status, evidence level and observation are taken. An unparseable
     * record is skipped instead of being guessed at, so a damaged archive
     * contributes fewer facts rather than wrong ones.
     */
    private fun parseResults(bytes: ByteArray): Map<String, PreviousResult> {
        val names = runCatching { exporter.verify(bytes) }.getOrNull()?.entryNames.orEmpty()
        val found = LinkedHashMap<String, PreviousResult>()
        for (name in names.filter { it.startsWith("tests/") && it.endsWith(".json") }) {
            val text = exporter.readEntry(bytes, name)?.toString(Charsets.UTF_8) ?: continue
            val values = MiniJson.parseObject(text) ?: continue
            val id = values["id"] ?: continue
            val status = values["status"]?.let { name -> DiagnosticStatus.entries.find { it.name == name } }
            val level =
                values["evidence_level"]?.let { name ->
                    EvidenceLevel.entries.find { it.name == name }
                }
            if (status == null || level == null) continue
            found[id] =
                PreviousResult(
                    id = id,
                    status = status,
                    evidenceLevel = level,
                    observed = values["observed"].orEmpty(),
                )
        }
        return found
    }
}

/**
 * A deliberately small JSON reader.
 *
 * The project's reports are written by this same code, so a full parser would
 * be dead weight. What matters here is the opposite property: this reader
 * returns null rather than guessing when the text is not the shape it expects,
 * which is what makes an untrusted import safe.
 */
internal object MiniJson {
    /** Returns the string/number/bool values of a flat object, or null. */
    fun parseObject(text: String): Map<String, String>? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = text.substring(start + 1, end)
        val values = LinkedHashMap<String, String>()
        var index = 0
        while (index < body.length) {
            val keyStart = body.indexOf('"', index)
            if (keyStart < 0) break
            val keyEnd = findStringEnd(body, keyStart)
            if (keyEnd < 0) return null
            val key = unescape(body.substring(keyStart + 1, keyEnd))
            var cursor = keyEnd + 1
            while (cursor < body.length && (body[cursor].isWhitespace() || body[cursor] == ':')) cursor++
            if (cursor >= body.length) return null
            val value =
                when (body[cursor]) {
                    '"' -> {
                        val valueEnd = findStringEnd(body, cursor)
                        if (valueEnd < 0) return null
                        unescape(body.substring(cursor + 1, valueEnd)).also { cursor = valueEnd + 1 }
                    }

                    else -> {
                        val valueEnd = body.indexOf(',', cursor)
                        val raw = body.substring(cursor, if (valueEnd < 0) body.length else valueEnd)
                        cursor = if (valueEnd < 0) body.length else valueEnd + 1
                        raw.trim()
                    }
                }
            values[key] = value
            index = cursor
        }
        return values
    }

    /** Parses a flat array of strings; anything else yields null. */
    fun parseArray(text: String?): List<String>? {
        if (text == null) return null
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val body = text.substring(start + 1, end)
        val items = mutableListOf<String>()
        var index = 0
        while (true) {
            val quote = body.indexOf('"', index)
            if (quote < 0) break
            val quoteEnd = findStringEnd(body, quote)
            if (quoteEnd < 0) return null
            items.add(unescape(body.substring(quote + 1, quoteEnd)))
            index = quoteEnd + 1
        }
        return items
    }

    /** Index of the closing quote, honouring backslash escapes. */
    private fun findStringEnd(
        text: String,
        quoteIndex: Int,
    ): Int {
        var index = quoteIndex + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '"' -> return index
            }
            index++
        }
        return -1
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index == value.lastIndex) {
                out.append(character)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
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
                    val hex = value.substring(index + 2, minOf(index + 6, value.length))
                    val code = hex.toIntOrNull(16)
                    if (code == null) return value
                    out.append(code.toChar())
                    index += 4
                }

                else -> {
                    out.append(escaped)
                }
            }
            index += 2
        }
        return out.toString()
    }
}
