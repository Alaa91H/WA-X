package com.wax.module.modern;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * Target-process settings writer: the in-WhatsApp controls persist here.
 *
 * libxposed RemotePreferences are READ-ONLY inside hooked apps, so a toggle
 * flipped in WhatsApp cannot write them directly. This client forwards the
 * change to the Manager through the UID-authenticated settings-write
 * provider method; the Manager persists it to the same preference file the
 * {@code ModernRuntimePreferenceRelay} observes, which then syncs it back
 * into RemotePreferences. Hooks read the new value after a WhatsApp restart.
 *
 * Provider Binder calls must run on a background thread, never a hooked UI
 * thread. Callers show a "requires WhatsApp restart" notice with every save.
 */
public final class ModernTargetSettingsClient {
    static final Uri PROVIDER = Uri.parse("content://com.wax.module.runtime.telemetry");
    static final String METHOD = "write-target-setting-v1";

    private ModernTargetSettingsClient() {}

    public static boolean write(Context context, String packageName, String key, boolean enabled) {
        if (context == null || packageName == null || key == null) return false;
        if (!ModernTargetPolicy.isTargetPackageForProcess(context.getPackageName(), packageName)) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString("target", packageName);
        extras.putString("key", key);
        extras.putBoolean("enabled", enabled);
        try {
            Bundle response = context.getContentResolver().call(PROVIDER, METHOD, null, extras);
            return response != null && response.getBoolean("accepted", false);
        } catch (RuntimeException deliveryFailure) {
            return false;
        }
    }
}
