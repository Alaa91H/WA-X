package com.wax.module.xposed.features.privacy

import android.content.SharedPreferences
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime.getPrivBoolean
import com.wax.module.xposed.core.devkit.Unobfuscator.getMethodDescriptor
import com.wax.module.xposed.core.devkit.Unobfuscator.loadFreezeSeenMethod
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

class FreezeLastSeen(
    loader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(loader, preferences) {
    override fun doHook() {
        val freezeLastSeen = prefs.getBoolean("freezelastseen", false)
        val freezeLastSeenOption = getPrivBoolean("freezelastseen", false)
        val ghostmode = getPrivBoolean("ghostmode", false) && prefs.getBoolean("ghostmode", false)

        if (freezeLastSeen || freezeLastSeenOption || ghostmode) {
            val method = loadFreezeSeenMethod(classLoader)
            logDebug(getMethodDescriptor(method))
            XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
        }
    }

    override fun getPluginName(): String = "Freeze Last Seen"
}
