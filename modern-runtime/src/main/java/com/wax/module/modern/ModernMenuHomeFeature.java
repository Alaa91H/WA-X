package com.wax.module.modern;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single WA X entry in the native WhatsApp home overflow menu (#433).
 *
 * Tapping it opens the embedded WA X Control Center in-process; it never
 * redirects straight to the Manager, and falls back to the Manager only when
 * the embedded surface cannot be shown. Exactly one WA X item is contributed:
 * no per-feature overflow clutter, no placeholder row. Does not enable any
 * unmigrated feature or call the legacy Xposed API.
 */
public final class ModernMenuHomeFeature {
    public static final String FEATURE_ID = "menu_home";
    public static final int MENU_ITEM_ID = 0x57415821;
    private static final String TAG = "WA-X MenuHome102";
    private final AtomicBoolean reportedVisible = new AtomicBoolean(false);

    public enum Outcome {
        INSTALLED,
        ALREADY_INSTALLED,
        UNSUPPORTED_TARGET,
        HOME_CLASS_MISSING,
        MENU_METHOD_MISSING,
        INVALID_HOME_TYPE,
        INVALID_MENU_SIGNATURE
    }

    public Outcome install(
            Context target,
            XposedInterface framework,
            ModernHookRegistry registry,
            Runnable onMenuItemAdded) throws Throwable {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(framework, "framework");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(onMenuItemAdded, "onMenuItemAdded");
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

        boolean installed = registry.installOnce(FEATURE_ID,
                new ModernHookRegistry.Registration("home.options_menu", () -> {
                    XposedInterface.HookHandle handle =
                            new ModernHookBridge(framework).intercept(
                                    method, "wax.modern.menu_home.options_menu", chain -> {
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
                                                    addManagerEntry(menu, activity, onMenuItemAdded);
                                                } catch (RuntimeException failure) {
                                                    Log.e(TAG, "Could not add WA X menu entry", failure);
                                                }
                                            }
                                        }
                                        return result;
                                    });
                    return handle::unhook;
                }));
        return installed ? Outcome.INSTALLED : Outcome.ALREADY_INSTALLED;
    }

    private void addManagerEntry(Menu menu, Activity activity, Runnable onMenuItemAdded) {
        if (menu.findItem(MENU_ITEM_ID) != null) return;
        MenuItem item = menu.add(Menu.NONE, MENU_ITEM_ID, 9999, "WA X");
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        item.setOnMenuItemClickListener(clicked -> {
            // Embedded first: the Control Center is the primary surface. It
            // reports whether its Activity-bound window was actually shown.
            boolean opened;
            try {
                opened = ModernControlCenterShell.showFor(activity, activity.getPackageName());
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
                Log.w(TAG, "WINDOW_FAILED package=" + activity.getPackageName()
                        + " reason=WINDOW_CREATE_FAILED exception=" + failure.getClass().getSimpleName());
                opened = false;
            }
            if (opened) {
                Log.i(TAG, "M06_CONTROL_CENTER_OPENED");
            } else {
                ModernManagerFallback.open(activity);
            }
            return true;
        });
        Log.i(TAG, "M06_MENU_HOME_ITEM_ADDED package=" + activity.getPackageName());
        if (reportedVisible.compareAndSet(false, true)) {
            // Caller enqueues evidence I/O. Never make Binder calls in the UI hook.
            onMenuItemAdded.run();
        }
    }
}
