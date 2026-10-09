package com.wax.module.modern;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;

/**
 * API 102 port of the legacy MinorFixes DocumentPicker/ML Kit initialization hook.
 *
 * This is an opt-in development adapter until real WhatsApp and Business invocations are
 * observed. It never uses XposedBridge or XposedHelpers and owns one reversible hook.
 */
public final class ModernMinorFixesFeature {
    public static final String FEATURE_ID = "minor_fixes";
    public static final String ENABLE_KEY = "modern.feature.minor_fixes.enabled";
    private static final String DOCUMENT_PICKER = "com.whatsapp.documentpicker.DocumentPickerActivity";
    private static final String ML_KIT_PROVIDER = "com.google.mlkit.common.internal.MlKitInitProvider";
    private static final String TAG = "WA-X MinorFixes 102";

    public enum Outcome { DISABLED, INSTALLED }

    private boolean providerInitialized;

    public Outcome install(XposedInterface framework, ModernHookRegistry hooks,
                           SharedPreferences preferences) throws Throwable {
        Objects.requireNonNull(preferences, "preferences");
        if (!preferences.getBoolean(ENABLE_KEY, false)) return Outcome.DISABLED;
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(hooks, "hooks");
        Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        hooks.installFeature(FEATURE_ID, Collections.singletonList(
                new ModernHookRegistry.Registration("minor_fixes.document_picker", () -> {
                    XposedInterface.HookHandle handle =
                            new ModernHookBridge(framework).intercept(onCreate,
                                    "wax.modern.minor_fixes.document_picker", chain -> {
                                        Object receiver = chain.getThisObject();
                                        if (receiver instanceof Activity
                                                && matchesDocumentPicker(receiver.getClass().getName())) {
                                            ensureMlKitInitialized((Activity) receiver);
                                        }
                                        // Legacy fix runs before Activity.onCreate, never replaces it.
                                        return chain.proceed();
                                    });
                    return handle::unhook;
                })));
        return Outcome.INSTALLED;
    }

    static boolean matchesDocumentPicker(String className) {
        return DOCUMENT_PICKER.equals(className);
    }

    static boolean isAlreadyInitialized(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof IllegalStateException
                    && failure.getMessage() != null
                    && failure.getMessage().contains("MlKitContext is already initialized")) {
                return true;
            }
        }
        return false;
    }

    private synchronized void ensureMlKitInitialized(Activity activity) {
        if (providerInitialized) return;
        try {
            Class<?> type = Class.forName(ML_KIT_PROVIDER, true, activity.getClassLoader());
            ContentProvider provider = (ContentProvider) type.getDeclaredConstructor().newInstance();
            ComponentName component = new ComponentName(activity.getPackageName(), ML_KIT_PROVIDER);
            android.content.pm.ProviderInfo info = activity.getPackageManager()
                    .getProviderInfo(component, PackageManager.GET_META_DATA);
            provider.attachInfo(activity.getApplicationContext(), info);
            providerInitialized = true;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
            if (isAlreadyInitialized(failure)) {
                providerInitialized = true;
                return;
            }
            // Never suppress the target Activity.onCreate after a failed optional repair.
            Log.w(TAG, "ML Kit DocumentPicker initialization unavailable", failure);
        }
    }
}
