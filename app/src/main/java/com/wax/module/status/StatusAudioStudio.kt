package com.wax.module.status

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.TargetApp
import com.wax.module.platform.boolean
import com.wax.module.platform.jsonBoolean
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.obj
import com.wax.module.platform.objOrNull
import com.wax.module.platform.string

/**
 * An audio container the Status composer may be handed.
 *
 * [nativeVoiceStatus] is the compatibility fact that matters: it says whether the installed
 * client can post this container as a voice Status without conversion. The value is a
 * conservative statement about the format, not about a particular version — the version's own
 * answer comes from [StatusAudioCapability], and the planner refuses a container the capability
 * does not list.
 */
enum class AudioContainer(
    /** Name shown in the editor and in diagnostics. */
    val label: String,
    /** MIME type used when handing the file to the system. */
    val mimeType: String,
    /** Whether it can be posted as a voice Status without conversion. */
    val nativeVoiceStatus: Boolean,
) {
    MP3("MP3", "audio/mpeg", false),
    M4A("M4A", "audio/mp4", true),
    AAC("AAC", "audio/aac", false),
    OGG("OGG", "audio/ogg", true),
    OPUS("Opus", "audio/opus", true),
    WAV("WAV", "audio/wav", false),
    FLAC("FLAC", "audio/flac", false),
    ;

    companion object {
        private val BY_EXTENSION: Map<String, AudioContainer> =
            mapOf(
                "mp3" to MP3,
                "m4a" to M4A,
                "aac" to AAC,
                "ogg" to OGG,
                "oga" to OGG,
                "opus" to OPUS,
                "wav" to WAV,
                "flac" to FLAC,
            )

        /** Recognises a container from a file name, or null when the extension is unknown. */
        fun fromFileName(fileName: String): AudioContainer? = BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]

        /** Recognises a container from a MIME type, ignoring any parameters. */
        fun fromMimeType(mimeType: String): AudioContainer? {
            val normalised = mimeType.substringBefore(';').trim().lowercase()
            return entries.firstOrNull { it.mimeType == normalised }
        }
    }
}

/**
 * What the installed client will accept for a voice Status.
 *
 * [maxVoiceStatusMillis] is read from the client and never hardcoded: WhatsApp's Status limit is
 * a per-version fact, and a constant here would either refuse a clip the client would post or
 * promise a post the client will reject. Zero means the limit could not be resolved, which the
 * planner treats as "use the documented compatibility fallback and say so", not as "no limit".
 */
data class StatusAudioCapability(
    val maxVoiceStatusMillis: Long,
    val supportedContainers: Set<AudioContainer>,
) {
    /** Whether the client's own limit was read. */
    val limitResolved: Boolean get() = maxVoiceStatusMillis > 0L

    companion object {
        /**
         * The conservative limit used when the client's own value cannot be read.
         *
         * It is a fallback, not the feature's definition: [StatusAudioCapability.Unknown] starts
         * here, the planner records a warning whenever it is used, and a resolved capability
         * always overrides it.
         */
        const val FALLBACK_MAX_MILLIS: Long = 60_000L

        /** The limit and formats were not resolvable; the planner falls back and says so. */
        val Unknown = StatusAudioCapability(0L, emptySet())

        /** A capability read from the client. */
        fun resolved(
            maxVoiceStatusMillis: Long,
            containers: Set<AudioContainer> = AudioContainer.entries.toSet(),
        ): StatusAudioCapability = StatusAudioCapability(maxVoiceStatusMillis, containers)
    }
}

/** The file the user picked, described without anything that would leave the device. */
data class StatusAudioSource(
    /** Display name only; the original full path is never carried past this point. */
    val fileName: String,
    /** The recognised container, or null when the format could not be identified. */
    val container: AudioContainer?,
    /** Length in milliseconds, or zero when it could not be read. */
    val durationMillis: Long,
    /** Size on disk, used only to warn about very large sources. */
    val sizeBytes: Long = 0L,
    /** Whether the file carries a location tag that would otherwise travel with it. */
    val hasLocationTag: Boolean = false,
    /** Whether the file carries an author or account name tag. */
    val hasAuthorTag: Boolean = false,
)

