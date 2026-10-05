package com.wax.module.provider

import android.os.Binder
import android.os.Process
import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import com.wax.module.BuildConfig
import com.wax.module.xposed.core.FeatureLoader

class RemotePreferenceProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID + ".preferences",
    arrayOf(BuildConfig.APPLICATION_ID + "_preferences")
) {
    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean {
        if (write) return false

        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return true

        val packages = context?.packageManager?.getPackagesForUid(callingUid).orEmpty()
        return packages.any {
            it == FeatureLoader.PACKAGE_WPP || it == FeatureLoader.PACKAGE_BUSINESS
        }
    }
}
