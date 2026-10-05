package com.wax.module.xposed.features.others

import com.wax.module.xposed.core.Feature
import android.content.SharedPreferences 

class DebugFeature(classLoader: ClassLoader, preferences:SharedPreferences) :
    Feature(classLoader, preferences) {

    override fun doHook() {
    }


    override fun getPluginName(): String {
        return "Debug Feature"
    }
}