/** What to do when the selection is longer than the client will post. */
enum class StatusAudioOverflowAction(
    val label: String,
) {
    /** Stop and let the user choose between trimming, splitting or cancelling. */
    ASK("Choose"),

    /** Keep the first part of the selection, up to the limit. */
    TRIM("Trim to the limit"),

    /** Post the selection as a numbered series of parts. */
    AUTO_SPLIT("Auto split"),

    /** Give up without changing anything. */
    CANCEL("Cancel"),
}

/** How the selection is to be prepared before posting. */
data class StatusAudioOptions(
    /** Where the selection starts, in milliseconds into the source. */
    val trimStartMillis: Long = 0L,
    /** Where it ends, or null for the end of the file. */
    val trimEndMillis: Long? = null,
    val fadeInMillis: Long = 0L,
    val fadeOutMillis: Long = 0L,
    /** Playback volume, as a percentage. */
    val volumePercent: Int = 100,
    /** Whether to level the clip's loudness. */
    val normalize: Boolean = false,
    /** Whether split parts are labelled with their position. */
    val addNumbering: Boolean = true,
    /** Whether identifying metadata is dropped before posting. */
    val stripMetadata: Boolean = true,
    /** What to do when the selection is longer than the client allows. */
    val overflowAction: StatusAudioOverflowAction = StatusAudioOverflowAction.ASK,
)

/** The fixed bounds the planner works with, collected so they are visible in one place. */
object StatusAudioLimits {
    /** A part shorter than this is not worth posting. */
    const val MIN_SEGMENT_MILLIS: Long = 1_000L

    /** The most parts a single source may be split into before the plan is refused. */
    const val MAX_SEGMENTS: Int = 10
}

/** One part of the plan: what to post, and when it starts and ends in the source. */
data class StatusAudioSegment(
    val index: Int,
    val total: Int,
    val startMillis: Long,
    val endMillis: Long,
    val label: String,
) {
    /** Length of this part. */
    val durationMillis: Long get() = endMillis - startMillis
}

/** What the planner decided to do with the source. */
enum class StatusAudioPlanKind {
    /** Post the selection unchanged. */
    POST_AS_IS,

    /** Post a trimmed selection. */
    TRIM,

    /** Post several numbered parts. */
    SPLIT,

    /** The selection is too long and the user has to choose what to do. */
    REQUIRES_DECISION,

    /** Nothing can be posted, for the reason in the plan. */
    REJECT,
}

/** Why a source cannot be posted, with the sentence to show. */
data class StatusAudioRejection(
    val code: String,
    val explanation: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$code: $explanation"
}

/** The rejection reasons, each phrased the way the editor shows it. */
object StatusAudioProblems {
    val UNKNOWN_CONTAINER =
        StatusAudioRejection(
            "unknown_container",
            "This file's format could not be recognised, so it cannot be prepared for a voice Status.",
        )

    val UNSUPPORTED_CONTAINER =
        StatusAudioRejection(
            "unsupported_container",
            "This WhatsApp version cannot post this audio format as a voice Status.",
        )

    val UNKNOWN_DURATION =
        StatusAudioRejection(
            "unknown_duration",
            "The audio's length could not be read, so it cannot be split safely.",
        )

    val TOO_SHORT =
        StatusAudioRejection(
            "too_short",
            "The selected range is shorter than a second, which is too short to post.",
        )

    val CANCELLED =
        StatusAudioRejection(
            "cancelled",
            "Nothing was prepared for posting.",
        )

    /** The source would need more parts than [StatusAudioLimits.MAX_SEGMENTS]. */
    fun tooManySegments(max: Int): StatusAudioRejection =
        StatusAudioRejection(
            "too_many_segments",
            "The file would need more than $max voice Status parts. Select a shorter range.",
        )
}

/**
 * The complete preparation plan for one source.
 *
 * A plan is a value, not an action: the editor can show exactly what will be posted — how many
 * parts, where each begins and ends, what metadata is dropped — before anything is written to
 * disk. [accepted] is false only for [StatusAudioPlanKind.REJECT], which is the one outcome the
 * caller must not treat as "post anyway".
 */
