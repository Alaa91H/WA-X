package com.wmods.wppenhacer.xposed

import android.content.pm.PackageInstaller
import com.wmods.wppenhacer.platform.SupportedPackages
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.IOException

object AntiUpdater {
    /**
     * Blocks an update of WhatsApp while the module is active, because an update
     * replaces the obfuscated classes every resolver was matched against.
     *
     * The hook is installed only in a supported target. It used to be installed in
     * every process on the device except `system_server`, so the module was hooking
     * applications it has no business touching. Rejecting the non-targets up front
     * keeps the behaviour for WhatsApp identical and removes the hooks everywhere
     * else.
     */
    fun hookSession(lpparam: LoadPackageParam) {
        if (!SupportedPackages.isTarget(lpparam.packageName)) return
        XposedBridge.hookAllMethods(
            PackageInstaller::class.java,
            "createSession",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val session = param.args[0] as PackageInstaller.SessionParams?
                    val packageName = XposedHelpers.getObjectField(session, "mPackageName") as? String
                    if (SupportedPackages.isTarget(packageName)) {
                        param.setThrowable(IOException("UPDATE LOCKED BY WAENHANCER"))
                    }
                }
            })
    }
}
