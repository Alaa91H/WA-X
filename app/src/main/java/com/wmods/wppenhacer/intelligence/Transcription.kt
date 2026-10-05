package com.wmods.wppenhacer.intelligence

import com.wmods.wppenhacer.platform.JsonValue
import com.wmods.wppenhacer.platform.KeyValueStore
import com.wmods.wppenhacer.platform.MiniJson
import com.wmods.wppenhacer.platform.array
import com.wmods.wppenhacer.platform.jsonArray
import com.wmods.wppenhacer.platform.jsonNumber
import com.wmods.wppenhacer.platform.jsonObject
import com.wmods.wppenhacer.platform.jsonString
import com.wmods.wppenhacer.platform.long
import com.wmods.wppenhacer.platform.string
import java.security.MessageDigest

/** Where transcription runs. */
enum class TranscriptionEngine {
    /** On-device. */
    LOCAL,

    /** An external service; the privacy gate must allow it. */
    CLOUD,
}

/** One piece of audio to transcribe. */
data class TranscriptionRequest(
    val mediaId: String,
    val mediaFingerprint: String,
    val languageHint: String? = null,
)

/** One timed transcript segment. */
data class TranscriptSegment(
    val startMillis: Long,
    val endMillis: Long,
    val text: String,
) {
    /** `[mm:ss] text`, the form the UI shows next to playback. */
    fun render(): String = "[${format(startMillis)}] $text"

    private fun format(millis: Long): String {
        val totalSeconds = millis / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}

/** A completed transcript. */
data class Transcript(
    val mediaId: String,
    val mediaFingerprint: String,
    val language: String?,
    val engine: TranscriptionEngine,
    val segments: List<TranscriptSegment>,
    val createdAtMillis: Long,
) {
    /** The whole transcript as one block of text. */
    val fullText: String get() = segments.joinToString(" ") { it.text }

    /** Segments containing [query], case-insensitively. */
    fun search(query: String): List<TranscriptSegment> {
        if (query.isBlank()) return emptyList()
        return segments.filter { it.text.contains(query, ignoreCase = true) }
    }

    /** The timestamped rendering. */
    fun renderWithTimestamps(): String = segments.joinToString("\n") { it.render() }

    /** One line for diagnostics; contains no transcript content. */
    fun toDisplayLine(): String = "transcript: ${segments.size} segment(s), engine ${engine.name.lowercase()}"
}

/** What happened when transcription was requested. */
sealed interface TranscriptionOutcome {
    /** A transcript is available. */
    data class Transcribed(
        val transcript: Transcript,
    ) : TranscriptionOutcome

    /** No usable provider, optionally because cloud use is not opted in. */
    data class Unavailable(
        val reason: String,
        val cloudBlocked: Boolean = false,
    ) : TranscriptionOutcome

    /** The provider ran and failed. */
    data class Failed(
        val reason: String,
    ) : TranscriptionOutcome
}

/**
 * A transcription backend.
 *
 * Like the translation provider, implementations report ordinary failures as outcomes and
 * keep throwing for genuine faults. The engine type is part of the contract because the
 * privacy gate keys off it.
 */
interface TranscriptionProvider {
    /** Which engine this implements. */
    val engine: TranscriptionEngine

    /** Whether audio would leave the device. */
    val isCloud: Boolean

    /** A user-facing name. */
    val displayName: String

    /** Transcribes [request]. */
    fun transcribe(request: TranscriptionRequest): TranscriptionOutcome
}

/** Keeps the available transcription providers. */
class TranscriptionProviderRegistry {
    private val providers = LinkedHashMap<TranscriptionEngine, TranscriptionProvider>()

    /** Registers (or replaces) a provider. */
    fun register(provider: TranscriptionProvider) {
        providers[provider.engine] = provider
    }

    /** The on-device provider, or null. */
    fun local(): TranscriptionProvider? = providers[TranscriptionEngine.LOCAL]

    /** Cloud providers, in registration order. */
    fun cloud(): List<TranscriptionProvider> = providers.values.filter { it.isCloud }

    /** Every registered provider. */
    fun all(): List<TranscriptionProvider> = providers.values.toList()

    /** Drops every registration. Used by tests. */
    fun clear() {
        providers.clear()
    }
}

/**
 * Bounded, self-invalidating transcript cache.
 *
 * Entries are keyed by media id and store the media fingerprint they were produced from.
 * Reading an entry whose fingerprint no longer matches deletes it and reports a miss — that
 * is the "automatic invalidation" of T112: a re-recorded or replaced voice note never
 * serves a stale transcript, without the caller having to remember to invalidate.
 */
class TranscriptCache(
    private val store: KeyValueStore,
    private val maxEntries: Int = 200,
    private val maxAgeMillis: Long = 30L * 24L * 60L * 60L * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The cached transcript for a media item, or null. Removes stale entries. */
    fun get(
        mediaId: String,
        mediaFingerprint: String,
        nowMillis: Long = now(),
    ): Transcript? {
        if (mediaId.isBlank()) return null
        val key = keyFor(mediaId)
        val stored = store.getString(key) ?: return null
        val transcript =
            decode(MiniJson.parse(stored)) ?: run {
                store.remove(key)
                return null
            }
        val stale =
            transcript.mediaFingerprint != mediaFingerprint ||
                (nowMillis - transcript.createdAtMillis) > maxAgeMillis
        if (stale) {
            store.remove(key)
            return null
        }
        return transcript
    }

    /** Stores a transcript, pruning when the bound is exceeded. */
    fun put(transcript: Transcript) {
        store.putString(keyFor(transcript.mediaId), MiniJson.write(encode(transcript)))
        prune()
    }

    /** Deletes one transcript (the manual delete control). */
    fun remove(mediaId: String): Boolean {
        val key = keyFor(mediaId)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** The number of cached transcripts. */
    fun size(): Int = store.keys(KEY_ENTRY).size

    /** Drops expired and excess entries; returns how many were removed. */
    fun prune(nowMillis: Long = now()): Int {
        val keys = store.keys(KEY_ENTRY).toList()
        var removed = 0
        val surviving = ArrayList<String>()
        keys.forEach { key ->
            val transcript = decode(MiniJson.parse(store.getString(key)))
            val expired = transcript == null || (nowMillis - transcript.createdAtMillis) > maxAgeMillis
            if (expired) {
                store.remove(key)
                removed++
            } else {
                surviving.add(key)
            }
        }
        if (surviving.size > maxEntries) {
            val excess = surviving.size - maxEntries
            surviving.take(excess).forEach {
                store.remove(it)
                removed++
            }
        }
        return removed
    }

    /** Deletes every transcript. */
    fun clear() {
        store.keys(KEY_ENTRY).forEach { store.remove(it) }
    }

    private fun keyFor(mediaId: String): String =
        KEY_ENTRY +
            MessageDigest
                .getInstance("SHA-256")
                .digest(mediaId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)

    private fun encode(transcript: Transcript): JsonValue.Obj =
        jsonObject(
            "mediaId" to jsonString(transcript.mediaId),
            "fingerprint" to jsonString(transcript.mediaFingerprint),
            "language" to transcript.language?.let { jsonString(it) },
            "engine" to jsonString(transcript.engine.name),
            "createdAt" to jsonNumber(transcript.createdAtMillis),
            "segments" to
                jsonArray(
                    transcript.segments.map { segment ->
                        jsonObject(
                            "start" to jsonNumber(segment.startMillis),
                            "end" to jsonNumber(segment.endMillis),
                            "text" to jsonString(segment.text),
                        )
                    },
                ),
        )

    private fun decode(value: JsonValue?): Transcript? {
        val fields = (value as? JsonValue.Obj)?.fields ?: return null
        val mediaId = fields.string("mediaId") ?: return null
        val fingerprint = fields.string("fingerprint") ?: return null
        val segments =
            fields.array("segments").orEmpty().mapNotNull { segment ->
                val segmentFields = (segment as? JsonValue.Obj)?.fields ?: return@mapNotNull null
                TranscriptSegment(
                    startMillis = segmentFields.long("start") ?: 0L,
                    endMillis = segmentFields.long("end") ?: 0L,
                    text = segmentFields.string("text") ?: return@mapNotNull null,
                )
            }
        return Transcript(
            mediaId = mediaId,
            mediaFingerprint = fingerprint,
            language = fields.string("language"),
            engine =
                TranscriptionEngine.entries.firstOrNull { it.name == fields.string("engine") }
                    ?: TranscriptionEngine.LOCAL,
            segments = segments,
            createdAtMillis = fields.long("createdAt") ?: 0L,
        )
    }

    companion object {
        private const val KEY_ENTRY = "wae.intelligence.transcript."
    }
}

/**
 * Runs transcription with the cache in front and the privacy gate around the cloud.
 *
 * The order is: cache, then local provider, then an opted-in cloud provider. A cloud
 * provider that is not opted in produces `cloudBlocked = true` with a sentence naming the
 * audio as the data that would be sent, so the UI can offer an informed opt-in rather than
 * a silent failure.
 */
class TranscriptionCoordinator(
    private val registry: TranscriptionProviderRegistry,
    private val cache: TranscriptCache,
    private val gate: CloudPrivacyGate,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Transcribes a voice message, using the cache when possible. */
    fun transcribe(
        mediaId: String,
        mediaFingerprint: String,
        languageHint: String? = null,
        preferLocal: Boolean = true,
    ): TranscriptionOutcome {
        cache.get(mediaId, mediaFingerprint, now())?.let {
            return TranscriptionOutcome.Transcribed(it)
        }
        val request = TranscriptionRequest(mediaId, mediaFingerprint, languageHint)

        if (preferLocal) {
            registry.local()?.let { local ->
                when (val outcome = local.transcribe(request)) {
                    is TranscriptionOutcome.Transcribed -> {
                        cache.put(outcome.transcript)
                        return outcome
                    }

                    is TranscriptionOutcome.Failed -> Unit
                    is TranscriptionOutcome.Unavailable -> Unit
                }
            }
        }

        val cloud = registry.cloud()
        val allowed = cloud.filter { gate.allows(CloudService.TRANSCRIPTION_CLOUD, CloudDataKind.VOICE_AUDIO) }
        for (provider in allowed) {
            when (val outcome = provider.transcribe(request)) {
                is TranscriptionOutcome.Transcribed -> {
                    cache.put(outcome.transcript)
                    return outcome
                }

                is TranscriptionOutcome.Failed -> Unit
                is TranscriptionOutcome.Unavailable -> Unit
            }
        }

        return if (cloud.isNotEmpty() && allowed.isEmpty()) {
            TranscriptionOutcome.Unavailable(
                reason = gate.describe(CloudService.TRANSCRIPTION_CLOUD, CloudDataKind.VOICE_AUDIO),
                cloudBlocked = true,
            )
        } else {
            TranscriptionOutcome.Unavailable("No transcription provider could handle this voice message.")
        }
    }

    /** Searches a cached transcript; returns an empty list when there is no transcript. */
    fun search(
        mediaId: String,
        mediaFingerprint: String,
        query: String,
    ): List<TranscriptSegment> = cache.get(mediaId, mediaFingerprint, now())?.search(query).orEmpty()

    /** The disclosure shown before cloud transcription would run. */
    fun cloudNotice(): String = gate.describe(CloudService.TRANSCRIPTION_CLOUD, CloudDataKind.VOICE_AUDIO)
}
