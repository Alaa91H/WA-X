package com.wax.module.modern

import android.content.Context
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.util.concurrent.CopyOnWriteArraySet
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy ContactItemListener contact-bind fan-out bus.
 *
 * Resolves the same hook targets from repo-derived anchors (never guessed
 * obfuscated names): the status-change method using
 * `ConversationViewFiller/setParentGroupProfilePhoto`, the view-holder class
 * using `"not recyclable"`, the holder field typed to that class on the
 * method's superclass, and the [View] field inside it. Every resolution step
 * requires uniqueness; ambiguity or a signature outside the observed 6..8
 * parameter range disables only this bus with a fixed outcome.
 *
 * The bus delivers the raw bound contact object plus its item [View]. JID
 * interpretation stays with the consumer migration (ShowOnline, W3): the
 * legacy `WaContactWpp` JID chain needs its own resolver evidence and is not
 * re-derived here. Always-on infrastructure: there is no user toggle and
 * therefore no in-WhatsApp control; an empty bus returns early.
 */
object ModernContactItemListenerFeature {
    const val FEATURE_ID = "contact_item_listener"
    const val ANCHOR_ON_CHANGE_STATUS = "ConversationViewFiller/setParentGroupProfilePhoto"
    const val ANCHOR_ABS_VIEW_HOLDER = "not recyclable"
    const val MIN_PARAMS = 6
    const val MAX_PARAMS = 8
    private const val TAG = "WA-X ContactBus102"

    enum class Outcome {
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
        ERROR,
    }

    /** Consumer callback. Listener exceptions are isolated per listener. */
    fun interface OnContactBind {
        fun onBind(contact: Any, itemView: View)
    }

    private val listeners = CopyOnWriteArraySet<OnContactBind>()

    @JvmStatic
    fun addListener(listener: OnContactBind) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: OnContactBind) {
        listeners.remove(listener)
    }

    @JvmStatic
    fun isPlausibleOnChangeStatus(paramCount: Int): Boolean =
        paramCount in MIN_PARAMS..MAX_PARAMS

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
    ): Outcome {
        try {
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                val methods = dex.findMethod {
                    matcher {
                        addUsingString(ANCHOR_ON_CHANGE_STATUS, StringMatchType.Contains)
                    }
                }
                if (methods.isEmpty()) return Outcome.RESOLVER_MISSING
                if (methods.size != 1) return Outcome.RESOLVER_AMBIGUOUS
                val onChangeStatus = try {
                    methods[0].getMethodInstance(target.classLoader)
                } catch (resolveFailure: Throwable) {
                    Log.w(TAG, "Contact bind method unresolvable", resolveFailure)
                    return Outcome.RESOLVER_MISSING
                }
                if (!isPlausibleOnChangeStatus(onChangeStatus.parameterCount)) {
                    return Outcome.UNSAFE_SIGNATURE
                }
                val holders = dex.findClass {
                    matcher {
                        addUsingString(ANCHOR_ABS_VIEW_HOLDER, StringMatchType.Contains)
                    }
                }
                if (holders.isEmpty()) return Outcome.RESOLVER_MISSING
                if (holders.size != 1) return Outcome.RESOLVER_AMBIGUOUS
                val holderClass = try {
                    holders[0].getInstance(target.classLoader)
                } catch (resolveFailure: Throwable) {
                    Log.w(TAG, "Contact holder class unresolvable", resolveFailure)
                    return Outcome.RESOLVER_MISSING
                }
                val holderField = findFieldOfType(
                    onChangeStatus.declaringClass.superclass, holderClass)
                    ?: return Outcome.UNSAFE_SIGNATURE
                holderField.isAccessible = true
                val viewField = findViewField(holderClass)
                    ?: return Outcome.UNSAFE_SIGNATURE
                viewField.isAccessible = true
                hooks.installFeature(FEATURE_ID, listOf(
                    ModernHookRegistry.Registration("contact.bind_fanout") {
                        val handle = ModernHookBridge(framework).intercept(
                            onChangeStatus, "wax.modern.contact_item.bind") { chain ->
                            val result = chain.proceed()
                            if (listeners.isEmpty()) return@intercept result
                            val args = chain.args
                            if (args.isEmpty()) return@intercept result
                            val contact = args[0] ?: return@intercept result
                            val holder = try {
                                holderField.get(chain.thisObject)
                            } catch (fieldFailure: Throwable) {
                                return@intercept result
                            } ?: return@intercept result
                            val itemView = try {
                                viewField.get(holder) as? View
                            } catch (fieldFailure: Throwable) {
                                return@intercept result
                            } ?: return@intercept result
                            for (listener in listeners) {
                                try {
                                    listener.onBind(contact, itemView)
                                } catch (listenerFailure: Throwable) {
                                    // One optional consumer cannot break the bus.
                                    Log.w(TAG, "Contact bind listener failed", listenerFailure)
                                }
                            }
                            result
                        }
                        ModernHookRegistry.Handle { handle.unhook() }
                    }))
                return Outcome.INSTALLED
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Contact bind bus unavailable", failure)
            return Outcome.ERROR
        }
    }

    private fun findFieldOfType(owner: Class<*>?, type: Class<*>?): Field? {
        if (owner == null || type == null) return null
        return owner.declaredFields.firstOrNull { type.isAssignableFrom(it.type) }
    }

    private fun findViewField(holderClass: Class<*>?): Field? {
        if (holderClass == null) return null
        return holderClass.declaredFields.firstOrNull { it.type == View::class.java }
    }
}
