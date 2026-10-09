package com.wax.module.modern

import android.util.Log
import java.lang.reflect.Method

/**
 * Reads the textual form of a WhatsApp JID and derives the phone number from
 * it, on the modern runtime.
 *
 * The legacy `FMessageWpp.UserJid` does the same thing but reaches the value
 * through `XposedHelpers.callMethod(jid, "getRawString")` — a literal member
 * name. This accessor resolves that method **by signature** instead: a
 * public no-argument method on the JID class returning `String`. If several
 * such methods exist, the ambiguity is reported rather than one being picked
 * at random, because reading the wrong string would attribute a contact to the
 * wrong number.
 *
 * The derived phone number reproduces the legacy rules exactly:
 * strip a device suffix (`.1:2@`), then if the string still carries a dot
 * before the `@`, take the part before the dot; otherwise, for the known JID
 * domains, take the part before the `@`.
 */
class ModernJidAccess private constructor(
    private val rawStringMethod: Method,
) {
    /** The JID's textual form with any device suffix removed. */
    fun rawString(jid: Any?): String? {
        if (jid == null) return null
        return try {
            rawStringMethod.invoke(jid) as? String
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "JID raw string unreadable", failure)
            null
        }?.let { JidRules.stripDeviceSuffix(it) }
    }

    /** The phone number, or null when the JID carries none. */
    fun phoneNumber(jid: Any?): String? = JidRules.phoneNumber(rawString(jid))

    fun isGroup(jid: Any?): Boolean =
        rawString(jid)?.endsWith(JidRules.GROUP_SUFFIX) == true

    fun isBroadcast(jid: Any?): Boolean =
        rawString(jid)?.endsWith(JidRules.BROADCAST_SUFFIX) == true

    companion object {
        private const val TAG = "WA-X JidAccess"

        enum class Outcome {
            AVAILABLE,
            RAW_STRING_METHOD_MISSING,
            RAW_STRING_METHOD_AMBIGUOUS,
            ERROR,
        }

        class Resolution(val access: ModernJidAccess?, val outcome: Outcome) {
            val available: Boolean get() = access != null
        }

        /** Resolves the raw-string accessor on an already-resolved JID class. */
        @JvmStatic
        fun resolve(jidClass: Class<*>): Resolution = try {
            val candidates = jidClass.methods.filter { method ->
                method.parameterCount == 0 &&
                    method.returnType == String::class.java &&
                    !java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
            when (candidates.size) {
                0 -> Resolution(null, Outcome.RAW_STRING_METHOD_MISSING)
                1 -> {
                    candidates[0].isAccessible = true
                    Resolution(ModernJidAccess(candidates[0]), Outcome.AVAILABLE)
                }
                else -> Resolution(null, Outcome.RAW_STRING_METHOD_AMBIGUOUS)
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "JID accessor resolver unavailable", failure)
            Resolution(null, Outcome.ERROR)
        }
    }
}

/** Pure JID string rules, shared by the accessor and its tests. */
object JidRules {
    const val GROUP_SUFFIX = "@g.us"
    const val BROADCAST_SUFFIX = "@broadcast"
    private val KNOWN_SUFFIXES = listOf(
        GROUP_SUFFIX,
        "@s.whatsapp.net",
        BROADCAST_SUFFIX,
        "@lid",
    )

    /** Removes a `.device:agent@` suffix, as the legacy accessor does. */
    fun stripDeviceSuffix(raw: String): String =
        raw.replaceFirst(Regex("\\.[\\d:]+@"), "@")

    /**
     * Mirrors the legacy rules: a dot before the `@` wins, then the known JID
     * domains take the part before the `@`, otherwise the raw string is used.
     */
    fun phoneNumber(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val dot = raw.indexOf('.')
        val at = raw.indexOf('@')
        if (dot >= 0 && at > dot) return raw.substring(0, dot)
        if (KNOWN_SUFFIXES.any { raw.endsWith(it) } && at > 0) return raw.substring(0, at)
        return raw
    }

    /** True when the JID carries no usable address. */
    fun isInvalid(raw: String?): Boolean {
        if (raw.isNullOrEmpty()) return true
        val at = raw.indexOf('@')
        return at <= 0 || at == raw.length - 1
    }
}