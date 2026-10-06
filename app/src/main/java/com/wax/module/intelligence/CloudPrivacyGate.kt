package com.wax.module.intelligence

import com.wax.module.platform.KeyValueStore

/** The external services the intelligence features can use. */
enum class CloudService(
    val displayName: String,
) {
    TRANSLATION_GOOGLE("Google Translate"),
    TRANSLATION_DEEPL("DeepL"),
    TRANSLATION_CUSTOM("the configured translation provider"),
    TRANSCRIPTION_CLOUD("cloud transcription"),
    SUMMARY_CLOUD("cloud summarisation"),
}

/** The kind of data a request would expose, described in user terms. */
enum class CloudDataKind(
    val dataDescription: String,
) {
    MESSAGE_TEXT("the message text"),
    VOICE_AUDIO("the voice message audio"),
    CONVERSATION_RANGE("the selected messages"),
}

/** Maps a translation engine to the service the gate knows. */
fun TranslationEngine.cloudService(): CloudService? =
    when (this) {
        TranslationEngine.LOCAL -> null
        TranslationEngine.GOOGLE -> CloudService.TRANSLATION_GOOGLE
        TranslationEngine.DEEPL -> CloudService.TRANSLATION_DEEPL
        TranslationEngine.CUSTOM -> CloudService.TRANSLATION_CUSTOM
    }

/**
 * The explicit opt-in layer for every cloud feature.
 *
 * The policy is "local processing preferred, cloud processing disabled unless explicitly
 * enabled by the user", and this class is where "explicitly" is enforced. Defaults are
 * closed: a service that was never enabled returns false, so no code path can accidentally
 * opt the user in.
 *
 * Disclosure is a separate method ([describe]) rather than a log line, because the UI must
 * be able to say exactly what would be sent and to which service *before* the user agrees.
 * That is also what makes the audio case (T113) impossible to miss: sending voice audio is
 * described as audio, not as "processing".
 */
class CloudPrivacyGate(
    private val store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Whether [service] was explicitly enabled. False by default. */
    fun isEnabled(service: CloudService): Boolean = store.getBoolean(keyFor(service), false)

    /** Enables or disables [service], recording when it was first enabled. */
    fun setEnabled(
        service: CloudService,
        enabled: Boolean,
    ) {
        store.putBoolean(keyFor(service), enabled)
        if (enabled) {
            if (store.getLong(enabledAtKey(service)) == 0L) {
                store.putLong(enabledAtKey(service), now())
            }
        } else {
            store.remove(enabledAtKey(service))
        }
    }

    /** Every enabled service. */
    fun enabledServices(): Set<CloudService> = CloudService.entries.filter { isEnabled(it) }.toSet()

    /** When [service] was enabled, or 0 when it never was or is disabled. */
    fun enabledAt(service: CloudService): Long = if (isEnabled(service)) store.getLong(enabledAtKey(service)) else 0L

    /**
     * Whether using [service] for [data] is currently allowed.
     *
     * Today the service switch is the only consent, and [data] is accepted so callers state
     * what they intend to send at the call site; a future per-data-kind consent (for example
     * audio separately from text) can be enforced here without touching every caller.
     */
    fun allows(
        service: CloudService,
        @Suppress("UNUSED_PARAMETER") data: CloudDataKind,
    ): Boolean = isEnabled(service)

    /**
     * The sentence shown before data would leave the device.
     *
     * Written as a full statement rather than a fragment so it can be shown verbatim in a
     * dialog or a settings summary without the caller assembling meaning.
     */
    fun describe(
        service: CloudService,
        data: CloudDataKind,
    ): String = "Using ${service.displayName} would send ${data.dataDescription} to an external service."

    /** The policy statement shown on the privacy screen. */
    val policyStatement: String = "Processing happens on this device. Cloud providers stay off until you enable them."

    /** Drops every decision. Used by tests and factory reset. */
    fun clear() {
        CloudService.entries.forEach { service ->
            store.remove(keyFor(service))
            store.remove(enabledAtKey(service))
        }
    }

    private fun keyFor(service: CloudService): String = "wae.intelligence.cloud.${service.name}"

    private fun enabledAtKey(service: CloudService): String = "wae.intelligence.cloud.${service.name}.enabled_at"
}
