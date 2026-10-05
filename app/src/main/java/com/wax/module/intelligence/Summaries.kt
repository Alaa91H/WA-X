package com.wax.module.intelligence

/**
 * A summary request covering exactly the messages the user selected.
 *
 * The model carries the selected texts explicitly. There is deliberately no way to ask for
 * "the last N messages of a chat" here: the roadmap requires that only user-selected ranges
 * are summarised, and a request that cannot express anything else is the strongest form of
 * that guarantee. The caller assembles the range; the summariser never reaches into a chat
 * on its own.
 */
data class SummaryRequest(
    val chatId: String,
    val messages: List<String>,
    val language: String? = null,
) {
    /** How many messages the request covers. */
    val messageCount: Int get() = messages.size

    /** One line for diagnostics; never includes message content. */
    fun toDisplayLine(): String = "summary of $messageCount selected message(s)"
}

/** What happened when a summary was requested. */
sealed interface SummaryOutcome {
    /** The summary was produced. */
    data class Summarized(
        val text: String,
        val cloud: Boolean,
    ) : SummaryOutcome

    /** No provider, or cloud use not opted in. */
    data class Unavailable(
        val reason: String,
        val cloudBlocked: Boolean = false,
    ) : SummaryOutcome

    /** The provider ran and failed. */
    data class Failed(
        val reason: String,
    ) : SummaryOutcome
}

/** A summary backend. */
interface SummaryProvider {
    /** Whether selected messages would leave the device. */
    val isCloud: Boolean

    /** A user-facing name. */
    val displayName: String

    /** Summarises [request]. */
    fun summarize(request: SummaryRequest): SummaryOutcome
}

/**
 * Runs summarisation under the privacy gate.
 *
 * The gate check happens before the provider is called, so a cloud provider that is not
 * opted in cannot receive a single message even transiently. The blocked outcome names the
 * data ("the selected messages") so the consent dialog is honest about what it would send.
 */
class ConversationSummarizer(
    private val provider: SummaryProvider,
    private val gate: CloudPrivacyGate,
) {
    /** Summarises the selected messages. */
    fun summarize(request: SummaryRequest): SummaryOutcome {
        if (request.messages.isEmpty()) {
            return SummaryOutcome.Failed("Select at least one message to summarise.")
        }
        if (provider.isCloud && !gate.allows(CloudService.SUMMARY_CLOUD, CloudDataKind.CONVERSATION_RANGE)) {
            return SummaryOutcome.Unavailable(
                reason = gate.describe(CloudService.SUMMARY_CLOUD, CloudDataKind.CONVERSATION_RANGE),
                cloudBlocked = true,
            )
        }
        return try {
            provider.summarize(request)
        } catch (error: Throwable) {
            SummaryOutcome.Failed("summarisation failed: ${error.javaClass.simpleName}")
        }
    }

    /** The disclosure shown before a cloud summary would run. */
    fun cloudNotice(): String = gate.describe(CloudService.SUMMARY_CLOUD, CloudDataKind.CONVERSATION_RANGE)
}
