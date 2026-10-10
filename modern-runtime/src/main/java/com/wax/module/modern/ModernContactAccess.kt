package com.wax.module.modern

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

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
    /** The contact's nested data holder, null when the JID lives on the contact itself. */
    private val contactDataField: java.lang.reflect.Field?,
    /** The JID class, exposed so a JID accessor can be resolved from it. */
    val jidClass: Class<*>,
    private val phoneUserJidClass: Class<*>?,
    private val userJidField: java.lang.reflect.Field,
) {
    /** Reads only a JID field on its actual declaring object (never the wrong owner). */
    fun userJid(contact: Any): Any? {
        val owner = contactData(contact) ?: return null
        return try {
            userJidField.get(owner)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Contact JID unreadable: ${failure.javaClass.simpleName}")
            null
        }
    }

    /** True when the value is a JID (as opposed to a LID-shaped value). */
    fun isPhoneJid(jid: Any?): Boolean = jid != null && phoneUserJidClass?.isInstance(jid) == true

    /** Safely unwraps the data holder selected by the resolver, or returns null. */
    fun contactData(contact: Any): Any? {
        if (!contactClass.isInstance(contact)) return null
        val dataField = contactDataField ?: return contact
        return try {
            dataField.get(contact)?.takeIf { dataField.type.isInstance(it) }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Contact data unreadable: ${failure.javaClass.simpleName}")
            null
        }
    }

    enum class Outcome {
        AVAILABLE,
        CONTACT_CLASS_MISSING,
        CONTACT_DATA_CLASS_MISSING,
        JID_CLASS_MISSING,
        USER_JID_FIELD_MISSING,
        USER_JID_FIELD_AMBIGUOUS,
        CONTACT_DATA_FIELD_MISSING,
        CONTACT_DATA_FIELD_AMBIGUOUS,
        PHONE_JID_FIELD_AMBIGUOUS,
        PHONE_JID_METHOD_AMBIGUOUS,

        /**
         * More than one class satisfied the query. Picking one of them would be
         * the "arbitrary first match" this resolver exists to avoid, so it fails
         * and says so instead.
         */
        CONTACT_CLASS_AMBIGUOUS,
        CONTACT_DATA_CLASS_AMBIGUOUS,
        JID_CLASS_AMBIGUOUS,

        /**
         * The candidate was found in the target DEX but its defining loader is
         * not the target's, so the class it yields is not the one WhatsApp runs.
         */
        CLASS_LOADER_MISMATCH,
        ERROR,
    }

    /**
     * What the resolver actually saw, for the diagnostic record.
     *
     * Counts and the query that produced them, never a class name that could
     * carry contact data: this travels into an exported ZIP.
     */
    data class Evidence(
        val contactCandidates: Int,
        val contactDataCandidates: Int,
        val jidCandidates: Int,
        val contactDataAnchor: String,
        val contactDataLoaderMatches: Boolean,
        val jidLoaderMatches: Boolean,
    )

    /** Java-friendly result holder; Kotlin's Pair extensions are not callable from Java. */
    class Resolution(
        val access: ModernContactAccess?,
        val outcome: Outcome,
        val evidence: Evidence? = null,
    ) {
        val available: Boolean get() = access != null
    }

    /**
     * How the contact-data class is looked for.
     *
     * The legacy resolver searched for a class that *uses* the string
     * `WaContactData`, not for a class *named* that. The modern resolver had
     * switched to the name query, which no WhatsApp build satisfies, so the
     * chain failed at `CONTACT_DATA_CLASS_MISSING` while the anchor that
     * actually works sat in the legacy code the whole time. Both queries are
     * tried, in the legacy order first, and which one answered is reported.
     */
    enum class ContactDataAnchor {
        USING_STRING,
        CLASS_NAME,
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
         *
         * Selection fails closed: a query that matches several classes is
         * reported as ambiguous rather than resolved by taking the first, and a
         * class whose defining loader is not the target's is rejected before it
         * can be reflected on.
         */
        @JvmStatic
        fun resolve(context: android.content.Context): Resolution {
            val classLoader = context.classLoader
            var evidence: Evidence? = null
            return try {
                DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                    val contactCandidates =
                        dex.findClass {
                            matcher { addUsingString(ANCHOR_CONTACT, StringMatchType.Contains) }
                        }
                    val contactData =
                        singleOrNull(contactCandidates)
                            ?: return Resolution(
                                null,
                                if (contactCandidates.isEmpty()) {
                                    Outcome.CONTACT_CLASS_MISSING
                                } else {
                                    Outcome.CONTACT_CLASS_AMBIGUOUS
                                },
                                evidence,
                            )
                    val contactClass = contactData.getInstance(classLoader)

                    // Legacy order first: a class that *uses* the marker string.
                    val byString =
                        dex.findClass {
                            matcher { addUsingString(CONTACT_DATA_SUFFIX, StringMatchType.EndsWith) }
                        }
                    val dataCandidates =
                        if (byString.isNotEmpty()) {
                            byString
                        } else {
                            dex.findClass {
                                matcher { className(CONTACT_DATA_SUFFIX, StringMatchType.EndsWith) }
                            }
                        }
                    val anchor =
                        if (byString.isNotEmpty()) {
                            ContactDataAnchor.USING_STRING
                        } else {
                            ContactDataAnchor.CLASS_NAME
                        }
                    val dataData =
                        singleOrNull(dataCandidates)
                            ?: return Resolution(
                                null,
                                if (dataCandidates.isEmpty()) {
                                    Outcome.CONTACT_DATA_CLASS_MISSING
                                } else {
                                    Outcome.CONTACT_DATA_CLASS_AMBIGUOUS
                                },
                                Evidence(
                                    contactCandidates = contactCandidates.size,
                                    contactDataCandidates = dataCandidates.size,
                                    jidCandidates = 0,
                                    contactDataAnchor = anchor.name,
                                    contactDataLoaderMatches = false,
                                    jidLoaderMatches = false,
                                ),
                            )
                    val dataClass = dataData.getInstance(classLoader)

                    val jidCandidates =
                        dex.findClass {
                            matcher { className(JID_SUFFIX, StringMatchType.EndsWith) }
                        }
                    val jidData =
                        singleOrNull(jidCandidates)
                            ?: return Resolution(
                                null,
                                if (jidCandidates.isEmpty()) {
                                    Outcome.JID_CLASS_MISSING
                                } else {
                                    Outcome.JID_CLASS_AMBIGUOUS
                                },
                                Evidence(
                                    contactCandidates = contactCandidates.size,
                                    contactDataCandidates = dataCandidates.size,
                                    jidCandidates = jidCandidates.size,
                                    contactDataAnchor = anchor.name,
                                    contactDataLoaderMatches = true,
                                    jidLoaderMatches = false,
                                ),
                            )
                    val jidClass = jidData.getInstance(classLoader)

                    evidence =
                        Evidence(
                            contactCandidates = contactCandidates.size,
                            contactDataCandidates = dataCandidates.size,
                            jidCandidates = jidCandidates.size,
                            contactDataAnchor = anchor.name,
                            contactDataLoaderMatches = isTargetClass(dataClass, classLoader),
                            jidLoaderMatches = isTargetClass(jidClass, classLoader),
                        )
                    // A class resolved from the target DEX but defined by another
                    // loader is not the class WhatsApp runs. Catching it here is
                    // the difference between a clear failure and a ClassCast
                    // exception somewhere further down.
                    if (!evidence.contactDataLoaderMatches) {
                        return Resolution(null, Outcome.CLASS_LOADER_MISMATCH, evidence)
                    }
                    if (!evidence.jidLoaderMatches) {
                        return Resolution(null, Outcome.CLASS_LOADER_MISMATCH, evidence)
                    }

                    if (!isTargetClass(contactClass, classLoader)) {
                        return Resolution(null, Outcome.CLASS_LOADER_MISMATCH, evidence)
                    }

                    // A method match is not a guarantee of uniqueness. Never
                    // derive a contact owner from an arbitrary first result.
                    val phoneMethods =
                        dex.findMethod {
                            matcher { addUsingString(ANCHOR_PHONE_JID, StringMatchType.Contains) }
                        }
                    if (phoneMethods.size > 1) {
                        return Resolution(null, Outcome.PHONE_JID_METHOD_AMBIGUOUS, evidence)
                    }
                    // DexKit's returnType is a ClassData, not a Class.
                    val phoneJidClass = phoneMethods.singleOrNull()?.returnType?.getInstance(classLoader)
                    if (phoneJidClass != null && !isTargetClass(phoneJidClass, classLoader)) {
                        return Resolution(null, Outcome.CLASS_LOADER_MISMATCH, evidence)
                    }

                    val plan = selectFieldPlan(contactClass, dataClass, jidClass, phoneJidClass)
                    if (plan.outcome != Outcome.AVAILABLE) {
                        return Resolution(null, plan.outcome, evidence)
                    }
                    val field = plan.jidField
                        ?: return Resolution(null, Outcome.USER_JID_FIELD_MISSING, evidence)
                    field.isAccessible = true
                    plan.contactDataField?.isAccessible = true
                    Log.i(
                        TAG,
                        "Contact access resolved; JID owner=" +
                            if (plan.contactDataField == null) "CONTACT" else "CONTACT_DATA",
                    )
                    Resolution(
                        ModernContactAccess(
                            contactClass,
                            plan.contactDataField,
                            jidClass,
                            phoneJidClass,
                            field,
                        ),
                        Outcome.AVAILABLE,
                        evidence,
                    )
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Contact access resolver unavailable", failure)
                Resolution(null, Outcome.ERROR, evidence)
            }
        }

        /**
         * One candidate, or null when the query matched nothing or too much.
         *
         * Returning null for both cases is deliberate: the caller maps the count
         * to a missing or an ambiguous outcome, and neither ever results in an
         * arbitrary class being reflected on.
         */
private fun <T> singleOrNull(candidates: List<T>): T? =
            if (candidates.size == 1) candidates[0] else null

        /** True when the class is defined by the loader the target actually runs. */
        private fun isTargetClass(
            candidate: Class<*>,
            classLoader: ClassLoader,
        ): Boolean = candidate.classLoader == classLoader

        /**
         * Reflect the same direct-vs-nested decision as the legacy wrapper.
         * Missing or multiple candidates fail closed, never select the first
         * obfuscated field that happens to have a compatible type.
         */
        internal data class FieldPlan(
            val contactDataField: java.lang.reflect.Field?,
            val jidField: java.lang.reflect.Field?,
            val outcome: Outcome,
        )

        internal fun selectFieldPlan(
            contactClass: Class<*>,
            dataClass: Class<*>,
            jidClass: Class<*>,
            phoneJidClass: Class<*>?,
        ): FieldPlan {
            fun fieldsOfType(owner: Class<*>, type: Class<*>): List<java.lang.reflect.Field> =
                owner.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    type.isAssignableFrom(it.type) }

            val phoneFields = phoneJidClass?.let { fieldsOfType(contactClass, it) }.orEmpty()
            if (phoneFields.size > 1) {
                return FieldPlan(null, null, Outcome.PHONE_JID_FIELD_AMBIGUOUS)
            }

            val nested = phoneFields.isEmpty()
            val contactDataFields =
                if (nested) fieldsOfType(contactClass, dataClass) else emptyList()
            if (nested && contactDataFields.isEmpty()) {
                return FieldPlan(null, null, Outcome.CONTACT_DATA_FIELD_MISSING)
            }
            if (contactDataFields.size > 1) {
                return FieldPlan(null, null, Outcome.CONTACT_DATA_FIELD_AMBIGUOUS)
            }

            val owner = if (nested) dataClass else contactClass
            val jidFields = fieldsOfType(owner, jidClass)
            if (jidFields.isEmpty()) {
                return FieldPlan(null, null, Outcome.USER_JID_FIELD_MISSING)
            }
            if (jidFields.size > 1) {
                return FieldPlan(null, null, Outcome.USER_JID_FIELD_AMBIGUOUS)
            }
            return FieldPlan(contactDataFields.singleOrNull(), jidFields.single(), Outcome.AVAILABLE)
        }
    }
}
