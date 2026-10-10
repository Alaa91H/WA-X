package com.wax.module.modern

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/** A resolver must never silently pick one item from a non-unique match set. */
internal sealed class CandidateSelection<out T> {
    data object Missing : CandidateSelection<Nothing>()
    data class Unique<T>(val candidate: T) : CandidateSelection<T>()
    data object Ambiguous : CandidateSelection<Nothing>()

    companion object {
        fun <T> from(candidates: List<T>): CandidateSelection<T> = when (candidates.size) {
            0 -> Missing
            1 -> Unique(candidates.single())
            else -> Ambiguous
        }
    }
}

/**
 * Modern runtime access to WhatsApp's contact objects.
 *
 * The legacy path wraps contacts in `WaContactWpp`, which resolves a chain of
 * obfuscated members through `XposedBridge`. Every consumer feature that needs
 * a name, a JID or a phone number reads that wrapper, so the chain has to
 * exist on API 102 before those features can be ported honestly.
 *
 * Every target here is derived from evidence already in the repository — the
 * same anchors `Unobfuscator` uses — never from a guessed member name:
 *
 * - contact class: the class using `"problematic contact:"`;
 * - contact data: the class whose name ends with `WaContactData`;
 * - JID class: the class whose name ends with `jid.Jid`;
 * - phone-JID class: the return type of the method using
 *   `WaJidMapRepository/getPhoneJidByAccountUserJid`;
 * - user JID: the field typed to the JID class, on the contact data class when
 *   the contact holds one and on the contact class otherwise, exactly as the
 *   legacy initializer decides.
 *
 * Missing or ambiguous targets produce a typed failure and a null accessor
 * instead of an exception, so a consumer that needs a JID degrades to "no
 * information" rather than guessing.
 */
