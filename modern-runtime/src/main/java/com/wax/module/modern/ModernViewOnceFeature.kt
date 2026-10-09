package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy ViewOnce feature.
 *
 * The legacy version keeps a viewed view-once message visible by rewriting the
 * caller's view-state argument from 1 to 0 when the message did not come from
 * this account. It resolves its targets through a four-step chain: the method
 * using `INSERT_VIEW_ONCE_SQL`, the interface it invokes whose declaring class
 * declares exactly two methods, the classes implementing that interface, and
 * finally their single-`int`/void methods.
 *
 * That chain is reproduced here with the same evidence and the same shape
 * checks. Two guards are added where the legacy resolver would silently accept
 * a wrong target:
 *
 * - every hook target must take a primitive `int` as its only argument and
 *   return `void`, because the hook rewrites that argument;
 * - the rewritten value is only produced when the message key actually
 *   resolves, so an unreadable key leaves the message untouched rather than
 *   guessing who sent it.
 *
 * Opt-in through the user's existing `viewonce` switch; nothing happens when
 * it is off.
 */
object ModernViewOnceFeature {
    const val FEATURE_ID = "view_once"
    const val PREF_ENABLE = "viewonce"
    const val ANCHOR_VIEW_ONCE_SQL = "INSERT_VIEW_ONCE_SQL"
    const val INTERFACE_METHOD_COUNT = 2
    const val STATE_VIEWED = 1
    const val STATE_UNVIEWED = 0
    private const val TAG = "WA-X ViewOnce102"

    enum class Outcome {
        DISABLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        NO_TARGETS,
        ERROR,
    }

    /** Pure decision: does this message state need rewriting? */
    @JvmStatic
    fun shouldRewrite(
        stateValue: Int,
        fromMe: Boolean,
    ): Boolean = stateValue == STATE_VIEWED && !fromMe

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
        messageAccess: ModernMessageAccess?,
    ): Outcome {
        if (!preferences.getBoolean(PREF_ENABLE, false)) return Outcome.DISABLED
        if (messageAccess == null) return Outcome.RESOLVER_MISSING

        val classLoader = target.classLoader
        val targets = try {
            resolveTargets(target, classLoader)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "View-once resolver unavailable", failure)
            return Outcome.RESOLVER_MISSING
        }
        if (targets.isEmpty()) return Outcome.NO_TARGETS

        try {
            hooks.installFeature(
                FEATURE_ID,
                targets.mapIndexed { index, method ->
                    ModernHookRegistry.Registration("view_once.state.$index") {
                        val handle = ModernHookBridge(framework).intercept(
                            method, "wax.modern.view_once.state.$index",
                        ) { chain ->
                            val args = chain.args.toMutableList()
                            val stateValue = args.firstOrNull() as? Int
                            if (stateValue == null) {
                                return@intercept chain.proceed()
                            }
                            val key = messageAccess.key(chain.thisObject)
                            // An unreadable key leaves the message untouched.
                            if (key == null) {
                                return@intercept chain.proceed()
                            }
                            if (shouldRewrite(stateValue, key.fromMe)) {
                                args[0] = STATE_UNVIEWED
                                return@intercept chain.proceed(args.toTypedArray())
                            }
                            chain.proceed()
                        }
                        ModernHookRegistry.Handle { handle.unhook() }
                    }
                },
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "View-once hook unavailable", failure)
            return Outcome.ERROR
        }
        return Outcome.INSTALLED
    }

    /**
     * Reproduces the legacy resolver chain and keeps only methods the hook can
     * actually rewrite.
     */
    private fun resolveTargets(target: Context, classLoader: ClassLoader): List<Method> =
        DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
            val anchor = dex.findMethod {
                matcher {
                    addUsingString(ANCHOR_VIEW_ONCE_SQL, StringMatchType.Contains)
                }
            }.firstOrNull() ?: return emptyList<Method>()
            val marker = anchor.getMethodInstance(classLoader)
            val markerInterface = marker.declaringClass.interfaces.firstOrNull { candidate ->
                Modifier.isInterface(candidate.modifiers) &&
                    candidate.declaredMethods.size == INTERFACE_METHOD_COUNT
            } ?: return emptyList<Method>()
            val found = mutableListOf<Method>()
            for (invoked in anchor.invokes) {
                val invokedMethod = invoked.getMethodInstance(classLoader)
                if (invokedMethod.declaringClass != markerInterface) continue
                for (implementor in dex.findClass {
                    matcher { addInterface(markerInterface.name) }
                }) {
                    val implementorClass = implementor.getInstance(classLoader)
                    for (method in implementorClass.declaredMethods) {
                        if (!isRewritableStateMethod(method)) continue
                        if (found.none { it == method }) found.add(method)
                    }
                }
            }
            found
        }

    /** The hook rewrites a primitive int argument; anything else is unsafe. */
    @JvmStatic
    fun isRewritableStateMethod(method: Method): Boolean =
        method.parameterCount == 1 &&
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
            method.returnType == Void.TYPE
}