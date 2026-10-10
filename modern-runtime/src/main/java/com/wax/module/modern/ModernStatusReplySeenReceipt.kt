package com.wax.module.modern

import java.util.concurrent.ConcurrentHashMap

/**
 * The Status reply seen-receipt exception (#357), the single owner of that rule.
 *
 * The request is narrow: a Status stays unseen when the user opens it, and
 * WhatsApp's **native** seen receipt for that exact Status goes out only after
 * the user really replied to it. This is not a global re-enable of Status
 * receipts, and it is deliberately not wired to the chat receipt path — a chat
 * read receipt and a Status seen receipt are different things on WhatsApp, and
 * substituting one for the other is exactly the failure this issue forbids.
 *
 * Four capabilities, because each step can independently fail and each failure
 * has to be distinguishable in a report:
 *
 * - [StatusSourceIdentityCapability] — which Status was actually opened.
 * - [StatusReplySendResultCapability] — whether a reply genuinely completed.
 * - [StatusSeenReceiptCapability] — whether the native receipt path resolved.
 * - [StatusReceiptStateCapability] — what was decided, for replay and audit.
 *
 * The rule the whole thing is built around: **nothing is sent unless the
 * native path resolved.** If it did not, the reply stays untouched, nothing is
 * fabricated, and the reason is reported. A user whose reply went through and
 * whose Status simply was not marked seen is in a better position than one
 * whose Status was marked seen by an invented protocol message.
 */
object ModernStatusReplySeenReceipt {
    const val FEATURE_ID = "status_reply_seen_receipt"

    /** Manager-side toggle, default off. */
    const val PREF_SEND_SEEN_ON_REPLY = "sendstatusseenonreply"

    /**
     * How long a decided operation is remembered.
     *
     * Bounded so a repeated send in the same conversation cannot replay a
     * receipt for a Status the user has long since scrolled past.
     */
    const val DEDUP_WINDOW_MILLIS = 30 * 60 * 1000L

    /** What was observed, or that the step could not answer. */
    enum class CapabilityState {
        AVAILABLE,
        NOT_OBSERVED,

        /** The step is not implemented on this runtime yet. */
        NOT_IMPLEMENTED,

        /** The step found more than one answer and refused to choose. */
        AMBIGUOUS,

        /** The step failed while running. */
        FAILED,
    }

    /**
     * The exact Status an operation belongs to.
     *
     * Every field matters. Two Status items from the same person differ only by
     * [statusId], and replying to B must never mark A — which is why a contact
     * alone is not an identity.
     */
    data class StatusIdentity(
        val targetPackage: String,
        val accountId: String,
        val statusId: String,
        val authorKey: String,
        val observedAtMillis: Long,
    ) {
        /** The idempotency key, so one Status is marked at most once. */
        fun dedupKey(): String = "$targetPackage|$accountId|$statusId|SEEN"

        fun isValid(): Boolean =
            targetPackage.isNotBlank() &&
                accountId.isNotBlank() &&
                statusId.isNotBlank() &&
                authorKey.isNotBlank()
    }

    /** The terminal outcome, recorded for audit and replay suppression. */
    enum class TerminalState {
        /** The native receipt went out for this exact Status. */
        SENT,

        /** A reply happened, but the native receipt path did not resolve. */
        SKIPPED_NO_NATIVE_PATH,

        /** The reply did not complete, so nothing was due. */
        NOT_APPLICABLE_REPLY_INCOMPLETE,

        /** This Status had already been decided inside the dedup window. */
        SUPPRESSED_DUPLICATE,

        /** Withheld because the toggle is off. */
        DISABLED,
    }

    data class Decision(
        val state: TerminalState,
        val reason: String,
        val identity: StatusIdentity?,
    )

    private val decided = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun decidedCount(): Int = decided.size

    @JvmStatic
    fun clearDecisions() {
        decided.clear()
    }

    @JvmStatic
    fun wasAlreadyDecided(
        identity: StatusIdentity,
        nowMillis: Long,
    ): Boolean {
        val at = decided[identity.dedupKey()] ?: return false
        if (nowMillis - at > DEDUP_WINDOW_MILLIS) {
            decided.remove(identity.dedupKey())
            return false
        }
        return true
    }

    /**
     * Decides what happens after a Status reply.
     *
     * The order is the contract, and it is the reason this is a pure function:
     * a duplicate is decided before anything else is attempted, the toggle is
     * checked before any work, an incomplete reply is never a trigger, and the
     * native path is checked before a receipt can be considered at all.
     */
    @JvmStatic
    fun decide(
        identity: StatusIdentity?,
        toggleOn: Boolean,
        replyCompleted: Boolean,
        identityState: CapabilityState,
        sendResultState: CapabilityState,
        nativeReceiptState: CapabilityState,
        nowMillis: Long,
    ): Decision {
        if (!toggleOn) {
            return Decision(TerminalState.DISABLED, "the switch is off", null)
        }
        if (identity == null || !identity.isValid()) {
            return Decision(
                TerminalState.SKIPPED_NO_NATIVE_PATH,
                "the Status could not be identified exactly",
                null,
            )
        }
        if (identityState != CapabilityState.AVAILABLE) {
            return Decision(
                TerminalState.SKIPPED_NO_NATIVE_PATH,
                "source identity: ${identityState.name}",
                identity,
            )
        }
        if (sendResultState != CapabilityState.AVAILABLE) {
            return Decision(
                TerminalState.NOT_APPLICABLE_REPLY_INCOMPLETE,
                "reply send result: ${sendResultState.name}",
                identity,
            )
        }
        if (!replyCompleted) {
            return Decision(
                TerminalState.NOT_APPLICABLE_REPLY_INCOMPLETE,
                "the reply did not complete",
                identity,
            )
        }
        if (nativeReceiptState != CapabilityState.AVAILABLE) {
            // The honest outcome. The reply stands; no Status is marked, and no
            // packet is invented to stand in for the missing path.
            return Decision(
                TerminalState.SKIPPED_NO_NATIVE_PATH,
                "native Status receipt path: ${nativeReceiptState.name}",
                identity,
            )
        }
        if (wasAlreadyDecided(identity, nowMillis)) {
            return Decision(
                TerminalState.SUPPRESSED_DUPLICATE,
                "this Status was already decided in this window",
                identity,
            )
        }
        decided[identity.dedupKey()] = nowMillis
        return Decision(TerminalState.SENT, "native receipt sent for the exact Status", identity)
    }

    /**
     * Records a receipt that was sent, so a replay cannot send it twice.
     *
     * Kept separate from [decide] because the sending itself happens at the
     * native boundary: this only remembers what was already done.
     */
    @JvmStatic
    fun recordSent(
        identity: StatusIdentity,
        nowMillis: Long,
    ) {
        decided[identity.dedupKey()] = nowMillis
    }

    /**
     * Forgets decisions older than the window.
     *
     * Bounded work on the module bootstrap, which is the only place this runs.
     */
    @JvmStatic
    fun purgeOlderThan(nowMillis: Long): Int {
        val iterator = decided.entries.iterator()
        var dropped = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis - entry.value > DEDUP_WINDOW_MILLIS) {
                iterator.remove()
                dropped++
            }
        }
        return dropped
    }
}