class ModernContactAccess private constructor(
    val contactClass: Class<*>,
    private val contactDataClass: Class<*>?,
    /** The JID class, exposed so a JID accessor can be resolved from it. */
    val jidClass: Class<*>,
    private val phoneUserJidClass: Class<*>?,
    private val userJidField: java.lang.reflect.Field,
) {
    /** The contact's JID object, or null when it cannot be read. */
    fun userJid(contact: Any): Any? = try {
        userJidField.get(contact)
    } catch (failure: Throwable) {
        if (failure is VirtualMachineError) throw failure
        Log.w(TAG, "Contact JID unreadable", failure)
        null
    }

    /** True when the value is a JID (as opposed to a LID-shaped value). */
    fun isPhoneJid(jid: Any?): Boolean = jid != null && phoneUserJidClass?.isInstance(jid) == true

    /** Unwraps the contact-data holder when the contact stores one. */
    fun contactData(contact: Any): Any = contact

    enum class Outcome {
        AVAILABLE,
        CONTACT_CLASS_MISSING,
        CONTACT_CLASS_AMBIGUOUS,
        CONTACT_DATA_CLASS_MISSING,
        CONTACT_DATA_CLASS_AMBIGUOUS,
        JID_CLASS_MISSING,
        JID_CLASS_AMBIGUOUS,
        PHONE_JID_METHOD_AMBIGUOUS,
        PHONE_JID_FIELD_AMBIGUOUS,
        USER_JID_FIELD_MISSING,
        USER_JID_FIELD_AMBIGUOUS,
        ERROR,
    }

    /** Java-friendly result holder; Kotlin's Pair extensions are not callable from Java. */
    class Resolution(val access: ModernContactAccess?, val outcome: Outcome) {
        val available: Boolean get() = access != null
    }

    companion object {
        private const val TAG = "WA-X ContactAccess"

        const val ANCHOR_CONTACT = "problematic contact:"
        const val CONTACT_DATA_SUFFIX = "WaContactData"
        const val JID_SUFFIX = "jid.Jid"
        const val ANCHOR_PHONE_JID = "WaJidMapRepository/getPhoneJidByAccountUserJid"



        /**
         * Resolves the access chain once per process. Returns null together
         * with the reason, so the caller can report an honest state instead of
         * pretending a feature is wired.
         */
        @JvmStatic
        fun resolve(context: android.content.Context): Resolution {
            val classLoader = context.classLoader
            return try {
                DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                    val contactData = when (val matches = CandidateSelection.from(dex.findClass {
                        matcher { addUsingString(ANCHOR_CONTACT, StringMatchType.Contains) }
                    })) {
                        CandidateSelection.Missing -> return Resolution(null, Outcome.CONTACT_CLASS_MISSING)
                        CandidateSelection.Ambiguous -> return Resolution(null, Outcome.CONTACT_CLASS_AMBIGUOUS)
                        is CandidateSelection.Unique -> matches.candidate
                    }
                    val contactClass = contactData.getInstance(classLoader)
                    val dataData = when (val matches = CandidateSelection.from(dex.findClass {
                        matcher { className(CONTACT_DATA_SUFFIX, StringMatchType.EndsWith) }
                    })) {
                        CandidateSelection.Missing -> return Resolution(null, Outcome.CONTACT_DATA_CLASS_MISSING)
                        CandidateSelection.Ambiguous -> return Resolution(null, Outcome.CONTACT_DATA_CLASS_AMBIGUOUS)
                        is CandidateSelection.Unique -> matches.candidate
                    }
                    val dataClass = dataData.getInstance(classLoader)
                    val jidData = when (val matches = CandidateSelection.from(dex.findClass {
                        matcher { className(JID_SUFFIX, StringMatchType.EndsWith) }
                    })) {
                        CandidateSelection.Missing -> return Resolution(null, Outcome.JID_CLASS_MISSING)
                        CandidateSelection.Ambiguous -> return Resolution(null, Outcome.JID_CLASS_AMBIGUOUS)
                        is CandidateSelection.Unique -> matches.candidate
                    }
                    val jidClass = jidData.getInstance(classLoader)
                    // DexKit's returnType is a ClassData, not a Class.
                    val phoneJidMethod = when (val matches = CandidateSelection.from(dex.findMethod {
                        matcher { addUsingString(ANCHOR_PHONE_JID, StringMatchType.Contains) }
                    })) {
                        CandidateSelection.Missing -> null
                        CandidateSelection.Ambiguous ->
                            return Resolution(null, Outcome.PHONE_JID_METHOD_AMBIGUOUS)
                        is CandidateSelection.Unique -> matches.candidate
                    }
                    val phoneJidClass = phoneJidMethod?.returnType?.getInstance(classLoader)

                    // Mirror the legacy decision: the JID field lives on the
                    // contact-data class when the contact has no phone-JID
                    // field of its own, and on the contact class otherwise.
                    val phoneField = phoneJidClass?.let {
                        when (val matches = fieldsOfType(contactClass, it)) {
                            CandidateSelection.Missing -> null
                            CandidateSelection.Ambiguous ->
                                return Resolution(null, Outcome.PHONE_JID_FIELD_AMBIGUOUS)
                            is CandidateSelection.Unique -> matches.candidate
                        }
                    }
                    val (owner, field) = if (phoneField == null) {
                        val jidField = when (val matches = fieldsOfType(dataClass, jidClass)) {
                            CandidateSelection.Missing ->
                                return Resolution(null, Outcome.USER_JID_FIELD_MISSING)
                            CandidateSelection.Ambiguous ->
                                return Resolution(null, Outcome.USER_JID_FIELD_AMBIGUOUS)
                            is CandidateSelection.Unique -> matches.candidate
                        }
                        dataClass to jidField
                    } else {
                        val jidField = when (val matches = fieldsOfType(contactClass, jidClass)) {
                            CandidateSelection.Missing ->
                                return Resolution(null, Outcome.USER_JID_FIELD_MISSING)
                            CandidateSelection.Ambiguous ->
                                return Resolution(null, Outcome.USER_JID_FIELD_AMBIGUOUS)
                            is CandidateSelection.Unique -> matches.candidate
                        }
                        contactClass to jidField
                    }
                    field.isAccessible = true
                    Log.i(
                        TAG,
                        "Contact access resolved; JID field owner=" + owner.name,
                    )
                    Resolution(
                        ModernContactAccess(
                            contactClass, dataClass, jidClass, phoneJidClass, field,
                        ),
                        Outcome.AVAILABLE,
                    )
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Contact access resolver unavailable", failure)
                Resolution(null, Outcome.ERROR)
            }
        }

        private fun fieldsOfType(
            owner: Class<*>,
            type: Class<*>,
        ): CandidateSelection<java.lang.reflect.Field> =
            CandidateSelection.from(owner.declaredFields.filter { type.isAssignableFrom(it.type) })
    }
}
