package com.wax.module.modern;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * In-WhatsApp settings shell for features already migrated to API 102.
 *
 * Contributes one overflow-menu item per migrated toggle plus a restart item
 * and an honest pending-features note. Every control is really wired: the
 * ON/OFF label reflects the RemotePreferences value read on the bootstrap
 * thread, a tap flips it through the authenticated settings-write channel
 * into the Manager preference file the relay syncs, and every save tells the
 * user a WhatsApp restart is required. Nothing here enables a legacy-only
 * feature and no legacy Xposed API is used.
 */
public final class ModernInWhatsAppSettingsMenu {
    public static final String FEATURE_ID = "in_whatsapp_settings";
    public static final int MENU_BASE_ID = 0x57415822;
    public static final int RESTART_ITEM_ID = 0x57415827;
    static final String PENDING_NOTE = "WA X \u00B7 other features pending (see Manager)";
    private static final String TAG = "WA-X Settings102";

    /** Only features with a modern adapter wired into {@code ModernXposedEntry}. */
    public enum Toggle {
        CUSTOM_TIME("modern.feature.custom_time.enabled", "Custom Time"),
        SHARE_LIMIT("removeforwardlimit", "Share Limit"),
        FREEZE_LAST_SEEN("freezelastseen", "Freeze Last Seen"),
        DND_MODE("dndmode", "DND Mode");

        public final String key;
        public final String label;

        Toggle(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    public enum Outcome {
        INSTALLED,
        ALREADY_INSTALLED,
        UNSUPPORTED_TARGET,
        HOME_CLASS_MISSING,
        MENU_METHOD_MISSING,
        INVALID_HOME_TYPE,
        INVALID_MENU_SIGNATURE
    }

    static String titleFor(Toggle toggle, boolean on) {
        return "WA X \u00B7 " + toggle.label + ": " + (on ? "ON" : "OFF");
    }

    public Outcome install(
            Context target,
            XposedInterface framework,
            ModernHookRegistry registry,
            SharedPreferences preferences) throws Throwable {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(preferences, "preferences");
        String targetPackage = target.getPackageName();
        String className = ModernMenuHomePolicy.homeClassName(targetPackage);
        if (className == null) return Outcome.UNSUPPORTED_TARGET;

        Class<?> homeClass;
        try {
            homeClass = Class.forName(className, false, target.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return Outcome.HOME_CLASS_MISSING;
        }
        if (!Activity.class.isAssignableFrom(homeClass)) return Outcome.INVALID_HOME_TYPE;
        Method method;
        try {
            method = homeClass.getDeclaredMethod("onCreateOptionsMenu", Menu.class);
        } catch (NoSuchMethodException missing) {
            return Outcome.MENU_METHOD_MISSING;
        }
        if (method.getReturnType() != boolean.class) return Outcome.INVALID_MENU_SIGNATURE;

        // Snapshot toggle states on the installing (background) thread: the
        // menu hook itself must never perform RemotePreferences Binder IPC.
        boolean[] states = new boolean[Toggle.values().length];
        for (int i = 0; i < states.length; i++) {
            states[i] = preferences.getBoolean(Toggle.values()[i].key, false);
        }

        boolean installed = registry.installOnce(FEATURE_ID,
                new ModernHookRegistry.Registration("settings.options_menu", () -> {
                    XposedInterface.HookHandle handle =
                            new ModernHookBridge(framework).intercept(
                                    method, "wax.modern.settings.options_menu", chain -> {
                                        Object result = chain.proceed();
                                        Object receiver = chain.getThisObject();
                                        Object argument = chain.getArg(0);
                                        if (receiver instanceof Activity && argument instanceof Menu) {
                                            Activity activity = (Activity) receiver;
                                            Menu menu = (Menu) argument;
                                            if (ModernMenuHomePolicy.isTargetHome(
                                                        targetPackage, activity.getClass().getName())
                                                    && Looper.myLooper() == Looper.getMainLooper()
                                                    && ModernMenuHomePolicy.shouldContribute(
                                                        result, menu.size())) {
                                                try {
                                                    addSettingsEntries(menu, activity, targetPackage, states);
                                                } catch (RuntimeException failure) {
                                                    Log.e(TAG, "Could not add WA X settings entries", failure);
                                                }
                                            }
                                        }
                                        return result;
                                    });
                    return handle::unhook;
                }));
        return installed ? Outcome.INSTALLED : Outcome.ALREADY_INSTALLED;
    }

    private void addSettingsEntries(Menu menu, Activity activity, String targetPackage, boolean[] states) {
        Toggle[] toggles = Toggle.values();
        for (int i = 0; i < toggles.length; i++) {
            final int index = i;
            final Toggle toggle = toggles[i];
            int itemId = MENU_BASE_ID + index;
            if (menu.findItem(itemId) != null) continue;
            MenuItem item = menu.add(Menu.NONE, itemId, 9000 + index,
                    titleFor(toggle, states[index]));
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            item.setOnMenuItemClickListener(clicked ->
                    onToggleClicked(activity, targetPackage, toggle, index, states, item));
        }
        if (menu.findItem(RESTART_ITEM_ID) == null) {
            MenuItem restart = menu.add(Menu.NONE, RESTART_ITEM_ID, 9010, "WA X \u00B7 Restart WhatsApp");
            restart.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            restart.setOnMenuItemClickListener(clicked -> {
                restartWhatsApp(activity);
                return true;
            });
        }
        // Honest, timeless pending note: a disabled label, never a fake control.
        int noteId = RESTART_ITEM_ID + 1;
        if (menu.findItem(noteId) == null) {
            MenuItem note = menu.add(Menu.NONE, noteId, 9020, PENDING_NOTE);
            note.setEnabled(false);
        }
    }

    private boolean onToggleClicked(Activity activity, String targetPackage, Toggle toggle,
                                    int index, boolean[] states, MenuItem item) {
        boolean current = states[index];
        new AlertDialog.Builder(activity)
                .setTitle("WA X \u00B7 " + toggle.label)
                .setMessage("Currently " + (current ? "ON" : "OFF") + ". "
                        + (current ? "Turn OFF?" : "Turn ON?")
                        + " WhatsApp must restart to apply the change.")
                .setPositiveButton(current ? "Turn OFF" : "Turn ON", (dialog, which) -> {
                    boolean next = !current;
                    Thread writer = new Thread(() -> {
                        boolean saved = ModernTargetSettingsClient.write(
                                activity, targetPackage, toggle.key, next);
                        activity.runOnUiThread(() -> {
                            if (saved) {
                                states[index] = next;
                                item.setTitle(titleFor(toggle, next));
                                Toast.makeText(activity,
                                        "WA X \u00B7 " + toggle.label + " saved. "
                                                + "Restart WhatsApp to apply.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(activity,
                                        "WA X \u00B7 could not save (Manager unreachable)",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    }, "wax-api102-settings-write");
                    writer.setDaemon(true);
                    writer.start();
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .show();
        return true;
    }

    private void restartWhatsApp(Activity activity) {
        try {
            Intent launch = activity.getPackageManager()
                    .getLaunchIntentForPackage(activity.getPackageName());
            if (launch == null || launch.getComponent() == null) {
                Toast.makeText(activity, "WA X \u00B7 restart unavailable",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent restart = Intent.makeRestartActivityTask(launch.getComponent());
            restart.setPackage(activity.getPackageName());
            activity.startActivity(restart);
            Runtime.getRuntime().exit(0);
        } catch (RuntimeException failure) {
            Log.e(TAG, "WA X restart unavailable", failure);
            Toast.makeText(activity, "WA X \u00B7 restart unavailable",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
