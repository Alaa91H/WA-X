package com.wmods.wppenhacer.provider

import android.os.Binder
import android.os.Process
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.xposed.core.FeatureLoader

class RemotePreferenceProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID + ".preferences",
    arrayOf(BuildConfig.APPLICATION_ID + "_preferences")
) {
    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean {
        if (write) return false

        val providerContext = context ?: return false
        val callingUid = Binder.getCallingUid()

        if (callingUid == Process.myUid()) return true

        val packages = providerContext.packageManager.getPackagesForUid(callingUid).orEmpty()
        return packages.any { packageName ->
            packageName == FeatureLoader.PACKAGE_WPP ||
                packageName == FeatureLoader.PACKAGE_BUSINESS ||
                packageName == BuildConfig.APPLICATION_ID
        }
    }
}
