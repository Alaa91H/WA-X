package com.wax.module.modern

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Modern runtime access to WhatsApp's message objects.
 *
 * The legacy path wraps messages in `FMessageWpp`, whose `Key` wrapper reads
 * obfuscated fields (`A01`, `A00`, `A02`) through `XposedHelpers`. Those
 * literal field names are the one place in the codebase where obfuscated
 * members are hardcoded, and a modern port must not simply copy them.
 *
 * This accessor therefore resolves the message class, its key field, the key
 * class and the key's own fields from evidence already in the repository:
 *
 * - message class: the class using `FMessage/getSenderUserJid/key.id`;
 * - key class: a three-field class with a `toString` using `"Key"`, exactly
 *   the legacy `loadMessageKeyField` evidence;
 * - message-key field: the field on the message class typed to that key class;
 * - within the key: the id field by type (String), the sender-JID field by
 *   assignability to the JID class, and the from-me flag by type (boolean).
 *
 * When a member cannot be resolved the accessor reports it instead of falling
 * back to a hardcoded name, because reading the wrong field would silently
 * attribute a message to the wrong sender.
 */
class ModernMessageAccess private constructor(
    val messageClass: Class<*>,
    private val keyField: java.lang.reflect.Field,
    private val messageIdField: java.lang.reflect.Field?,
    private val senderJidField: java.lang.reflect.Field?,
    private val fromMeField: java.lang.reflect.Field?,
) {
    data class MessageKey(
        val messageId: String?,
        val senderJid: Any?,
        val fromMe: Boolean,
    )

    /** Reads the message key, or null when the key cannot be read. */
    fun key(message: Any): MessageKey? {
        val key = try {
            keyField.get(message)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Message key unreadable", failure)
            null
        } ?: return null
        return MessageKey(
            messageId = messageIdField?.let { read(it, key) as? String },
            senderJid = senderJidField?.let { read(it, key) },
            fromMe = fromMeField?.let { read(it, key) as? Boolean } ?: false,
        )
    }

    private fun read(field: java.lang.reflect.Field, target: Any): Any? = try {
        field.get(target)
    } catch (failure: Throwable) {
        if (failure is VirtualMachineError) throw failure
        Log.w(TAG, "Message key field unreadable", failure)
        null
    }

    companion object {
        private const val TAG = "WA-X MessageAccess"

        const val ANCHOR_MESSAGE_CLASS = "FMessage/getSenderUserJid/key.id"
        const val ANCHOR_KEY_TOSTRING = "Key"
        const val KEY_FIELD_COUNT = 3
        const val JID_SUFFIX = "jid.Jid"

        enum class Outcome {
            AVAILABLE,
            MESSAGE_CLASS_MISSING,
            KEY_CLASS_MISSING,
            KEY_FIELD_MISSING,
            ERROR,
        }
        /** Java-friendly result holder; Kotlin's Pair extensions are not callable from Java. */
        class Resolution(val access: ModernMessageAccess?, val outcome: Outcome) {
            val available: Boolean get() = access != null
        }

        /**
         * Resolves the access chain once per process. Returns null with the
         * reason when a step fails, so callers report an honest state.
         */
        @JvmStatic
        fun resolve(context: android.content.Context): Resolution {
            val classLoader = context.classLoader
            return try {
                DexKitBridge.create(context.applicationInfo.sourceDir).use { dex ->
                    val messageData = dex.findClass {
                        matcher {
                            addUsingString(ANCHOR_MESSAGE_CLASS, StringMatchType.Contains)
                        }
                    }.firstOrNull() ?: return Resolution(null, Outcome.MESSAGE_CLASS_MISSING)
                    val messageClass = messageData.getInstance(classLoader)
                    val keyData = dex.findClass {
                        matcher {
                            fieldCount(KEY_FIELD_COUNT)
                            addMethod {
                                addUsingString(ANCHOR_KEY_TOSTRING)
                                name("toString")
                            }
                        }
                    }.firstOrNull() ?: return Resolution(null, Outcome.KEY_CLASS_MISSING)
                    val keyClass = keyData.getInstance(classLoader)
                    val keyField = messageClass.declaredFields
                        .firstOrNull { it.type == keyClass }
                        ?: return Resolution(null, Outcome.KEY_FIELD_MISSING)
                    keyField.isAccessible = true
                    val jidData = dex.findClass {
                        matcher { className(JID_SUFFIX, StringMatchType.EndsWith) }
                    }.firstOrNull()
                    val jidClass = jidData?.getInstance(classLoader) as? Class<*>
                    val messageIdField = keyClass.declaredFields
                        .firstOrNull { it.type == String::class.java }
                    val senderJidField = jidClass?.let { jid ->
                        keyClass.declaredFields
                            .firstOrNull { jid.isAssignableFrom(it.type) }
                    }
                    val fromMeField = keyClass.declaredFields
                        .firstOrNull { it.type == java.lang.Boolean.TYPE }
                    listOfNotNull(messageIdField, senderJidField, fromMeField)
                        .forEach { it.isAccessible = true }
                    Log.i(
                        TAG,
                        "Message access resolved: id=" + (messageIdField != null) +
                            " senderJid=" + (senderJidField != null) +
                            " fromMe=" + (fromMeField != null),
                    )
                    Resolution(
                        ModernMessageAccess(
                            messageClass, keyField, messageIdField,
                            senderJidField, fromMeField,
                        ),
                        Outcome.AVAILABLE,
                    )
                }
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                Log.w(TAG, "Message access resolver unavailable", failure)
                Resolution(null, Outcome.ERROR)
            }
        }
    }
}