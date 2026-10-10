package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap

/**
 * API 102 port of the receipt privacy family (#449).
 *
 * Three behaviours that must stay separate, because they are different claims:
 *
 * 1. **withhold read receipts** — the sender does not learn the message was
 *    read. This is a protocol statement, so it is implemented by withholding
 *    the outbound read receipt, never by hiding a tick on screen.
 * 2. **release after my reply** — while the receipts are withheld, one real
 *    reply in the same conversation releases the eligible receipts exactly
 *    once. A draft, a cancelled send, a queued send or a reply to another chat
 *    must not release anything.
 * 3. **withhold delivery receipts** — the sender does not learn the message
 *    arrived. Whether that is possible at all is a question about WhatsApp's
 *    server, not about this module. This feature does not answer it by
 *    pretending: it reports [Outcome.UNSUPPORTED] with the reason, because a
 *    toggle that changes nothing is worse than one that says it cannot work.
 *
 * Every failure path returns to native behaviour. When the resolver is
 * ambiguous, the signature is unexpected, or the DEX scan fails, this module
 * sends whatever WhatsApp would have sent: withholding a receipt that the user
 * asked to withhold is the safe direction, and failing open on *hiding* would
 * leak the read the user asked to protect.
 *
 * Targets come from the legacy resolver in this repository, which found them
 * on real builds:
 * - the read-receipt send: a method using
 *   `ReadReceipts/sendReceiptForIncomingMessage`;
 * - the outbound job that carries it: the class ending in `SendReadReceiptJob`
 *   and its method using the literal `receipt`.
 */
object ModernReceiptPrivacyFeature {
    const val FEATURE_ID_READ = "receipt_privacy_read"
    const val FEATURE_ID_AFTER_REPLY = "receipt_privacy_after_reply"
    const val FEATURE_ID_DELIVERY = "receipt_privacy_delivery"

    /** Legacy keys, read so an existing user's switch keeps its meaning. */
    const val PREF_HIDE_READ = "hideread"
    const val PREF_HIDE_READ_GROUP = "hideread_group"
    const val PREF_HIDE_RECEIPT = "hidereceipt"
    const val PREF_AFTER_REPLY = "hidereadafterreply"

    const val ANCHOR_READ_RECEIPT = "ReadReceipts/sendReceiptForIncomingMessage"
    const val RECEIPT_JOB_SUFFIX = "SendReadReceiptJob"
    const val RECEIPT_JOB_STRING = "receipt"

    private const val TAG = "WA-X ReceiptPrivacy102"

    /**
     * How long a confirmed reply keeps a conversation eligible for release.
     *
     * Bounded on purpose: a release that stays pending forever would turn one
     * reply into a standing permission to read every later message.
     */
    const val RELEASE_WINDOW_MILLIS = 10 * 60 * 1000L

    enum class Outcome {
        /** Nothing was asked for, so nothing was installed. */
        DISABLED,

        /** The hook is installed and withholding. */
        INSTALLED,

        /** Withheld, and one confirmed reply will release them once. */
        INSTALLED_ARMED,

        /** The anchor was not found on this build. */
        RESOLVER_MISSING,

        /** The anchor matched several methods; nothing was hooked. */
        RESOLVER_AMBIGUOUS,

        /** The anchor resolved but does not have the shape this relies on. */
        UNSAFE_SIGNATURE,

        /**
         * Delivery receipts are decided by WhatsApp's servers. Suppressing what
         * the sender can observe about delivery cannot be done from inside the
         * app, and this module will not fake an acknowledgement to pretend
         * otherwise.
         */
        UNSUPPORTED,

        ERROR,
    }

    enum class SendResult {
        /** The send completed; a receipt may be released if one is armed. */
        SUCCEEDED,

        /** Draft, cancelled, queued, scheduled or failed: nothing is released. */
        NOT_COMPLETED,
    }

    /**
     * The per-conversation arming state.
     *
     * Keyed by a conversation the caller already holds, never by a contact
     * number or a JID: this map is the only thing that persists between the
     * send callback and the receipt job, so it must not become a contact store.
     */
    private val armedConversations = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun armedCount(): Int = armedConversations.size

