package com.wmods.wppenhacer.provider

import com.crossbowffs.remotepreferences.RemotePreferenceProvider
import com.wmods.wppenhacer.BuildConfig
import com.wmods.wppenhacer.xposed.bridge.BridgeAccess

class RemotePreferenceProvider : RemotePreferenceProvider(
    BuildConfig.APPLICATION_ID + ".preferences",
    arrayOf(BuildConfig.APPLICATION_ID + "_preferences")
) {
    override fun checkAccess(prefFileName: String, prefKey: String, write: Boolean): Boolean {
        if (write) return false
        val providerContext = context ?: return false
        return BridgeAccess.isCallerAllowed(providerContext)
    }
}
