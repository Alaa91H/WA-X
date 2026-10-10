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
import androidx.preference.PreferenceManager;
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
    public static final String METHOD_WRITE_SETTING = "write-target-setting-v1";
    public static final String METHOD_READ_STATES = "read-target-states-v1";
    public static final String METHOD_READ_PRIVACY = "read-target-privacy-v1";
    public static final String LOCAL_PREFS = "modern_runtime_target_reports";
    public static final String EVENT_BOOTSTRAP = "BOOTSTRAP";
    public static final String EVENT_RUNTIME_HEARTBEAT = "RUNTIME_HEARTBEAT";
    public static final String EVENT_CUSTOM_TIME = "CUSTOM_TIME";
    public static final String EVENT_SHARE_LIMIT = "SHARE_LIMIT";
    public static final String EVENT_FREEZE_LAST_SEEN = "FREEZE_LAST_SEEN";
    public static final String EVENT_DND_MODE = "DND_MODE";
    public static final String EVENT_MENU_HOME = "MENU_HOME";
    public static final String EVENT_CONTACT_ITEM_LISTENER = "CONTACT_ITEM_LISTENER";
    public static final String EVENT_CONVERSATION_ITEM_LISTENER = "CONVERSATION_ITEM_LISTENER";
    public static final String EVENT_MENU_STATUS_PROVIDER = "MENU_STATUS_PROVIDER";
    public static final String EVENT_ACTIVITY_CONTROLLER = "ACTIVITY_CONTROLLER";
    public static final String EVENT_TASKER = "TASKER";
    public static final String EVENT_CONTEXT_MENU_ACTION_PROVIDER = "CONTEXT_MENU_ACTION_PROVIDER";
    public static final String EVENT_CONTACT_ACCESS = "CONTACT_ACCESS";
    public static final String EVENT_JID_ACCESS = "JID_ACCESS";
    public static final String EVENT_TYPING_PRIVACY = "TYPING_PRIVACY";
    public static final String EVENT_TYPING_PRIVACY_TYPING = "TYPING_PRIVACY_TYPING";
    public static final String EVENT_TYPING_PRIVACY_RECORDING = "TYPING_PRIVACY_RECORDING";
    public static final String EVENT_ONLINE_PRIVACY = "ONLINE_PRIVACY";
    public static final String EVENT_ANTI_REVOKE = "ANTI_REVOKE";
    public static final String EVENT_STATUS_SEEN_HIDDEN = "STATUS_SEEN_HIDDEN";
    public static final String EVENT_STATUS_SEEN_AFTER_REPLY = "STATUS_SEEN_AFTER_REPLY";
    public static final String EVENT_RECEIPT_PRIVACY_READ = "RECEIPT_PRIVACY_READ";
    public static final String EVENT_RECEIPT_PRIVACY_AFTER_REPLY = "RECEIPT_PRIVACY_AFTER_REPLY";
    public static final String EVENT_RECEIPT_PRIVACY_DELIVERY = "RECEIPT_PRIVACY_DELIVERY";
    public static final String EVENT_HIDE_CHAT = "HIDE_CHAT";
    public static final String EVENT_VIEW_ONCE = "VIEW_ONCE";
    public static final String EVENT_MESSAGE_ACCESS = "MESSAGE_ACCESS";
    public static final String EVENT_DIAGNOSTICS = "DIAGNOSTICS";
    private static final String TAG = "WA-X TargetTelemetry";

    /**
     * The one string-valued preference the Control Center may write.
     *
     * It holds the comma-separated ids of the user's favourite controls, so
     * the favourites filter is backed by real persisted state rather than a
     * cosmetic toggle that forgets everything on restart.
     */
    public static final String CONTROL_CENTER_FAVORITES_KEY = "wax.control_center.favorites";

    /** The archived-chat modes, matching res/values/arrays.xml archive_values. */
    static final String MODE_DISABLED = "0";
    static final String MODE_CLICK_TIMES = "1";
    static final String MODE_HOLD_TITLE = "2";

    /** Upper bound on the favourites list, so the value stays a short string. */
    static final int MAX_FAVORITES_LENGTH = 256;

    /** The one string-valued mode the embedded Control Center may read/write. */
    static final String CONTROL_CENTER_MODE_KEY = "typearchive";

    /** The only preference keys the embedded Control Center may read. */
    static final String[] CONTROL_CENTER_PREFERENCE_KEYS = {
        "modern.feature.custom_time.enabled",
        "removeforwardlimit",
        "freezelastseen",
        "dndmode",
        "tasker",
        "ghostmode",
        "ghostmode_t",
        "ghostmode_r",
        "viewonce",
        // Receipt privacy keeps the legacy keys so an existing switch keeps
        // its meaning across the port.
        "hideread",
        "hideread_group",
        "hidereceipt",
        "hidereadafterreply",
        "hidestatusview",
        "sendstatusseenonreply",
    };

    /** The only effective-state keys the embedded Control Center may read. */
    static final String[] CONTROL_CENTER_EVIDENCE_KEYS = {
        "modern.feature.custom_time.state",
        "modern.feature.share_limit.state",
        "modern.feature.freeze_last_seen.state",
        "modern.feature.dnd_mode.state",
        "modern.feature.menu_home.state",
        "modern.feature.contact_item_listener.state",
        "modern.feature.conversation_item_listener.state",
        "modern.feature.menu_status_provider.state",
        "modern.feature.activity_controller.state",
        "modern.feature.tasker.state",
        "modern.feature.context_menu_action_provider.state",
        "modern.feature.contact_access.state",
        "modern.feature.jid_access.state",
        "modern.feature.typing_privacy.state",
        "modern.feature.hide_chat.state",
        "modern.feature.view_once.state",
        "modern.feature.message_access.state",
        "modern.feature.diagnostics.state",
        "modern.feature.receipt_privacy_read.state",
        "modern.feature.receipt_privacy_after_reply.state",
        "modern.feature.receipt_privacy_delivery.state",
        "modern.feature.typing_privacy_typing.state",
        "modern.feature.typing_privacy_recording.state",
        "modern.feature.online_privacy.state",
        "modern.feature.anti_revoke.state",
        "modern.feature.status_seen_hidden.state",
        "modern.feature.status_seen_after_reply.state",
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null || extras == null) {
            return rejected();
        }
        if (METHOD_WRITE_SETTING.equals(method)) {
            return writeSetting(getContext(), extras);
        }
        if (METHOD_READ_STATES.equals(method)) {
            return readStates(getContext(), extras);
        }
        if (METHOD_READ_PRIVACY.equals(method)) {
            return readPrivacy(getContext(), extras);
        }
        if (!METHOD_REPORT.equals(method)) {
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
        } else if (EVENT_CONTACT_ITEM_LISTENER.equals(event)) {
            editor.putString("modern.feature.contact_item_listener.state." + target, value);
        } else if (EVENT_CONVERSATION_ITEM_LISTENER.equals(event)) {
            editor.putString("modern.feature.conversation_item_listener.state." + target, value);
        } else if (EVENT_MENU_STATUS_PROVIDER.equals(event)) {
            editor.putString("modern.feature.menu_status_provider.state." + target, value);
        } else if (EVENT_ACTIVITY_CONTROLLER.equals(event)) {
            editor.putString("modern.feature.activity_controller.state." + target, value);
        } else if (EVENT_TASKER.equals(event)) {
            editor.putString("modern.feature.tasker.state." + target, value);
        } else if (EVENT_CONTEXT_MENU_ACTION_PROVIDER.equals(event)) {
            editor.putString("modern.feature.context_menu_action_provider.state." + target, value);
        } else if (EVENT_CONTACT_ACCESS.equals(event)) {
            editor.putString("modern.feature.contact_access.state." + target, value);
        } else if (EVENT_JID_ACCESS.equals(event)) {
            editor.putString("modern.feature.jid_access.state." + target, value);
        } else if (EVENT_TYPING_PRIVACY.equals(event)) {
            editor.putString("modern.feature.typing_privacy.state." + target, value);
        } else if (EVENT_TYPING_PRIVACY_TYPING.equals(event)) {
            editor.putString("modern.feature.typing_privacy_typing.state." + target, value);
        } else if (EVENT_TYPING_PRIVACY_RECORDING.equals(event)) {
            editor.putString("modern.feature.typing_privacy_recording.state." + target, value);
        } else if (EVENT_ONLINE_PRIVACY.equals(event)) {
            editor.putString("modern.feature.online_privacy.state." + target, value);
        } else if (EVENT_ANTI_REVOKE.equals(event)) {
            editor.putString("modern.feature.anti_revoke.state." + target, value);
        } else if (EVENT_STATUS_SEEN_HIDDEN.equals(event)) {
            editor.putString("modern.feature.status_seen_hidden.state." + target, value);
        } else if (EVENT_STATUS_SEEN_AFTER_REPLY.equals(event)) {
            editor.putString("modern.feature.status_seen_after_reply.state." + target, value);
        } else if (EVENT_RECEIPT_PRIVACY_READ.equals(event)) {
            editor.putString("modern.feature.receipt_privacy_read.state." + target, value);
        } else if (EVENT_RECEIPT_PRIVACY_AFTER_REPLY.equals(event)) {
            editor.putString("modern.feature.receipt_privacy_after_reply.state." + target, value);
        } else if (EVENT_RECEIPT_PRIVACY_DELIVERY.equals(event)) {
            editor.putString("modern.feature.receipt_privacy_delivery.state." + target, value);
        } else if (EVENT_HIDE_CHAT.equals(event)) {
            editor.putString("modern.feature.hide_chat.state." + target, value);
        } else if (EVENT_VIEW_ONCE.equals(event)) {
            editor.putString("modern.feature.view_once.state." + target, value);
        } else if (EVENT_MESSAGE_ACCESS.equals(event)) {
            editor.putString("modern.feature.message_access.state." + target, value);
        } else if (EVENT_DIAGNOSTICS.equals(event)) {
            editor.putString("modern.feature.diagnostics.state." + target, value);
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
                || EVENT_MENU_HOME.equals(event)
                || EVENT_CONTACT_ITEM_LISTENER.equals(event)
                || EVENT_CONVERSATION_ITEM_LISTENER.equals(event)
                || EVENT_MENU_STATUS_PROVIDER.equals(event)
                || EVENT_ACTIVITY_CONTROLLER.equals(event)
                || EVENT_TASKER.equals(event)
                || EVENT_CONTEXT_MENU_ACTION_PROVIDER.equals(event)
                || EVENT_CONTACT_ACCESS.equals(event)
                || EVENT_JID_ACCESS.equals(event)
                || EVENT_TYPING_PRIVACY.equals(event)
                || EVENT_HIDE_CHAT.equals(event)
                || EVENT_VIEW_ONCE.equals(event)
                || EVENT_MESSAGE_ACCESS.equals(event)
                || EVENT_DIAGNOSTICS.equals(event);
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

    /**
     * Allowlist of preference keys the injected target may flip through the
     * in-WhatsApp settings shell. Only enable flags of features with a wired
     * modern adapter; formatting sub-keys stay Manager-side for now.
     */
    static boolean isWritableSettingKey(String key) {
        return "modern.feature.custom_time.enabled".equals(key)
                || "ghostmode".equals(key)
                || "ghostmode_t".equals(key)
                || "ghostmode_r".equals(key)
                || "removeforwardlimit".equals(key)
                || "freezelastseen".equals(key)
                || "dndmode".equals(key)
                || "tasker".equals(key)
                || "viewonce".equals(key)
                || "hideread".equals(key)
                || "hideread_group".equals(key)
                || "hidereceipt".equals(key)
                || "hidereadafterreply".equals(key)
                || "antirevoke".equals(key)
                || "hidestatusview".equals(key)
                || "sendstatusseenonreply".equals(key);
    }

    /** Favourites must be a short, comma-separated list of plain identifiers. */
    static boolean isValidFavorites(String favorites) {
        if (favorites == null || favorites.length() > MAX_FAVORITES_LENGTH) return false;
        if (favorites.isEmpty()) return true;
        for (String id : favorites.split(",", -1)) {
            if (id.isEmpty() || id.length() > 64) return false;
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
                if (!allowed) return false;
            }
        }
        return true;
    }

    /**
     * Persists a target-originated toggle into the Manager default preferences
     * (the file {@code ModernRuntimePreferenceRelay} observes), so the
     * existing relay syncs it into RemotePreferences. Same UID authorization
     * as telemetry; boolean values only; never clears other keys.
     */
    private static Bundle writeSetting(Context context, Bundle extras) {
        String target = extras.getString("target", "");
        String key = extras.getString("key", "");
        if (!isAuthorizedSender(target, Binder.getCallingUid(),
                context.getPackageManager().getPackagesForUid(Binder.getCallingUid()))) {
            Log.w(TAG, "Rejected settings write from unauthorized UID");
            return rejected();
        }
        if ("typearchive".equals(key)) {
            String mode = extras.getString("mode", MODE_DISABLED);
            if (!MODE_DISABLED.equals(mode) && !MODE_CLICK_TIMES.equals(mode)
                    && !MODE_HOLD_TITLE.equals(mode)) {
                return rejected();
            }
            boolean savedMode = PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString("typearchive", mode)
                    .commit();
            Bundle modeResult = new Bundle();
            modeResult.putBoolean("accepted", savedMode);
            return modeResult;
        }
        if ("antirevoke".equals(key)) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            // The Manager owns a three-state ListPreference, never a Boolean.
            String previous = readMode(preferences, key);
            String next = extras.getBoolean("enabled", false)
                    ? (MODE_HOLD_TITLE.equals(previous) ? MODE_HOLD_TITLE : MODE_CLICK_TIMES)
                    : MODE_DISABLED;
            boolean savedMode = preferences.edit().putString(key, next).commit();
            Bundle modeResult = new Bundle();
            modeResult.putBoolean("accepted", savedMode);
            return modeResult;
        }
        if (CONTROL_CENTER_FAVORITES_KEY.equals(key)) {
            String favorites = extras.getString("favorites", "");
            if (!isValidFavorites(favorites)) return rejected();
            boolean savedFavorites = PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString(CONTROL_CENTER_FAVORITES_KEY, favorites)
                    .commit();
            Bundle favoritesResult = new Bundle();
            favoritesResult.putBoolean("accepted", savedFavorites);
            return favoritesResult;
        }
        if (!isWritableSettingKey(key)) {
            return rejected();
        }
        boolean enabled = extras.getBoolean("enabled", false);
        boolean saved = PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(key, enabled)
                .commit();
        Bundle result = new Bundle();
        result.putBoolean("accepted", saved);
        return result;
    }

    /**
     * Answers the embedded Control Center with the Manager's verified state.
     *
     * Read-only, same UID authorization as the report/write paths. Only the
     * fixed allowlisted keys are returned: the requested booleans and the
     * last effective state per feature. No other preference, chat, contact or
     * account data can cross this boundary.
     */
    private static Bundle readStates(Context context, Bundle extras) {
        String target = extras.getString("target", "");
        if (!isAuthorizedSender(target, Binder.getCallingUid(),
                context.getPackageManager().getPackagesForUid(Binder.getCallingUid()))) {
            Log.w(TAG, "Rejected state read from unauthorized UID");
            return rejected();
        }
        SharedPreferences reports =
                context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
        SharedPreferences manager = PreferenceManager.getDefaultSharedPreferences(context);
        Bundle result = new Bundle();
        for (String key : CONTROL_CENTER_PREFERENCE_KEYS) {
            // One corrupt or obsolete key must never blank every other switch.
            result.putBoolean("pref." + key, readBoolean(manager, key));
        }
        String archiveMode = readMode(manager, CONTROL_CENTER_MODE_KEY);
        String antiRevokeMode = readMode(manager, "antirevoke");
        result.putBoolean("pref." + CONTROL_CENTER_MODE_KEY, !MODE_DISABLED.equals(archiveMode));
        result.putBoolean("pref.antirevoke", !MODE_DISABLED.equals(antiRevokeMode));
        result.putString("pref." + CONTROL_CENTER_FAVORITES_KEY,
                manager.getString(CONTROL_CENTER_FAVORITES_KEY, ""));
        // String-valued modes travel under their own prefix, because the
        // boolean loop above would coerce them to false.
        result.putString("mode." + CONTROL_CENTER_MODE_KEY, archiveMode);
        result.putString("mode.antirevoke", antiRevokeMode);
        for (String key : CONTROL_CENTER_EVIDENCE_KEYS) {
            String value = reports.getString(key + "." + target, null);
            if (value != null) {
                result.putString("state." + key, value);
            }
        }
        result.putBoolean("accepted", true);
        return result;
    }

    /** Returns the effective boolean without letting a malformed legacy value break the bundle. */
    static boolean readBoolean(SharedPreferences prefs, String key) {
        Object value = prefs.getAll().get(key);
        return value instanceof Boolean && (Boolean) value;
    }

    /** Legacy list preferences must stay strings; old buggy toggles may have stored booleans. */
    static String readMode(SharedPreferences prefs, String key) {
        Object value = prefs.getAll().get(key);
        if (MODE_DISABLED.equals(value) || MODE_CLICK_TIMES.equals(value)
                || MODE_HOLD_TITLE.equals(value)) return (String) value;
        if (Boolean.TRUE.equals(value)) return MODE_CLICK_TIMES;
        return MODE_DISABLED;
    }

    /**
     * Answers one contact's privacy rules.
     *
     * Same UID authorization as every other path. The request names a single
     * number the target is already inspecting, and the answer is exactly two
     * booleans, so no contact list is transferred and nothing is stored here.
     */
    private static Bundle readPrivacy(Context context, Bundle extras) {
        String target = extras.getString("target", "");
        if (!isAuthorizedSender(target, Binder.getCallingUid(),
                context.getPackageManager().getPackagesForUid(Binder.getCallingUid()))) {
            Log.w(TAG, "Rejected privacy read from unauthorized UID");
            return rejected();
        }
        String number = extras.getString("number", "");
        if (number.isEmpty() || number.length() > 32) return rejected();
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) return rejected();
        }
        SharedPreferences manager = PreferenceManager.getDefaultSharedPreferences(context);
        if ("0".equals(manager.getString("custom_privacy_type", "0"))) {
            return rejected();
        }
        String rules = manager.getString(number + "_privacy", "");
        Bundle result = new Bundle();
        result.putBoolean("hide_typing", rules.contains("\"HideTyping\":true"));
        result.putBoolean("hide_recording", rules.contains("\"HideRecording\":true"));
        result.putBoolean("accepted", true);
        return result;
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
