package com.wax.module

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.content.res.XModuleResources
import android.view.Window
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.wax.module.platform.TargetPackageRegistry
import com.wax.module.xposed.AntiUpdater
import com.wax.module.xposed.bridge.ScopeHook
import com.wax.module.xposed.core.FeatureLoader
import com.wax.module.xposed.downgrade.Patch
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam
import de.robv.android.xposed.callbacks.XC_LoadPackage

class ModuleEntryPoint :
    IXposedHookLoadPackage,
    IXposedHookInitPackageResources,
    IXposedHookZygoteInit {
    private var modulePath: String? = null

    companion object {
        private var pref: XSharedPreferences? = null

        /**
         * The method the self-hook replaces, named here so the name exists in exactly one place.
         *
         * It is a constant rather than an inline literal because the string is the contract
         * between the hook and the method it hooks: renaming one without the other would leave
         * the signal permanently false with nothing failing.
         */
        const val LEGACY_SIGNAL_METHOD: String = "isLegacySelfHookSignal"

        @JvmStatic
        var resParam: InitPackageResourcesParam? = null

        @JvmStatic
        fun getPref(): XSharedPreferences =
            pref ?: XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + "_preferences",
            ).apply {
                makeWorldReadable()
                reload()
                pref = this
            }
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName
        val classLoader = lpparam.classLoader

        // The module's own process. It is hooked, but not enhanced: this is what
        // makes ModuleApplication.isLegacySelfHookSignal report that a framework loaded WA X
        // here, and what forces the preference file world-readable so hooked WhatsApp
        // processes can read it through XSharedPreferences. LSPosed adds a legacy module to its
        // own scope automatically for exactly this reason.
        //
        // This signal is deliberately narrow. It says a framework loaded the module into the
        // module's own process, which is not the same claim as "WhatsApp is activated", and it
        // is no longer used to make that claim - the per-target heartbeat is. What it remains is
        // the one piece of evidence available when no target has ever started.
        if (packageName == BuildConfig.APPLICATION_ID) {
            hookSelf(classLoader)
            return
        }

        // Defence in depth for the scope. LSPosed's scope is user editable, so the
        // scope metadata is a recommendation and not a guarantee. Anything outside
        // the supported set leaves here without a single hook installed, instead of
        // running every hook stage against an unrelated application.
        if (!TargetPackageRegistry.isInHookScope(packageName)) {
            return
        }

        // One APK hooks both WhatsApp builds, so the target is resolved from the
        // process rather than from which build the user installed. That scope is what
        // every feature below reads, and it is the only thing that keeps one target's
        // overrides out of the other process.
        val target = TargetPackageRegistry.targetOf(packageName)

        XposedBridge.log(
            "[•] This package: $packageName" + (target?.let { " (${it.displayName})" } ?: ""),
        )

        if (target != null) {
            TargetRuntime.attach(target)
            AntiUpdater.hookSession(lpparam)
        }

        Patch.handleLoadPackage(lpparam)

        ScopeHook.hook(lpparam)

        if (target != null && lpparam.isFirstApplication) {
            // I believe isFirstApplication may fix the problem when using multiple
            // accounts; not yet tested.
            //
            // The package and process names are passed in rather than re-derived inside the
            // loader: they are what the health record is keyed by, and the framework's own
            // callback parameters are the only authoritative source for them.
            FeatureLoader.start(
                classLoader,
                lpparam.appInfo.sourceDir,
                packageName = lpparam.packageName,
                processName = lpparam.processName,
            )
            disableSecureFlag()
        }
    }

    /**
     * Hooks the module's own process. Kept separate from [handleLoadPackage] so the
     * self-hook cannot accidentally be reached for any other package.
     *
     * The method it replaces is named after what it is - a legacy signal - rather than after
     * the question it used to be asked. A hook that replaced a method named for "is Xposed
     * enabled" was, by its name, claiming to make the module enabled; one that replaces a
     * method named for the legacy signal is only claiming what the framework did, which is the
     * entire difference between evidence and an opinion.
     */
    private fun hookSelf(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClass(ModuleApplication::class.java.name, classLoader)
        XposedBridge.hookAllMethods(clazz, LEGACY_SIGNAL_METHOD, XC_MethodReplacement.returnConstant(true))

        @Suppress("DEPRECATION")
        @SuppressLint("WorldReadableFiles")
        XposedHelpers.findAndHookMethod(
            PreferenceManager::class.java.name,
            classLoader,
            "getDefaultSharedPreferencesMode",
            XC_MethodReplacement.returnConstant(ContextWrapper.MODE_WORLD_READABLE),
        )

        XposedHelpers.findAndHookMethod(
            "android.app.ContextImpl",
            classLoader,
            "checkMode",
            Int::class.javaPrimitiveType!!,
            XC_MethodReplacement.DO_NOTHING,
        )
    }

    @Throws(Throwable::class)
    override fun handleInitPackageResources(resparam: InitPackageResourcesParam) {
        val packageName = resparam.packageName

        if (!TargetPackageRegistry.isTarget(packageName)) {
            return
        }

        val modRes = XModuleResources.createInstance(modulePath, resparam.res)
        resParam = resparam
        val resourceClasses =
            listOf(
                R.array::class.java,
                R.string::class.java,
                R.drawable::class.java,
            )
        resourceClasses.forEach {
            injectResources(it, modRes, resparam)
        }
    }

    private fun injectResources(
        clazz: Class<*>,
        modRes: XModuleResources?,
        resparam: InitPackageResourcesParam,
    ) {
        var count = 0
        for (field in clazz.declaredFields) {
            try {
                field.isAccessible = true

                if (field.type === Int::class.javaPrimitiveType) {
                    val resId = field.getInt(null)
                    if (resId > 0x7f000000) {
                        count++
                        val replacementId = resparam.res.addResource(modRes, resId)
                        field.set(null, replacementId)
                    }
                } else if (field.type === IntArray::class.java) {
                    val resIds = field.get(null) as IntArray?
                    if (resIds != null) {
                        for (i in resIds.indices) {
                            if (resIds[i] > 0x7f000000) {
                                count++
                                resIds[i] = resparam.res.addResource(modRes, resIds[i])
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        XposedBridge.log("Injected " + count + " resources for " + clazz.getSimpleName())
    }

    @Throws(Throwable::class)
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    fun disableSecureFlag() {
        XposedHelpers.findAndHookMethod(
            Window::class.java,
            "setFlags",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val flags = param.args[0] as Int
                    val mask = param.args[1] as Int
                    param.args[0] = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    param.args[1] = mask and WindowManager.LayoutParams.FLAG_SECURE.inv()
                }
            },
        )

        XposedHelpers.findAndHookMethod(
            Window::class.java,
            "addFlags",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val flags = param.args[0] as Int
                    val newFlags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    param.args[0] = newFlags
                    if (newFlags == 0) {
                        param.result = null
                    }
                }
            },
        )
    }
}
