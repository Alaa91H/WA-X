package com.wax.module.xposed.features.others

import android.content.SharedPreferences
import com.wax.module.xposed.core.Feature

class DebugFeature(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(classLoader, preferences) {
    override fun doHook() {
    }

    override fun getPluginName(): String = "Debug Feature"
}
