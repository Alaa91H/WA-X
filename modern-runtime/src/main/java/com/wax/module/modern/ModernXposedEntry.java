package com.wax.module.modern;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ModernHookRegistry hookRegistry = new ModernHookRegistry();
    private final AtomicLong formattedCalls = new AtomicLong();
    private final ModernInvocationThrottle invocationThrottle = new ModernInvocationThrottle(30_000L);
    private final ExecutorService evidenceWorker = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "wax-api102-feature-evidence");
        worker.setDaemon(true);
        return worker;
    });

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
            hookRegistry.installOnce("runtime.bootstrap",
                    new ModernHookRegistry.Registration("application.attach", () -> {
                        io.github.libxposed.api.XposedInterface.HookHandle handle =
                                new ModernHookBridge(this).intercept(
                    attach,
                    "wax.modern.application.attach",
                    chain -> {
                        Object result = chain.proceed();
                        Context target = (Context) chain.getArg(0);
                        if (target != null && packageName.equals(target.getPackageName())) {
                            // Remote framework IPC must never block WhatsApp Application.attach.
                            Thread reporter = new Thread(() -> reportBootstrap(packageName, target),
                                    "wax-api102-target-proof");
                            reporter.setDaemon(true);
                            reporter.start();
                        }
                        return result;
                    });
                        return handle::unhook;
                    }));
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) throw (VirtualMachineError) e;
            started.remove(packageName);
            log(Log.ERROR, TAG, "Modern package attach hook unavailable: " + packageName, e);
        }
    }

    private void recordFormattedInvocation(String packageName) {
        long count = formattedCalls.incrementAndGet();
        if (!invocationThrottle.accept(SystemClock.elapsedRealtime())) return;
        // A confirmed Hooker invocation is stronger evidence than a registered HookHandle.
        // No preference IPC or blocking work occurs inside WhatsApp's timestamp renderer.
        try {
            evidenceWorker.execute(() -> {
                try {
                    SharedPreferences prefs = getRemotePreferences(PREFS_GROUP);
                    if (prefs == null) return;
                    long now = System.currentTimeMillis();
                    prefs.edit()
                            .putLong(ModernInvocationEvidence.timeKey(packageName), now)
                            .putLong(ModernInvocationEvidence.bootKey(packageName),
                                    now - SystemClock.elapsedRealtime())
                            .putLong(ModernInvocationEvidence.countKey(packageName), count)
                            .apply();
                } catch (RuntimeException e) {
                    log(Log.WARN, TAG, "Modern feature invocation evidence write failed", e);
                }
            });
        } catch (RuntimeException e) {
            log(Log.WARN, TAG, "Modern feature invocation evidence queue unavailable", e);
        }
    }
    private void reportBootstrap(String packageName, Context target) {
        try {
            SharedPreferences preferences = getRemotePreferences(PREFS_GROUP);
            // Only report target-originated canary evidence: never claim feature readiness.
            boolean canaryEnabled = preferences != null
                    && preferences.getBoolean("modern_canary_enabled", false);
            if (preferences != null) {
                preferences.edit()
                        .putLong(ModernRuntimeProof.heartbeatKey(packageName), System.currentTimeMillis())
                        .putLong(ModernRuntimeProof.bootEpochKey(packageName),
                                System.currentTimeMillis() - SystemClock.elapsedRealtime())
                        .putString(ModernRuntimeProof.processKey(packageName), currentProcessName)
                        .apply();
            }
            // Optional, reversible first modern feature. Never affect WhatsApp by default.
            String customTimeState = ModernCustomTimeFeature.Outcome.DISABLED.name();
            if (preferences != null && preferences.getBoolean(ModernCustomTimeFeature.ENABLE_KEY, false)) {
                try {
                    System.loadLibrary("dexkit");
                    customTimeState = ModernCustomTimeFeature.INSTANCE
                            .install(target, this, hookRegistry, preferences,
                                    () -> recordFormattedInvocation(packageName)).name();
                } catch (Throwable featureFailure) {
                    if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                    customTimeState = "ERROR_" + featureFailure.getClass().getSimpleName();
                    log(Log.ERROR, TAG, "Modern CustomTime pilot failed on " + packageName, featureFailure);
                }
            }
            if (preferences != null) {
                preferences.edit().putString("modern.feature.custom_time.state." + packageName,
                        customTimeState).apply();
            }
            // ShareLimit is migrated with the SAME user setting (off by default).
            String shareLimitState = ModernShareLimitFeature.Outcome.DISABLED.name();
            if (preferences != null && preferences.getBoolean(ModernShareLimitFeature.ENABLE_KEY, false)) {
                try {
                    System.loadLibrary("dexkit");
                    shareLimitState = ModernShareLimitFeature.INSTANCE
                            .install(target, this, hookRegistry, preferences).name();
                } catch (Throwable featureFailure) {
                    if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                    shareLimitState = "ERROR_" + featureFailure.getClass().getSimpleName();
                    log(Log.ERROR, TAG, "Modern ShareLimit hook failed on " + packageName, featureFailure);
                }
            }
            if (preferences != null) {
                preferences.edit()
                        .putString("modern.feature.share_limit.state." + packageName, shareLimitState)
                        .apply();
            }
            for (ModernPresenceFeatures.Pilot pilot : ModernPresenceFeatures.Pilot.values()) {
                String state = ModernPresenceFeatures.Outcome.DISABLED.name();
                if (preferences != null && preferences.getBoolean(pilot.getPreferenceKey(), false)) {
                    try {
                        System.loadLibrary("dexkit");
                        state = ModernPresenceFeatures.INSTANCE
                                .install(pilot, target, this, hookRegistry, preferences).name();
                    } catch (Throwable featureFailure) {
                        if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                        state = "ERROR_" + featureFailure.getClass().getSimpleName();
                        log(Log.ERROR, TAG, "Modern presence/DND adapter failed: " + pilot.name(), featureFailure);
                    }
                }
                if (preferences != null) {
                    preferences.edit()
                            .putString("modern.feature." + pilot.getFeatureId() + ".state." + packageName, state)
                            .apply();
                }
            }
            log(Log.INFO, TAG, "API102 attached: " + packageName
                    + ", canary=" + canaryEnabled);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Modern preferences unavailable: " + packageName, e);
        }
    }

}
