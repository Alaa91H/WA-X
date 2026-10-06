package com.wax.module.outgoing

import com.wax.module.platform.ChatKind
import com.wax.module.platform.TargetApp

/**
 * One outgoing send, described in the terms the policies speak.
 *
 * Every sender path — manual send, scheduled send, template send, auto reply, an automation
 * action — builds this and asks the engine. That is what keeps the rules in one place: a
 * telegram-style "resolve it at each call site" approach is how timed deletion ends up
 * applied to manually typed messages but not to scheduled ones.
 *
 * [chatId] is whatever stable internal identifier the caller has. It is never used for
 * display and never stored with message content; the revocation queue keeps only the
 * identifier it needs to address the deletion.
 */
data class OutgoingSendRequest(
    /** The hooked application the send happens in. Policies never cross this. */
    val app: TargetApp,
    /** The chat the message goes to. */
    val chatId: String,
    /** Whether the chat is a direct conversation or a group. */
    val kind: ChatKind,
    /** The resolved class of the outgoing item, after any editing or transcoding. */
    val messageClass: OutgoingMessageClass,
    /** The native List (folder) the chat is in, when the client exposes one. */
    val listId: String? = null,
    /** The account the send is from, when the client exposes account identity. */
    val accountId: String? = null,
    /** A one-time View Once choice made for this send only. */
    val perMessageViewOnce: ViewOnceChoice? = null,
    /** A one-time auto-delete choice made for this send only. */
    val perMessageAutoDelete: AutoDeleteChoice? = null,
    /** The one-time delay that goes with [perMessageAutoDelete]. */
    val perMessageAutoDeleteMillis: Long? = null,
    /**
     * The native `1` control's state, when the user touched it.
     *
     * Null means untouched, which is not the same as off: an untouched control has to give way
     * to the stored policy, while an explicit off is the user overriding it for this message.
     * That distinction is the whole reason this is nullable.
     */
    val nativeViewOnceControl: Boolean? = null,
    /**
     * Whether this send carries a View Once requirement from a scheduled item.
     *
     * Changes what happens when the capability is missing: a requirement fails the send with
     * a notification, where a preference-only block can be offered as a manual retry. In both
     * cases the media is not silently sent as normal.
     */
    val explicitViewOnceRequired: Boolean = false,
)

/** Why a View Once decision could not be applied. */
enum class ViewOnceBlockReason(
    /** The sentence to display. Never mentions a paid tier. */
    val explanation: String,
) {
    /** The class cannot use native View Once on this client. */
    CLASS_NOT_SUPPORTED("This message type cannot be sent as View Once."),

    /** The class can, but the installed version does not expose the capability. */
    MISSING_CAPABILITY("This WhatsApp version does not offer View Once for this message type."),

    /** The policy asks every time. */
    AWAITING_CONFIRMATION("Confirm View Once for this message before sending."),
}

/** Why a timed revocation was not scheduled. */
enum class AutoDeleteBlockReason(
    /** The sentence to display. */
    val explanation: String,
) {
    /** The policy says enabled but carries no usable delay. */
    MALFORMED_POLICY("The auto-delete policy is incomplete, so nothing was scheduled."),

    /** The class is not in the selected set for this scope. */
    CLASS_NOT_SELECTED("This message type is not selected for auto delete."),

    /** The client cannot revoke this at all. */
    TIMED_REVOKE_UNSUPPORTED("This WhatsApp version cannot delete this message for everyone."),

    /** The revocation window could not be resolved, so nothing is promised. */
    REVOKE_WINDOW_UNKNOWN("WA X could not determine this version's Delete-for-Everyone window."),

    /** The delay is longer than the client will honour. */
    DELAY_EXCEEDS_REVOKE_WINDOW("The chosen delay is longer than this WhatsApp version still allows."),
}

/**
 * What View Once should do for this send.
 *
 * [abortRequired] is the fail-safe: when a scope demanded View Once and it cannot be applied,
 * the message is not sent as ordinary media. [indicator] is the visible cue the interface
 * shows when a policy turned View Once on by itself, because changing how media behaves
 * without saying so is the failure this feature exists to avoid.
 */
data class ViewOnceDecision(
    val choice: ViewOnceChoice,
    val source: PolicyScope,
    val applyViewOnce: Boolean,
    val capabilityId: String?,
    val block: ViewOnceBlockReason?,
    val abortRequired: Boolean,
    val notifyRequired: Boolean,
    val indicator: String?,
) {
    /** One line for diagnostics. Names the scope's kind, never the chat's identifier. */
    fun toDisplayLine(): String =
        "view-once ${if (applyViewOnce) "on" else "off"} from ${source.label} (${choice.name})" +
            (block?.let { " blocked: ${it.name}" } ?: "")
}

