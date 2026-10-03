package com.wmods.wppenhacer.xposed.bridge

import android.content.Context
import android.os.Binder
import android.os.Process
import com.wmods.wppenhacer.BuildConfig

object BridgeAccess {

    private const val SETTINGS_PROVIDER_PACKAGE = "com.android.providers.settings"

    private val allowedClientPackages = setOf(
        BuildConfig.APPLICATION_ID,
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    @JvmStatic
    fun isCallerAllowed(context: Context, allowSettingsProvider: Boolean = false): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return true

        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return false
        if (packages.any { it in allowedClientPackages }) return true

        return allowSettingsProvider && packages.any { it == SETTINGS_PROVIDER_PACKAGE }
    }

    @JvmStatic
    fun enforceCaller(context: Context, allowSettingsProvider: Boolean = false) {
        if (!isCallerAllowed(context, allowSettingsProvider)) {
            throw SecurityException("Unauthorized WaEnhancer bridge caller uid=${Binder.getCallingUid()}")
        }
    }
}