    @JvmStatic
    fun clearArmedConversations() {
        armedConversations.clear()
    }

    /**
     * Records that the user really replied in this conversation.
     *
     * Only [SendResult.SUCCEEDED] arms it. Everything else — a draft, a
     * cancelled send, a queued or scheduled one, a reply to another chat — is
     * deliberately not enough, because releasing on those would turn a reply
     * the user never sent into a read receipt.
     */
    @JvmStatic
    fun onSendCompleted(
        conversationKey: String?,
        result: SendResult,
        nowMillis: Long,
    ): Boolean {
        if (conversationKey.isNullOrEmpty() || result != SendResult.SUCCEEDED ||
            nowMillis < 0 || nowMillis > Long.MAX_VALUE - RELEASE_WINDOW_MILLIS
        ) return false
        armedConversations[conversationKey] = nowMillis + RELEASE_WINDOW_MILLIS
        return true
    }

    /**
     * Consumes an arming for one receipt send.
     *
     * Returns true at most once per arming, which is what makes "release
     * exactly once" true even when WhatsApp queues several receipt jobs.
     */
    @JvmStatic
    fun consumeArming(
        conversationKey: String?,
        nowMillis: Long,
    ): Boolean {
        if (conversationKey.isNullOrEmpty()) return false
        val expiresAt = armedConversations[conversationKey] ?: return false
        if (nowMillis > expiresAt) {
            // Do not remove a later, fresh arming that raced with expiration.
            armedConversations.remove(conversationKey, expiresAt)
            return false
        }
        // Conditional remove is atomic. Two receipt-job threads must not
        // both release the same withheld receipt after one completed reply.
        return armedConversations.remove(conversationKey, expiresAt)
    }

    /** Whether a withheld receipt may still be released right now. */
    @JvmStatic
    fun isArmed(
        conversationKey: String?,
        nowMillis: Long,
    ): Boolean {
        val expiresAt = conversationKey?.let { armedConversations[it] } ?: return false
        return nowMillis <= expiresAt
    }

    /** Everything the three toggles resolve to, before anything is hooked. */
    data class Request(
        val hideRead: Boolean,
        val hideReadInGroups: Boolean,
        val afterReply: Boolean,
        val hideDelivery: Boolean,
    )

    @JvmStatic
    fun readRequest(preferences: SharedPreferences): Request =
        Request(
            hideRead = preferences.getBoolean(PREF_HIDE_READ, false),
            hideReadInGroups = preferences.getBoolean(PREF_HIDE_READ_GROUP, false),
            afterReply = preferences.getBoolean(PREF_AFTER_REPLY, false),
            hideDelivery = preferences.getBoolean(PREF_HIDE_RECEIPT, false),
        )

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Map<String, Outcome> {
        val request = readRequest(preferences)
        val results = LinkedHashMap<String, Outcome>()

        // Delivery receipts are answered first because the answer does not
        // depend on the resolver and must never be mistaken for one.
        results[FEATURE_ID_DELIVERY] =
            if (request.hideDelivery) Outcome.UNSUPPORTED else Outcome.DISABLED

        val withholds = request.hideRead || request.afterReply
        if (!withholds) {
            results[FEATURE_ID_READ] = Outcome.DISABLED
            results[FEATURE_ID_AFTER_REPLY] = Outcome.DISABLED
            return results
        }
        // The reply rule is meaningless on its own: without withheld receipts
        // there is nothing to release, and saying so is more useful than
        // installing a hook that never fires.
        if (request.afterReply && !request.hideRead) {
            results[FEATURE_ID_READ] = Outcome.UNSUPPORTED
            results[FEATURE_ID_AFTER_REPLY] = Outcome.UNSUPPORTED
            return results
        }

        val resolved = resolveReceiptSend(target)
        if (resolved is ReceiptResolution.Failed) {
            results[FEATURE_ID_READ] = resolved.outcome
            results[FEATURE_ID_AFTER_REPLY] = resolved.outcome
            return results
        }
        val method = (resolved as ReceiptResolution.Resolved).method

        val installed =
            try {
                hooks.installFeature(
                    FEATURE_ID_READ,
                    listOf(
                        ModernHookRegistry.Registration("receipt_privacy.send") {
                            val handle =
                                ModernHookBridge(framework).intercept(
                                    method,
                                    "wax.modern.receipt_privacy.send",
                                ) { chain ->
                                    val conversation = conversationKeyOf(chain.args)
                                    val suppress =
                                        when {
                                            conversation == null -> {
                                                // Without a conversation there is
                                                // no way to match a reply to this
                                                // receipt, so withholding is the
                                                // only safe reading of the switch.
                                                true
                                            }

                                            request.afterReply -> {
                                                !consumeArming(conversation, System.currentTimeMillis())
                                            }

                                            else -> {
                                                true
                                            }
                                        }
                                    if (suppress) null else chain.proceed()
                                }
                            ModernHookRegistry.Handle { handle.unhook() }
                        },
                    ),
                )
                Outcome.INSTALLED
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Receipt privacy hook unavailable", failure)
                Outcome.ERROR
            }

        results[FEATURE_ID_READ] = installed
        results[FEATURE_ID_AFTER_REPLY] = afterReplyOperationalState(request.afterReply, installed)
        return results
    }