data class StatusAudioPlan(
    val kind: StatusAudioPlanKind,
    val segments: List<StatusAudioSegment>,
    val rejection: StatusAudioRejection?,
    val warnings: List<String>,
    val strippedFields: Set<String>,
    val transcodedFrom: AudioContainer?,
    /** The limit the plan was built against, after any fallback. */
    val limitMillis: Long,
) {
    /** Whether anything may be posted. */
    val accepted: Boolean get() = kind != StatusAudioPlanKind.REJECT

    /** Whether the user has to choose how to handle the overflow before posting. */
    val requiresDecision: Boolean get() = kind == StatusAudioPlanKind.REQUIRES_DECISION

    /** Whether the source has to be converted before it can be posted. */
    val needsTranscode: Boolean get() = transcodedFrom != null

    /** Total length of everything that will be posted. */
    val totalMillis: Long get() = segments.sumOf { it.durationMillis }

    /** One line for diagnostics. */
    fun toDisplayLine(): String =
        when (kind) {
            StatusAudioPlanKind.REJECT -> "rejected: ${rejection?.code ?: "unknown"}"
            StatusAudioPlanKind.REQUIRES_DECISION -> "waiting for a trim-or-split choice"
            else -> "${kind.name.lowercase()}: ${segments.size} part(s), ${totalMillis}ms"
        }
}

/**
 * Turns a chosen audio file into a plan for a voice Status.
 *
 * The planner is pure: it reads a value describing the file and a value describing what the
 * client supports, and returns what should be posted. That is what lets every boundary — an
 * unsupported container, an unreadable length, a selection exactly at the limit, a file that
 * would need more parts than the cap allows — be tested without a device, and it is why the
 * limit is a parameter rather than a constant.
 *
 * It never returns a plan that would post something the user did not ask for: a selection past
 * the limit becomes a decision, a trim or a numbered series, and a source that cannot be handled
 * becomes a rejection with a sentence rather than a best-effort post.
 */
object StatusAudioStudio {
    /** The metadata fields dropped when stripping is on. */
    @JvmField
    val STRIPPED_FIELDS: Set<String> = setOf("filesystem_path", "location", "author", "cover", "device", "comment")

    /** Builds the plan for [source]. */
    fun plan(
        source: StatusAudioSource,
        options: StatusAudioOptions = StatusAudioOptions(),
        capability: StatusAudioCapability = StatusAudioCapability.Unknown,
    ): StatusAudioPlan {
        val warnings = ArrayList<String>()
        val stripped = if (options.stripMetadata) STRIPPED_FIELDS else emptySet()
        if (!options.stripMetadata) {
            warnings.add("Metadata will be posted as it is" + if (source.hasLocationTag) ", including the location tag." else ".")
        }
        val container = source.container
        if (container == null) {
            return reject(StatusAudioProblems.UNKNOWN_CONTAINER, warnings, stripped, 0L)
        }
        val limit = resolveLimit(capability, warnings)
        // An empty container set means the client's formats could not be read, not that it
        // supports nothing: the same unknown capability that falls back for the limit also
        // falls back here, and the warning recorded above says so.
        if (capability.supportedContainers.isNotEmpty() && container !in capability.supportedContainers) {
            return reject(StatusAudioProblems.UNSUPPORTED_CONTAINER, warnings, stripped, limit)
        }
        if (source.durationMillis <= 0L) {
            return reject(StatusAudioProblems.UNKNOWN_DURATION, warnings, stripped, limit)
        }

        val start = options.trimStartMillis.coerceIn(0L, source.durationMillis)
        val end = (options.trimEndMillis ?: source.durationMillis).coerceIn(start, source.durationMillis)
        if (end - start < StatusAudioLimits.MIN_SEGMENT_MILLIS) {
            return reject(StatusAudioProblems.TOO_SHORT, warnings, stripped, limit)
        }

        val preparation =
            Preparation(
                warnings = warnings,
                stripped = stripped,
                transcodedFrom = if (container.nativeVoiceStatus) null else container,
                limit = limit,
                start = start,
                end = end,
            )
        recordWarnings(options, preparation)
        return if (preparation.effective <= limit) {
            postAsOne(source, options, preparation)
        } else {
            overLimit(options, preparation)
        }
    }

