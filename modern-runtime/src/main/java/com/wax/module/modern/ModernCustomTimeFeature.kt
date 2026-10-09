package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Calendar

/**
 * First user-visible feature to use modern API 102 hooks end-to-end.
 * Disabled by default, diagnostics-only canary until verified on both target apps.
 */
object ModernCustomTimeFeature {
    const val FEATURE_ID = "custom_time"
    const val ENABLE_KEY = "modern.feature.custom_time.enabled"

    enum class Outcome {
        DISABLED,
        INSTALLED,
        RESOLVER_MISSING,
    }

    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!preferences.getBoolean(ENABLE_KEY, false)) return Outcome.DISABLED

        val method =
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                dex.findMethod {
                    matcher {
                        usingNumbers(223, 224)
                        modifiers = Modifier.STATIC
                        returnType = "java.lang.String"
                        paramCount = 2
                        paramTypes(null, "java.util.Calendar")
                    }
                }.singleOrNull()?.getMethodInstance(target.classLoader)
            } ?: return Outcome.RESOLVER_MISSING

        requireMethodShape(method)
        val seconds = preferences.getBoolean("segundos", false)
        val amPm = preferences.getBoolean("ampm", false)
        val template = preferences.getString("text_in_hour", "[TIME]")

        hooks.installFeature(
            FEATURE_ID,
            listOf(
                ModernHookRegistry.Registration("custom_time.format") {
                    val handle =
                        ModernHookBridge(framework).intercept(
                            method,
                            "wax.modern.custom_time.format",
                        ) { chain ->
                            val original = chain.proceed()
                            val calendar = chain.getArg(1) as? Calendar
                            if (calendar == null) {
                                original
                            } else {
                                ModernTimeFormatter.render(calendar, seconds, amPm, template)
                            }
                        }
                    ModernHookRegistry.Handle { handle.unhook() }
                },
            ),
        )
        return Outcome.INSTALLED
    }

    private fun requireMethodShape(method: Method) {
        require(
            Modifier.isStatic(method.modifiers) &&
                method.returnType == String::class.java &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Calendar::class.java,
        ) { "CustomTime resolver returned an incompatible method" }
    }
}