    /**
     * A reply release requires an observed *successful native send* callback.
     * Until that event is wired and proven, the arming state-machine alone is
     * NOT a working feature. Keep the withholding read hook independent.
     */
    internal fun afterReplyOperationalState(requested: Boolean, readHook: Outcome): Outcome =
        when {
            !requested -> Outcome.DISABLED
            readHook != Outcome.INSTALLED -> readHook
            else -> Outcome.UNSUPPORTED
        }

    /**
     * The receipt send, or the reason there is none.
     *
     * Only the anchor the legacy resolver found is accepted, and only when it
     * matches exactly once. A second match is reported rather than resolved by
     * order: hooking the wrong method would send receipts the user asked to
     * withhold.
     */
    private sealed interface ReceiptResolution {
        data class Resolved(
            val method: java.lang.reflect.Method,
        ) : ReceiptResolution

        data class Failed(
            val outcome: Outcome,
        ) : ReceiptResolution
    }

    private fun resolveReceiptSend(target: Context): ReceiptResolution {
        val candidates =
            try {
                DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                    dex.findMethod {
                        matcher { addUsingString(ANCHOR_READ_RECEIPT, StringMatchType.Contains) }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Read receipt resolver unavailable", failure)
                return ReceiptResolution.Failed(Outcome.RESOLVER_MISSING)
            }
        return when {
            candidates.isEmpty() -> {
                ReceiptResolution.Failed(Outcome.RESOLVER_MISSING)
            }

            candidates.size != 1 -> {
                ReceiptResolution.Failed(Outcome.RESOLVER_AMBIGUOUS)
            }

            else -> {
                val method =
                    try {
                        candidates[0].getMethodInstance(target.classLoader)
                    } catch (failure: Throwable) {
                        if (failure is VirtualMachineError) throw failure
                        Log.w(TAG, "Read receipt method unresolvable", failure)
                        return ReceiptResolution.Failed(Outcome.RESOLVER_MISSING)
                    }
                // The legacy feature calls this with the receipt as the first
                // argument. Anything else is a different method that happens to
                // share the anchor, and hooking it would be a guess.
                if (method.parameterCount == 0) {
                    ReceiptResolution.Failed(Outcome.UNSAFE_SIGNATURE)
                } else {
                    ReceiptResolution.Resolved(method)
                }
            }
        }
    }

    /**
     * The conversation a receipt belongs to, read from the argument the legacy
     * feature already reads.
     *
     * Returns an opaque key or null, never a contact identity: nothing here
     * may end up holding a phone number or a JID.
     */
    private fun conversationKeyOf(args: List<Any?>): String? {
        val receipt = args.firstOrNull() ?: return null
        return try {
            receipt.javaClass
                .getMethod("getAbridgedMessage")
                .invoke(receipt)
                ?.toString()
                ?.hashCode()
                ?.toString()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            // No conversation identity is recoverable, and inventing one would
            // risk releasing a receipt for the wrong chat.
            null
        }
    }
}
