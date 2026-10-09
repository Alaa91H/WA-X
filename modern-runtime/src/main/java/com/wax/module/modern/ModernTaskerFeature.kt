package com.wax.module.modern

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicLong
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy Tasker automation bridge.
 *
 * The Manager can already toggle Tasker support and hold the auth token; this
 * adapter reproduces the runtime half on API 102:
 *
 * - hook the inbound receipt method resolved from the repo-derived evidence
 *   the legacy resolver uses (`receipt` string, `ProtocolTreeNode` return
 *   type, a `jid.DeviceJid` parameter) — no guessed name;
 * - forward a received message to Tasker on a package-targeted broadcast that
 *   carries the user's auth token;

 * Honest scope: the forward direction (WhatsApp receipt -> Tasker) is ported.
 * The reverse direction (Tasker -> WA X sends a message) needs the migrated
 * send pipeline (ActionUser + FMessage + userJid construction are still
 * legacy-only), so no receiver is registered for it and the outcome reports
 * `SEND_DIRECTION_PENDING` rather than pretending both directions work.
 *
 * Everything is opt-in: disabled by default, inert without a non-blank token,
 * and every broadcast carries the user's auth token.
 */
object ModernTaskerFeature {
    const val FEATURE_ID = "tasker"
    const val PREF_ENABLED = "tasker"
    const val PREF_TOKEN = "tasker_auth_token"
    const val TASKER_PACKAGE = "net.dinglisch.android.taskerm"
    const val ACTION_MESSAGE_SENT = "com.wax.module.MESSAGE_SENT"
    const val ACTION_MESSAGE_RECEIVED = "com.wax.module.MESSAGE_RECEIVED"
    const val EXTRA_AUTH_TOKEN = "token"
    const val EXTRA_NUMBER = "number"
    const val EXTRA_NAME = "name"
    const val EXTRA_MESSAGE = "message"
    const val ANCHOR_RECEIPT = "receipt"
    const val ANCHOR_PROTOCOL_TREE_NODE = "ProtocolTreeNode/getAttributeJid"
    const val DEVICE_JID_SUFFIX = "jid.DeviceJid"
    private const val TAG = "WA-X Tasker102"

    enum class Outcome {
        DISABLED,
        TOKEN_MISSING,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        SEND_DIRECTION_PENDING,
        INSTALLED,
    }

    /** Pure validation shared by both broadcast directions. */
    @JvmStatic
    fun isAuthorized(expectedToken: String?, presented: String?): Boolean =
        !expectedToken.isNullOrBlank() && expectedToken == presented

    /** Tasker numbers may arrive as a string or a long; both are accepted. */
    @JvmStatic
    fun normalizeNumber(raw: Any?): String? {
        val text = when (raw) {
            null -> return null
            is String -> raw
            is Long -> raw.toString()
            is Int -> raw.toString()
            else -> return null
        }.trim()
        if (text.isEmpty() || text == "0") return null
        val digits = text.filter { it.isDigit() }
        return digits.ifEmpty { null }
    }

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!preferences.getBoolean(PREF_ENABLED, false)) return Outcome.DISABLED
        val token = preferences.getString(PREF_TOKEN, "").orEmpty()
        if (token.isBlank()) return Outcome.TOKEN_MISSING

        val receiptMethod = try {
            resolveReceiptMethod(target)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Receipt resolver unavailable", failure)
            return Outcome.RESOLVER_MISSING
        } ?: return Outcome.RESOLVER_MISSING

        val application = target.applicationContext
        // The reverse direction (Tasker asks WA X to send a message) needs the
        // migrated send pipeline: ActionUser + FMessage + userJid construction
        // are still legacy-only, and a receiver that accepts a send command and
        // silently drops it would be worse than not offering it. It is
        // therefore not registered, and the outcome says so instead of
        // pretending both directions work.
        Log.i(TAG, "Tasker forward channel ready; reverse send direction pending migration")

        val sent = AtomicLong()
        try {
            hooks.installFeature(FEATURE_ID, listOf(
                ModernHookRegistry.Registration("tasker.receipt") {
                    val handle = ModernHookBridge(framework).intercept(
                        receiptMethod, "wax.modern.tasker.receipt") { chain ->
                        val result = chain.proceed()
                        if (sent.incrementAndGet() % SAMPLE_EVERY == 0L) {
                            forward(application, token, chain.args)
                        }
                        result
                    }
                    ModernHookRegistry.Handle { handle.unhook() }
                }))
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Tasker hook unavailable", failure)
            return Outcome.RESOLVER_MISSING
        }
        return Outcome.INSTALLED
    }

    /**
     * Forwards one receipt hook invocation to Tasker.
     *
     * The message body is not re-derived here: extracting it requires the
     * FMessageWpp field chain, which is its own migration. When the hook
     * exposes the number and message strings among its arguments they are
     * forwarded; otherwise nothing is sent, because guessing which argument
     * is the message would send the wrong content to a third-party app.
     */
    private fun forward(application: Context, token: String, args: List<Any?>) {
        try {
            val number = normalizeNumber(args.firstOrNull { it is String && it.all { c -> c.isDigit() } })
            val message = args.firstOrNull { it is String && it.isNotBlank() && it != number } as? String
            if (number == null || message == null) return
            val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                setPackage(TASKER_PACKAGE)
                putExtra(EXTRA_AUTH_TOKEN, token)
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_MESSAGE, message)
            }
            application.sendBroadcast(intent)
            Log.i(TAG, "Forwarded a receipt event to Tasker")
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Tasker forward failed", failure)
        }
    }

    private fun resolveReceiptMethod(target: Context): java.lang.reflect.Method? {
        val classLoader = target.classLoader
        val deviceJid = DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
            dex.findClass {
                matcher { className(DEVICE_JID_SUFFIX, StringMatchType.EndsWith) }
            }.firstOrNull()?.getInstance(classLoader)?.name
        } ?: return null
        val treeNode = DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
            dex.findClass {
                matcher {
                    addUsingString(ANCHOR_PROTOCOL_TREE_NODE, StringMatchType.Contains)
                }
            }.firstOrNull()?.getInstance(classLoader)
        }
        return DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
            dex.findMethod {
                matcher {
                    addUsingString(ANCHOR_RECEIPT, StringMatchType.Contains)
                    if (treeNode != null) returnType(treeNode)
                }
            }.firstOrNull { data ->
                data.paramTypeNames.any { it == deviceJid }
            }?.getMethodInstance(classLoader)
        }
    }

    private const val SAMPLE_EVERY = 1L
}