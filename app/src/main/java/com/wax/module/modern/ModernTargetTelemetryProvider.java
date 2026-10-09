package com.wax.module.modern;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.util.Arrays;

/**
 * Authenticated one-way runtime evidence transport.
 *
 * XposedModule.getRemotePreferences() is READ-ONLY inside the hooked app.
 * The originating WhatsApp process instead calls this provider using Binder;
 * only the real com.whatsapp/com.whatsapp.w4b Linux UID is accepted.
 * No WhatsApp content or contacts are transferred.
 */
public final class ModernTargetTelemetryProvider extends ContentProvider {
    public static final String METHOD_REPORT = "report-target-event-v1";
    public static final String LOCAL_PREFS = "modern_runtime_target_reports";
    public static final String EVENT_BOOTSTRAP = "BOOTSTRAP";
    public static final String EVENT_RUNTIME_HEARTBEAT = "RUNTIME_HEARTBEAT";
    public static final String EVENT_CUSTOM_TIME = "CUSTOM_TIME";
    public static final String EVENT_SHARE_LIMIT = "SHARE_LIMIT";
    public static final String EVENT_FREEZE_LAST_SEEN = "FREEZE_LAST_SEEN";
    public static final String EVENT_DND_MODE = "DND_MODE";
    public static final String EVENT_MENU_HOME = "MENU_HOME";
    private static final String TAG = "WA-X TargetTelemetry";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_REPORT.equals(method) || extras == null || getContext() == null) {
            return rejected();
        }
        Context context = getContext();
        String target = extras.getString("target", "");
        String event = extras.getString("event", "");
        String value = extras.getString("value", "");
        if (!isAuthorizedSender(target, Binder.getCallingUid(),
                context.getPackageManager().getPackagesForUid(Binder.getCallingUid()))) {
            Log.w(TAG, "Rejected telemetry from unauthorized UID");
            return rejected();
        }
        if (!isSupportedEvent(event) || value == null || value.length() > 100) {
            return rejected();
        }
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE).edit();
        if (EVENT_BOOTSTRAP.equals(event)) {
            if (!"ATTACHED".equals(value)) return rejected();
            editor.putLong("modern.bootstrap.last." + target, now)
                    .putLong("modern.bootstrap.boot." + target, now - SystemClock.elapsedRealtime())
                    .putLong("modern.runtime.milestone.ATTACH_OBSERVED." + target, now);
        } else if (EVENT_RUNTIME_HEARTBEAT.equals(event)) {
            if (!isSupportedRuntimeHeartbeatValue(value)) return rejected();
            editor.putLong("modern.heartbeat.elapsed." + target, SystemClock.elapsedRealtime())
                    .putLong("modern.heartbeat.boot." + target,
                            now - SystemClock.elapsedRealtime());
        } else if (EVENT_CUSTOM_TIME.equals(event)) {
            if ("INVOKED".equals(value)) {
                SharedPreferences current =
                        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
                editor.putLong("modern.feature.custom_time.last_invoked." + target, now)
                        .putLong("modern.feature.custom_time.invocation_boot." + target,
                                now - SystemClock.elapsedRealtime())
                        .putLong("modern.feature.custom_time.invocation_count." + target,
                                current.getLong("modern.feature.custom_time.invocation_count." + target, 0) + 1);
            } else {
                editor.putString("modern.feature.custom_time.state." + target, value);
            }
        } else if (EVENT_SHARE_LIMIT.equals(event)) {
            editor.putString("modern.feature.share_limit.state." + target, value);
        } else if (EVENT_FREEZE_LAST_SEEN.equals(event)) {
            editor.putString("modern.feature.freeze_last_seen.state." + target, value);
        } else if (EVENT_DND_MODE.equals(event)) {
            editor.putString("modern.feature.dnd_mode.state." + target, value);
        } else if (EVENT_MENU_HOME.equals(event)) {
            if (!isSupportedMenuHomeState(value)) return rejected();
            editor.putString("modern.feature.menu_home.state." + target, value);
        } else {
            return rejected();
        }
        boolean saved = editor.commit();
        Bundle result = new Bundle();
        result.putBoolean("accepted", saved);
        return result;
    }

    static boolean isAuthorizedSender(String target, int callingUid, String[] uidPackages) {
        if (callingUid <= 0 || uidPackages == null) return false;
        if (!"com.whatsapp".equals(target) && !"com.whatsapp.w4b".equals(target)) return false;
        return Arrays.asList(uidPackages).contains(target);
    }

    static boolean isSupportedRuntimeHeartbeatValue(String value) {
        return "ALIVE".equals(value);
    }

    static boolean isSupportedEvent(String event) {
        return EVENT_BOOTSTRAP.equals(event)
                || EVENT_RUNTIME_HEARTBEAT.equals(event)
                || EVENT_CUSTOM_TIME.equals(event)
                || EVENT_SHARE_LIMIT.equals(event)
                || EVENT_FREEZE_LAST_SEEN.equals(event)
                || EVENT_DND_MODE.equals(event)
                || EVENT_MENU_HOME.equals(event);
    }

    static boolean isSupportedMenuHomeState(String value) {
        return "INSTALLED".equals(value)
                || "ALREADY_INSTALLED".equals(value)
                || "UNSUPPORTED_TARGET".equals(value)
                || "HOME_CLASS_MISSING".equals(value)
                || "MENU_METHOD_MISSING".equals(value)
                || "INVALID_HOME_TYPE".equals(value)
                || "INVALID_MENU_SIGNATURE".equals(value)
                || "ERROR".equals(value)
                || "ITEM_ADDED".equals(value);
    }

    private static Bundle rejected() {
        Bundle result = new Bundle();
        result.putBoolean("accepted", false);
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Telemetry provider only supports call()");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Telemetry provider only supports call()");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Telemetry provider only supports call()");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException("Telemetry provider only supports call()");
    }
}