    /**
     * Records what the user has to be told about the plan that follows.
     *
     * None of these change the plan; each one is a consequence of the options the user chose, so
     * they are collected once here rather than repeated in every branch that builds one.
     */
    private fun recordWarnings(
        options: StatusAudioOptions,
        preparation: Preparation,
    ) {
        val transcodedFrom = preparation.transcodedFrom
        if (transcodedFrom != null) {
            preparation.warnings.add("${transcodedFrom.label} is converted before posting.")
        }
        if (options.fadeInMillis + options.fadeOutMillis >= preparation.effective) {
            preparation.warnings.add("The fades cover the whole selection, so they cancel each other out.")
        }
        if (options.volumePercent !in 0..200) {
            preparation.warnings.add("The volume is outside the supported range and will be clamped.")
        }
    }

    /** The plan for a selection that already fits the client's limit. */
    private fun postAsOne(
        source: StatusAudioSource,
        options: StatusAudioOptions,
        preparation: Preparation,
    ): StatusAudioPlan {
        val kind =
            if (preparation.start > 0L || preparation.end < source.durationMillis) {
                StatusAudioPlanKind.TRIM
            } else {
                StatusAudioPlanKind.POST_AS_IS
            }
        val segment = StatusAudioSegment(1, 1, preparation.start, preparation.end, labelFor(1, 1, options.addNumbering))
        return preparation.toPlan(kind, listOf(segment))
    }

    /** The plan for a selection longer than the client's limit. */
    private fun overLimit(
        options: StatusAudioOptions,
        preparation: Preparation,
    ): StatusAudioPlan {
        val limit = preparation.limit
        val overflow = "The selection is ${preparation.effective}ms and this client posts up to ${limit}ms."
        return when (options.overflowAction) {
            StatusAudioOverflowAction.ASK -> {
                preparation.warnings.add("$overflow Choose to trim it or post it as parts.")
                preparation.toPlan(StatusAudioPlanKind.REQUIRES_DECISION, emptyList())
            }

            StatusAudioOverflowAction.TRIM -> {
                preparation.warnings.add("$overflow The rest of the selection is left out.")
                val segment =
                    StatusAudioSegment(
                        1,
                        1,
                        preparation.start,
                        preparation.start + limit,
                        labelFor(1, 1, options.addNumbering),
                    )
                preparation.toPlan(StatusAudioPlanKind.TRIM, listOf(segment))
            }

            StatusAudioOverflowAction.AUTO_SPLIT -> {
                split(options, preparation)
            }

            StatusAudioOverflowAction.CANCEL -> {
                reject(StatusAudioProblems.CANCELLED, preparation.warnings, preparation.stripped, limit)
            }
        }
    }

    /** The limit to plan against, recording a warning when the fallback is used. */
    private fun resolveLimit(
        capability: StatusAudioCapability,
        warnings: MutableList<String>,
    ): Long =
        if (capability.limitResolved) {
            capability.maxVoiceStatusMillis
        } else {
            warnings.add(
                "This client's voice Status limit could not be read; using the " +
                    "${StatusAudioCapability.FALLBACK_MAX_MILLIS}ms compatibility fallback.",
            )
            StatusAudioCapability.FALLBACK_MAX_MILLIS
        }

    /** Splits the range into consecutive parts that each fit the limit. */
    private fun split(
        options: StatusAudioOptions,
        preparation: Preparation,
    ): StatusAudioPlan {
        val warnings = preparation.warnings
        val limit = preparation.limit
        val start = preparation.start
        val end = preparation.end
        val total = ((preparation.effective + limit - 1) / limit).toInt()
        if (total > StatusAudioLimits.MAX_SEGMENTS) {
            return reject(
                StatusAudioProblems.tooManySegments(StatusAudioLimits.MAX_SEGMENTS),
                warnings,
                preparation.stripped,
                limit,
            )
        }
        val segments = ArrayList<StatusAudioSegment>(total)
        for (index in 0 until total) {
            val partStart = start + index.toLong() * limit
            val partEnd = (partStart + limit).coerceAtMost(end)
            segments.add(
                StatusAudioSegment(
                    index = index + 1,
                    total = total,
                    startMillis = partStart,
                    endMillis = partEnd,
                    label = labelFor(index + 1, total, options.addNumbering),
                ),
            )
        }
        warnings.add("The parts are posted in order, $total in total.")
        return preparation.toPlan(StatusAudioPlanKind.SPLIT, segments)
    }