/** What timed delete-for-everyone should do for this send. */
data class AutoDeleteDecision(
    val choice: AutoDeleteChoice,
    val source: PolicyScope,
    val delayMillis: Long,
    val schedule: Boolean,
    val block: AutoDeleteBlockReason?,
    val fallback: ExpiredWindowFallback,
    val explanation: String,
) {
    /** One line for diagnostics. Names the scope's kind, never the chat's identifier. */
    fun toDisplayLine(): String =
        if (schedule) {
            "revoke after ${AutoDeleteDurations.label(delayMillis)} from ${source.label}"
        } else {
            "no revoke from ${source.label}" + (block?.let { " (${it.name})" } ?: "")
        }
}

/** Everything the send path needs to know before it builds the native message. */
data class OutgoingPolicyDecision(
    val viewOnce: ViewOnceDecision,
    val autoDelete: AutoDeleteDecision,
    /** Sanitized notes explaining how the decision was reached. */
    val diagnostics: List<String>,
) {
    /** Whether the send must stop rather than continue without the requested policy. */
    val abortRequired: Boolean get() = viewOnce.abortRequired

    /** The indicator to show before sending, or null when there is nothing to say. */
    val indicator: String? get() = viewOnce.indicator

    /** Whether the user has to be told something before this send is retried. */
    val notifyRequired: Boolean get() = viewOnce.notifyRequired || autoDelete.block != null

    /**
     * Which mechanism makes the content disappear when both are active.
     *
     * Stated explicitly rather than left to the reader, because the two features overlap and
     * a message that is both View Once and timed-revocable needs a defined answer instead of
     * a race: View Once governs what happens after the recipient opens it, and the timed
     * revoke still applies to a message that has not been opened by its deadline.
     */
    val mediaDisappearanceRule: String
        get() =
            when {
                viewOnce.applyViewOnce && autoDelete.schedule -> {
                    "View Once governs after the recipient opens it; the timed revoke still applies while it is unopened."
                }

                viewOnce.applyViewOnce -> {
                    "View Once governs."
                }

                autoDelete.schedule -> {
                    "Timed delete for everyone governs."
                }

                else -> {
                    "Neither policy applies."
                }
            }

    /** One line for the audit log. */
    fun toDisplayLine(): String = "${viewOnce.toDisplayLine()}; ${autoDelete.toDisplayLine()}"
}

/**
 * The one place outgoing send-time policy is decided.
 *
 * Auto View Once, timed Delete for Everyone and the policies that will follow them (EXIF
 * cleanup, tracking-link cleanup, media download policy) are deliberately resolved together.
 * Each sender path asking this object once is what makes the precedence order real: the order
 * is written down here, tested here, and cannot drift per call site.
 *
 * The resolver never throws. A missing capability, an unreadable policy or an unresolvable
 * revocation window produces a decision with a block reason and a safe default, because a
 * feature whose resolver failed must not take the send down with it.
 */
