package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * Two small API102 replacements matching the previous API93 DO_NOTHING hooks.
 * They are *not* enabled by the original Legacy flags: each modern pilot needs
 * an explicit opt-in, and its target resolver must be unique and void-returning.
 */
object ModernPresenceFeatures {
    const val FREEZE_KEY = "freezelastseen"
    const val DND_KEY = "dndmode"

    enum class Pilot(
        val featureId: String,
        val preferenceKey: String,
        val anchor: String,
        val matchType: StringMatchType,
    ) {
        FREEZE_LAST_SEEN(
            "freeze_last_seen",
            FREEZE_KEY,
            "presencestatemanager/setAvailable/new-state",
            StringMatchType.Contains,
        ),
        DND_MODE("dnd_mode", DND_KEY, "MessageHandler/start", StringMatchType.Equals),
    }

    enum class Outcome {
        DISABLED,
        INSTALLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        UNSAFE_SIGNATURE,
    }

    fun install(
        pilot: Pilot,
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!preferences.getBoolean(pilot.preferenceKey, false)) return Outcome.DISABLED

        // Resolution is on the async module bootstrap reporter, NEVER on WhatsApp UI.
        val matches =
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                dex.findMethod {
                    matcher {
                        addUsingString(pilot.anchor, pilot.matchType)
                    }
                }
            }
        if (matches.isEmpty()) return Outcome.RESOLVER_MISSING
        if (matches.size != 1) return Outcome.RESOLVER_AMBIGUOUS

        val method = matches.single().getMethodInstance(target.classLoader)
        if (!ModernVoidReplacementPolicy.isSafe(method)) return Outcome.UNSAFE_SIGNATURE

        hooks.installFeature(
            pilot.featureId,
            listOf(
                ModernHookRegistry.Registration("presence.${pilot.featureId}") {
                    val handle =
                        ModernHookBridge(framework).intercept(
                            method,
                            "wax.modern.${pilot.featureId}",
                        ) { _ ->
                            // Mirrors the old XC_MethodReplacement.DO_NOTHING semantics:
                            // suppress only verified void methods, never synthesize values.
                            null
                        }
                    ModernHookRegistry.Handle { handle.unhook() }
                },
            ),
        )
        return Outcome.INSTALLED
    }
}