    /** Builds a rejection plan with everything the caller needs to explain it. */
    private fun reject(
        rejection: StatusAudioRejection,
        warnings: List<String>,
        stripped: Set<String>,
        limitMillis: Long,
    ): StatusAudioPlan =
        StatusAudioPlan(
            kind = StatusAudioPlanKind.REJECT,
            segments = emptyList(),
            rejection = rejection,
            warnings = warnings,
            strippedFields = stripped,
            transcodedFrom = null,
            limitMillis = limitMillis,
        )

    private fun labelFor(
        index: Int,
        total: Int,
        addNumbering: Boolean,
    ): String =
        when {
            total <= 1 -> "Whole clip"
            addNumbering -> "Part $index of $total"
            else -> "Part $index"
        }

    /**
     * The state a plan carries from the checks to the builder.
     *
     * It exists so the paths that build a plan take the plan's own state as one value rather than
     * seven positional arguments, which is what keeps those calls readable and what keeps each of
     * them under the complexity the static analysis allows.
     */
    private class Preparation(
        val warnings: MutableList<String>,
        val stripped: Set<String>,
        val transcodedFrom: AudioContainer?,
        val limit: Long,
        val start: Long,
        val end: Long,
    ) {
        /** How much of the selection the plan keeps. */
        val effective: Long get() = end - start

        /** Builds a plan from this state. Every path but a rejection carries all of it. */
        fun toPlan(
            kind: StatusAudioPlanKind,
            segments: List<StatusAudioSegment>,
        ): StatusAudioPlan = StatusAudioPlan(kind, segments, null, warnings, stripped, transcodedFrom, limit)
    }
}

/** Where a saved draft is in its life cycle. */
enum class StatusAudioDraftState {
    /** Being edited; nothing has been written for posting. */
    EDITING,

    /** Prepared and ready to post. */
    READY,

    /** A post is in flight. */
    POSTING,

    /** The last post failed; [StatusAudioDraft.failureCode] says why. */
    FAILED,
}

/**
 * A Status Audio Studio draft.
 *
 * The draft exists for the acceptance criterion that the flow survives process recreation: the
 * WhatsApp process can be killed while the editor is open, and the source reference and the
 * user's choices are read back rather than lost. It deliberately holds a content URI and a
 * display name, never the original filesystem path, so restoring a draft does not depend on
 * where the file used to be and does not leak where it was.
 */
data class StatusAudioDraft(
    val id: String,
    val target: TargetApp,
    val sourceUri: String,
    val container: AudioContainer?,
    val durationMillis: Long,
    val options: StatusAudioOptions,
    val state: StatusAudioDraftState = StatusAudioDraftState.EDITING,
    val failureCode: String? = null,
    val createdAtMillis: Long = 0L,
)

/** Encodes and decodes drafts. Total in both directions, as a stored draft must be. */
object StatusAudioDraftCodec {
    /** Serialises [draft] to the stored form. */
    fun encode(draft: StatusAudioDraft): String =
        MiniJson.write(
            jsonObject(
                "id" to jsonString(draft.id),
                "target" to jsonString(draft.target.code),
                "uri" to jsonString(draft.sourceUri),
                "container" to draft.container?.let { jsonString(it.name) },
                "duration" to jsonNumber(draft.durationMillis),
                "state" to jsonString(draft.state.name),
                "failure" to draft.failureCode?.let { jsonString(it) },
                "createdAt" to jsonNumber(draft.createdAtMillis),
                "options" to encodeOptions(draft.options),
            ),
        )