class OutgoingMessagePolicyEngine(
    private val policies: OutgoingPolicyStore,
    private val capabilities: CapabilityProbe = CapabilityProbe.AllSupported,
    private val revokeWindow: RevokeWindowProbe = RevokeWindowProbe.Unknown,
) {
    /** Resolves every outward-facing policy for [request]. */
    fun decide(request: OutgoingSendRequest): OutgoingPolicyDecision {
        val diagnostics = ArrayList<String>()
        val merged = merge(request, diagnostics)
        val viewOnce = decideViewOnce(request, merged, diagnostics)
        val autoDelete = decideAutoDelete(request, merged, diagnostics)
        diagnostics.add("disappearance: ${describeDisappearance(viewOnce, autoDelete)}")
        return OutgoingPolicyDecision(viewOnce, autoDelete, diagnostics)
    }

    // --- precedence ---------------------------------------------------------------------

    /**
     * Walks the scope hierarchy from least to most specific and merges what each scope says.
     *
     * The order is Global, Target, Account, List, Chat, and finally the choices made for this
     * single send. A scope that says nothing about a field leaves the more general answer in
     * place, which is what lets a contact override only the delay while still inheriting the
     * group's choice of message types.
     */
    private fun merge(
        request: OutgoingSendRequest,
        diagnostics: MutableList<String>,
    ): MergedPolicy {
        val merged = MergedPolicy()
        scopesFor(request).forEach { scope ->
            val layer = policies.layerFor(scope)
            if (layer.isEmpty) return@forEach
            // The scope's *kind*, not its code: a code carries the chat identifier, and
            // diagnostics end up in reports and logs where the identifier has no business.
            diagnostics.add("${scope.label}: policy applied")
            layer.viewOnce.forEach { (messageClass, choice) -> merged.putViewOnce(messageClass, choice, scope) }
            if (layer.autoDelete != AutoDeleteChoice.USE_PARENT) {
                merged.autoDelete = layer.autoDelete
                merged.autoDeleteSource = scope
            }
            layer.autoDeleteDelayMillis?.let {
                merged.delayMillis = it
                if (layer.autoDelete == AutoDeleteChoice.ENABLED) merged.autoDeleteSource = scope
            }
            layer.autoDeleteClasses?.let { merged.autoDeleteClasses = it }
            if (layer.expiredWindowFallback != ExpiredWindowFallback.DO_NOTHING) {
                merged.expiredWindowFallback = layer.expiredWindowFallback
            }
        }
        applyPerMessage(request, merged, diagnostics)
        return merged
    }

    /** The scopes that apply to [request], from least to most specific. */
    private fun scopesFor(request: OutgoingSendRequest): List<PolicyScope> =
        PolicyScopes.forChat(request.app, request.chatId, request.kind, request.accountId, request.listId)

    /**
     * Applies the highest-precedence layer: what the user asked for on this one message.
     *
     * WA X's own per-message action wins over the native `1` control when a caller supplies
     * both, because it is the more explicit of the two; the native control still wins over
     * every stored policy, which is what makes "the user turned View Once off for this message"
     * work even under an Always View Once contact policy.
     */
    private fun applyPerMessage(
        request: OutgoingSendRequest,
        merged: MergedPolicy,
        diagnostics: MutableList<String>,
    ) {
        val perMessageScope = PolicyScope.Message(request.app)
        request.nativeViewOnceControl?.let { on ->
            merged.putViewOnce(
                request.messageClass,
                if (on) ViewOnceChoice.ALWAYS_VIEW_ONCE else ViewOnceChoice.NORMAL,
                perMessageScope,
            )
            diagnostics.add("native view-once control used for this send")
        }
        request.perMessageViewOnce?.let { choice ->
            if (choice != ViewOnceChoice.USE_PARENT) {
                merged.putViewOnce(request.messageClass, choice, perMessageScope)
                diagnostics.add("per-message view-once override applied")
            }
        }
        request.perMessageAutoDelete?.let { choice ->
            if (choice != AutoDeleteChoice.USE_PARENT) {
                merged.autoDelete = choice
                merged.autoDeleteSource = perMessageScope
                diagnostics.add("per-message auto-delete override applied")
            }
        }
        request.perMessageAutoDeleteMillis?.let { merged.delayMillis = it }
    }

    // --- decisions ----------------------------------------------------------------------

    private fun decideViewOnce(
        request: OutgoingSendRequest,
        merged: MergedPolicy,
        diagnostics: MutableList<String>,
    ): ViewOnceDecision {
        val declared = merged.viewOnce[request.messageClass] ?: ViewOnceChoice.USE_PARENT
        val source = merged.viewOnceSource[request.messageClass] ?: PolicyScope.Global
        val choice = if (declared == ViewOnceChoice.USE_PARENT) ViewOnceChoice.NORMAL else declared
        val capabilityId = OutgoingCapabilities.viewOnceIdFor(request.messageClass)
        if (choice == ViewOnceChoice.NORMAL) {
            return ViewOnceDecision(
                choice = choice,
                source = source,
                applyViewOnce = false,
                capabilityId = capabilityId,
                block = null,
                abortRequired = false,
                notifyRequired = false,
                indicator = null,
            )
        }
        if (choice == ViewOnceChoice.ASK_EACH_TIME) {
            diagnostics.add("view-once policy asks before sending")
            return ViewOnceDecision(
                choice = choice,
                source = source,
                applyViewOnce = false,
                capabilityId = capabilityId,
                block = ViewOnceBlockReason.AWAITING_CONFIRMATION,
                abortRequired = false,
                notifyRequired = true,
                indicator = null,
            )
        }
        // ALWAYS_VIEW_ONCE from here on: it either applies, or the send stops. Sending the
        // media as an ordinary attachment would leave it persistent on the recipient's device
        // after the user asked for the opposite, and doing that silently is the one outcome
        // this feature must never produce.
        val block =
            when {
                capabilityId == null -> ViewOnceBlockReason.CLASS_NOT_SUPPORTED
                !capabilities.supports(capabilityId) -> ViewOnceBlockReason.MISSING_CAPABILITY
                else -> null
            }
        if (block != null) {
            diagnostics.add("view-once required but blocked: ${block.name}")
        }
        return ViewOnceDecision(
            choice = choice,
            source = source,
            applyViewOnce = block == null,
            capabilityId = capabilityId,
            block = block,
            abortRequired = block != null,
            notifyRequired = block != null,
            indicator =
                if (block == null) {
                    "View Once · ${source.label} policy"
                } else {
                    null
                },
        )
    }

    private fun decideAutoDelete(
        request: OutgoingSendRequest,
        merged: MergedPolicy,
        diagnostics: MutableList<String>,
    ): AutoDeleteDecision {
        val source = merged.autoDeleteSource
        val fallback = merged.expiredWindowFallback
        if (merged.autoDelete != AutoDeleteChoice.ENABLED) {
            return AutoDeleteDecision(
                choice = merged.autoDelete,
                source = source,
                delayMillis = 0L,
                schedule = false,
                block = null,
                fallback = fallback,
                explanation = "Auto delete is off for this chat.",
            )
        }
        val delay = merged.delayMillis
        if (delay == null || delay <= 0L) {
            diagnostics.add("auto-delete enabled without a usable delay")
            return blockedAutoDelete(AutoDeleteBlockReason.MALFORMED_POLICY, source, fallback)
        }
        val selected = merged.autoDeleteClasses
        if (selected != null && request.messageClass !in selected) {
            return blockedAutoDelete(AutoDeleteBlockReason.CLASS_NOT_SELECTED, source, fallback, delay)
        }
        if (!capabilities.supports(OutgoingCapabilities.TIMED_REVOKE)) {
            return blockedAutoDelete(AutoDeleteBlockReason.TIMED_REVOKE_UNSUPPORTED, source, fallback, delay)
        }
        val window = revokeWindow.maxRevokeWindowMillis()
        if (window <= 0L) {
            // Unknown is not the same as absent: the client might allow it, but WA X could not
            // confirm how long, and promising a remote deletion it cannot perform is worse than
            // saying so. Nothing is scheduled and no local-only deletion happens unless the
            // user explicitly chose that fallback.
            diagnostics.add("revoke window unresolved, so no remote deletion is promised")
            return blockedAutoDelete(AutoDeleteBlockReason.REVOKE_WINDOW_UNKNOWN, source, fallback, delay)
        }
        if (delay > window) {
            diagnostics.add("requested delay is outside the resolved revoke window")
            return blockedAutoDelete(AutoDeleteBlockReason.DELAY_EXCEEDS_REVOKE_WINDOW, source, fallback, delay)
        }
        return AutoDeleteDecision(
            choice = AutoDeleteChoice.ENABLED,
            source = source,
            delayMillis = delay,
            schedule = true,
            block = null,
            fallback = fallback,
            explanation = "Delete for everyone after ${AutoDeleteDurations.label(delay)}.",
        )
    }

    private fun blockedAutoDelete(
        block: AutoDeleteBlockReason,
        source: PolicyScope,
        fallback: ExpiredWindowFallback,
        delayMillis: Long = 0L,
    ): AutoDeleteDecision =
        AutoDeleteDecision(
            choice = AutoDeleteChoice.ENABLED,
            source = source,
            delayMillis = delayMillis,
            schedule = false,
            block = block,
            fallback = fallback,
            explanation =
                if (fallback == ExpiredWindowFallback.DELETE_LOCAL_COPY_ONLY) {
                    "${block.explanation} Only your own copy can be removed, and it will be."
                } else {
                    "${block.explanation} The message will be left as it is."
                },
        )

    private fun describeDisappearance(
        viewOnce: ViewOnceDecision,
        autoDelete: AutoDeleteDecision,
    ): String =
        when {
            viewOnce.applyViewOnce && autoDelete.schedule -> "view-once-then-timed-revoke"
            viewOnce.applyViewOnce -> "view-once"
            autoDelete.schedule -> "timed-revoke"
            else -> "none"
        }
}

/**
 * The resolved policy, with the source of each answer tracked as it is overwritten.
 *
 * Holding the source alongside the value is what lets the indicator read "View Once ·
 * Contact policy" instead of an unattributed "View Once": the user has to be able to see
 * which rule acted, or the setting becomes impossible to reason about.
 */
private class MergedPolicy {
    val viewOnce = LinkedHashMap<OutgoingMessageClass, ViewOnceChoice>()

    val viewOnceSource = LinkedHashMap<OutgoingMessageClass, PolicyScope>()

    var autoDelete: AutoDeleteChoice = AutoDeleteChoice.USE_PARENT

    var autoDeleteSource: PolicyScope = PolicyScope.Global

    var delayMillis: Long? = null

    var autoDeleteClasses: Set<OutgoingMessageClass>? = null

    var expiredWindowFallback: ExpiredWindowFallback = ExpiredWindowFallback.DO_NOTHING

    fun putViewOnce(
        messageClass: OutgoingMessageClass,
        choice: ViewOnceChoice,
        scope: PolicyScope,
    ) {
        viewOnce[messageClass] = choice
        viewOnceSource[messageClass] = scope
    }
}
