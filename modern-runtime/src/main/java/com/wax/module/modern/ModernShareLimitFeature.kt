package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Constructor

/**
 * API 102 replacement for the legacy ShareLimit constructor hook.
 *
 * The resolver must be unique, the constructor must accept a primitive int
 * in its first slot, and every handle is owned by the modern registry.
 */
object ModernShareLimitFeature {
    const val FEATURE_ID = "share_limit"
    const val ENABLE_KEY = "removeforwardlimit"

    enum class Outcome {
        DISABLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
        INSTALLED,
    }

    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!preferences.getBoolean(ENABLE_KEY, false)) return Outcome.DISABLED

        val candidateClasses =
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                dex.findClass {
                    matcher {
                        usingStrings("MultiSelectionLimitInfo")
                    }
                }.toList()
            }
        if (candidateClasses.isEmpty()) return Outcome.RESOLVER_MISSING
        if (candidateClasses.size != 1) return Outcome.RESOLVER_AMBIGUOUS

        val constructors =
            candidateClasses.single().getInstance(target.classLoader).declaredConstructors
                .filter(ModernShareLimitPolicy::matchesConstructor)
                .sortedBy { it.parameterTypes.joinToString(",") { type -> type.name } }
        if (constructors.isEmpty() || constructors.size > 8) return Outcome.UNSAFE_SIGNATURE

        hooks.installFeature(
            FEATURE_ID,
            constructors.mapIndexed { index, constructor ->
                ModernHookRegistry.Registration("share_limit.constructor.$index") {
                    val handle =
                        ModernHookBridge(framework).intercept(
                            constructor,
                            "wax.modern.share_limit.constructor.$index",
                        ) { chain ->
                            // API102 does not expose the legacy mutable param.args array.
                            // Forward a copy through the official Chain.proceed(args) API.
                            chain.proceed(
                                ModernShareLimitPolicy.forwardedArgs(
                                    chain.args.toTypedArray(),
                                    true,
                                ),
                            )
                        }
                    ModernHookRegistry.Handle { handle.unhook() }
                }
            },
        )
        return Outcome.INSTALLED
    }
}