    /** Reads a draft back, or null when the stored value is not a usable draft. */
    fun decode(text: String): StatusAudioDraft? {
        val fields = MiniJson.parse(text)?.objOrNull() ?: return null
        val id = fields.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val target = fields.string("target")?.let { TargetApp.fromCode(it) } ?: return null
        val uri = fields.string("uri")?.takeIf { it.isNotBlank() } ?: return null
        val containerName = fields.string("container")
        return StatusAudioDraft(
            id = id,
            target = target,
            sourceUri = uri,
            container = containerName?.let { name -> AudioContainer.entries.firstOrNull { it.name == name } },
            durationMillis = fields.long("duration") ?: 0L,
            options = decodeOptions(fields.obj("options")),
            state =
                fields
                    .string("state")
                    ?.let { name -> StatusAudioDraftState.entries.firstOrNull { it.name == name } }
                    ?: StatusAudioDraftState.EDITING,
            failureCode = fields.string("failure"),
            createdAtMillis = fields.long("createdAt") ?: 0L,
        )
    }

    private fun encodeOptions(options: StatusAudioOptions): JsonValue.Obj =
        jsonObject(
            "trimStart" to jsonNumber(options.trimStartMillis),
            "trimEnd" to options.trimEndMillis?.let { jsonNumber(it) },
            "fadeIn" to jsonNumber(options.fadeInMillis),
            "fadeOut" to jsonNumber(options.fadeOutMillis),
            "volume" to jsonNumber(options.volumePercent.toLong()),
            "normalize" to jsonBoolean(options.normalize),
            "numbering" to jsonBoolean(options.addNumbering),
            "stripMetadata" to jsonBoolean(options.stripMetadata),
            "overflow" to jsonString(options.overflowAction.name),
        )

    /**
     * Reads the options back.
     *
     * An unreadable options object decodes to the defaults, which are the safe ones: nothing
     * trimmed, metadata stripped, overflow asking rather than guessing.
     */
    private fun decodeOptions(fields: Map<String, JsonValue>?): StatusAudioOptions {
        if (fields == null) return StatusAudioOptions()
        return StatusAudioOptions(
            trimStartMillis = fields.long("trimStart") ?: 0L,
            trimEndMillis = fields.long("trimEnd"),
            fadeInMillis = fields.long("fadeIn") ?: 0L,
            fadeOutMillis = fields.long("fadeOut") ?: 0L,
            volumePercent = (fields.long("volume") ?: 100L).toInt(),
            normalize = fields.boolean("normalize") ?: false,
            addNumbering = fields.boolean("numbering") ?: true,
            stripMetadata = fields.boolean("stripMetadata") ?: true,
            overflowAction =
                fields
                    .string("overflow")
                    ?.let { name -> StatusAudioOverflowAction.entries.firstOrNull { it.name == name } }
                    ?: StatusAudioOverflowAction.ASK,
        )
    }
}

/**
 * Stores drafts so the editor survives the process being killed.
 *
 * One key per draft, keyed by the draft's own id rather than by target, so the same source can
 * be edited for two targets at once without them overwriting each other. A draft that cannot be
 * decoded is skipped rather than surfaced as a broken row.
 */
class StatusAudioDraftStore(
    private val store: KeyValueStore,
) {
    /** Writes [draft]. */
    fun save(draft: StatusAudioDraft) {
        store.putString(keyFor(draft.id), StatusAudioDraftCodec.encode(draft))
    }

    /** The draft with [id], or null. */
    fun get(id: String): StatusAudioDraft? = store.getString(keyFor(id))?.let { StatusAudioDraftCodec.decode(it) }

    /** Every readable draft, newest first. */
    fun all(): List<StatusAudioDraft> =
        store
            .keys(KEY_PREFIX)
            .mapNotNull { key -> store.getString(key)?.let { StatusAudioDraftCodec.decode(it) } }
            .sortedByDescending { it.createdAtMillis }

    /** Removes a draft. */
    fun remove(id: String): Boolean {
        val key = keyFor(id)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** Removes every draft. Used by tests and by a factory reset. */
    fun clear() {
        store.keys(KEY_PREFIX).forEach { store.remove(it) }
    }

    private fun keyFor(id: String): String = KEY_PREFIX + id

    companion object {
        /** Prefix for every stored Status Audio Studio draft. */
        const val KEY_PREFIX: String = "wae.status.audio.draft."
    }
}
