package com.wax.module.modern;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * API 102 entry, currently compiled as an isolated canary AAR.
 *
 * It deliberately does not invoke FeatureLoader, XposedBridge or XSharedPreferences:
 * legacy callbacks are forbidden on API 102. It must not be added to the legacy APK,
 * until the shipping metadata and every required feature have modern adapters.
 */
public final class ModernXposedEntry extends XposedModule {
    private static final String TAG = "WA-X Modern";
    private static final String PREFS_GROUP = "wax.runtime.v1";
    private volatile String currentProcessName;
    private final Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        currentProcessName = param.getProcessName();
        log(Log.INFO, TAG,
                "Module loaded: API=" + getApiVersion() + ", process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!ModernTargetPolicy.isMainTarget(
                currentProcessName, param.getPackageName(), param.isFirstPackage())) {
            return;
        }
        final String packageName = param.getPackageName();
        if (!started.add(packageName)) {
            return;
        }
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            new ModernHookBridge(this).intercept(
                    attach,
                    "wax.modern.application.attach",
                    chain -> {
                        Object result = chain.proceed();
                        Context target = (Context) chain.getArg(0);
                        if (target != null && packageName.equals(target.getPackageName())) {
                            reportBootstrap(packageName);
                        }
                        return result;
                    });
        } catch (ReflectiveOperationException | RuntimeException e) {
            started.remove(packageName);
            log(Log.ERROR, TAG, "Modern package attach hook unavailable: " + packageName, e);
        }
    }

    private void reportBootstrap(String packageName) {
        try {
            SharedPreferences preferences = getRemotePreferences(PREFS_GROUP);
            // Report only loader evidence. Do not mark 64 legacy features READY.
            boolean canaryEnabled = preferences != null
                    && preferences.getBoolean("modern_canary_enabled", false);
            log(Log.INFO, TAG, "API102 attached: " + packageName
                    + ", canary=" + canaryEnabled);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Modern preferences unavailable: " + packageName, e);
        }
    }

}
