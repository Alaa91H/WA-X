package com.wax.module.modern

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

/**
 * API 102 port of the legacy HideChat feature.
 *
 * The legacy version swaps the archive chat view for one that is always
 * `GONE` and ignores every `setVisibility` call, which hides archived chats
 * without touching the archive itself.
 *
 * Targets come from the repository's own resolver evidence, in the same order
 * the legacy resolver tries them: the class using
 * `archive/set-content-indicator-to-empty`, falling back to
 * `archive/Unsupported mode in ArchivePreviewView:`. A unique match is
 * required; ambiguity disables only this feature instead of hooking a class
 * that may not be the archive view.
 *
 * The mode is the user's existing `typearchive` preference: `0` disabled,
 * `1` hide after click count, `2` hold the title — anything non-zero hides
 * archived chats, exactly as the legacy feature behaved.
 */
object ModernHideChatFeature {
    const val FEATURE_ID = "hide_chat"
    const val PREF_ARCHIVE_MODE = "typearchive"
    const val MODE_DISABLED = "0"
    const val ANCHOR_PRIMARY = "archive/set-content-indicator-to-empty"
    const val ANCHOR_FALLBACK = "archive/Unsupported mode in ArchivePreviewView:"
    private const val TAG = "WA-X HideChat102"

    enum class Outcome {
        DISABLED,
        RESOLVER_MISSING,
        RESOLVER_AMBIGUOUS,
        VIEW_FIELD_MISSING,
        ERROR,
    }

    @JvmStatic
    fun isEnabled(mode: String?): Boolean = mode != null && mode != MODE_DISABLED

    @JvmStatic
    fun install(
        target: Context,
        framework: XposedInterface,
        hooks: ModernHookRegistry,
        preferences: SharedPreferences,
    ): Outcome {
        if (!isEnabled(preferences.getString(PREF_ARCHIVE_MODE, MODE_DISABLED))) {
            return Outcome.DISABLED
        }
        val classLoader = target.classLoader
        val archiveClass = try {
            DexKitBridge.create(target.applicationInfo.sourceDir).use { dex ->
                val primary = dex.findClass {
                    matcher { addUsingString(ANCHOR_PRIMARY, StringMatchType.Contains) }
                }
                val matches = if (primary.isNotEmpty()) {
                    primary
                } else {
                    dex.findClass {
                        matcher { addUsingString(ANCHOR_FALLBACK, StringMatchType.Contains) }
                    }
                }
                when (matches.size) {
                    0 -> return Outcome.RESOLVER_MISSING
                    1 -> matches[0].getInstance(classLoader)
                    else -> return Outcome.RESOLVER_AMBIGUOUS
                }
            }
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "Archive view resolver unavailable", failure)
            return Outcome.RESOLVER_MISSING
        }
        // The legacy feature replaces the single View-typed field on the class.
        val viewField = archiveClass.declaredFields.firstOrNull { it.type == View::class.java }
            ?: return Outcome.VIEW_FIELD_MISSING
        viewField.isAccessible = true
        val constructors = archiveClass.declaredConstructors
        if (constructors.isEmpty()) return Outcome.RESOLVER_MISSING

        try {
            hooks.installFeature(
                FEATURE_ID,
                constructors.mapIndexed { index, constructor ->
                    ModernHookRegistry.Registration("hide_chat.archive.$index") {
                        val handle = ModernHookBridge(framework).intercept(
                            constructor, "wax.modern.hide_chat.archive.$index",
                        ) { chain ->
                            val instance = chain.thisObject
                            val result = chain.proceed()
                            if (instance != null) {
                                try {
                                    viewField.set(instance, HiddenView(chain.thisObject))
                                } catch (failure: Throwable) {
                                    if (failure is VirtualMachineError) throw failure
                                    Log.w(TAG, "Could not hide the archive view", failure)
                                }
                            }
                            result
                        }
                        ModernHookRegistry.Handle { handle.unhook() }
                    }
                },
            )
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            Log.w(TAG, "HideChat hook unavailable", failure)
            return Outcome.ERROR
        }
        return Outcome.INSTALLED
    }

    /**
     * A view that is always gone. `setVisibility` is a no-op, which is what
     * stops the archive from re-showing itself after WhatsApp lays it out.
     */
    class HiddenView(context: Context) : View(context) {
        init {
            visibility = GONE
        }

        override fun setVisibility(visibility: Int) = Unit
    }
}