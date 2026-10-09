package com.wax.module.modern;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/**
 * One-way reports from the injected WhatsApp process to the original WA X app.
 *
 * The modern XposedModule.getRemotePreferences() surface is read-only in targets.
 * Provider calls are made ONLY from a background executor, never a hooked UI thread.
 */
public final class ModernTargetTelemetry {
    private static final Uri PROVIDER = Uri.parse("content://com.wax.module.runtime.telemetry");
    private static final String METHOD = "report-target-event-v1";
    private ModernTargetTelemetry() {}

    public static boolean send(Context context, String packageName, String event, String value) {
        if (context == null
                || !ModernTargetPolicy.isTargetPackageForProcess(context.getPackageName(), packageName)) {
            return false;
        }
        Bundle extras = new Bundle();
        extras.putString("target", packageName);
        extras.putString("event", event);
        extras.putString("value", value);
        Bundle response = context.getContentResolver().call(PROVIDER, METHOD, null, extras);
        return response != null && response.getBoolean("accepted", false);
    }
}
