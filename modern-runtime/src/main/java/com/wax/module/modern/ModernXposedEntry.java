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
 * Modern libxposed API 102 entry loaded by WA X's official module APK.
 *
 * This entry never invokes legacy XposedBridge or XSharedPreferences. Feature
 * adapters must be migrated individually, tested and opted in as appropriate.
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

    /**
     * Register the bootstrap as soon as the framework loads this module into the
     * actual WhatsApp main process. Waiting for onPackageLoaded plus isFirstPackage
     * can silently miss Application.attach on some framework/package lifecycles.
     */
    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        currentProcessName = param.getProcessName();
        log(Log.INFO, TAG,
                "Module loaded: API=" + getApiVersion() + ", process=" + currentProcessName);
        if (!ModernTargetPolicy.isMainTargetProcess(currentProcessName)) {
            return;
        }
        reportLifecycleStage(currentProcessName, "MODULE_LOADED");
        installBootstrapHook(currentProcessName, "onModuleLoaded");
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String packageName = param.getPackageName();
        if (!ModernTargetPolicy.isTargetPackageForProcess(currentProcessName, packageName)) {
            return;
        }
        // Record this independently of the bootstrap. isFirstPackage is NOT required.
        log(Log.INFO, TAG, "Target package loaded: " + packageName
                + ", firstPackage=" + param.isFirstPackage());
        reportLifecycleStage(packageName, "PACKAGE_LOADED");
        // Idempotent fallback if the framework rejected early hook registration.
        installBootstrapHook(packageName, "onPackageLoaded");
    }

    private void installBootstrapHook(String packageName, String origin) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            boolean installed = hookRegistry.installOnce("runtime.bootstrap",
                    new ModernHookRegistry.Registration("application.attach", () -> {
                        io.github.libxposed.api.XposedInterface.HookHandle handle =
                                new ModernHookBridge(this).intercept(
                                        attach, "wax.modern.application.attach", chain -> {
                                            Object result = chain.proceed();
                                            Context target = (Context) chain.getArg(0);
                                            if (target != null && packageName.equals(target.getPackageName())
                                                    && started.add(packageName)) {
                                                log(Log.INFO, TAG,
                                                        "Application.attach observed inside " + packageName);
                                                reportLifecycleStage(packageName, "ATTACH_OBSERVED");
                                                Thread reporter = new Thread(
                                                        () -> reportBootstrap(packageName, target),
                                                        "wax-api102-target-proof");
                                                reporter.setDaemon(true);
                                                reporter.start();
                                            }
                                            return result;
                                        });
                        return handle::unhook;
                    }));
            if (installed) {
                log(Log.INFO, TAG, "Bootstrap installed via " + origin + ": " + packageName);
                reportLifecycleStage(packageName, "ATTACH_HOOK_INSTALLED");
            }
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) throw (VirtualMachineError) e;
            log(Log.ERROR, TAG, "Bootstrap install failed via " + origin + ": " + packageName, e);
            reportLifecycleStage(packageName, "ATTACH_HOOK_FAILED");
        }
    }

    /**
     * Diagnostic evidence is separate from bootstrap heartbeat: merely loading the
     * module or installing a HookHandle must never be shown as target READY.
     */
    private void reportLifecycleStage(String packageName, String stage) {
        Thread worker = new Thread(() -> {
            try {
                SharedPreferences prefs = getRemotePreferences(PREFS_GROUP);
                if (prefs != null) {
                    prefs.edit()
                            .putLong("modern.runtime.milestone." + stage + "." + packageName,
                                    System.currentTimeMillis())
                            .apply();
                } else {
                    log(Log.WARN, TAG, "RemotePreferences unavailable for " + stage
                            + " in " + packageName);
                }
            } catch (RuntimeException e) {
                log(Log.ERROR, TAG, "Lifecycle evidence write failed for " + packageName
                        + " at " + stage, e);
            }
        }, "wax-api102-stage");
        worker.setDaemon(true);
        worker.start();
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
                boolean persisted = preferences.edit()
                        .putLong(ModernRuntimeProof.heartbeatKey(packageName), System.currentTimeMillis())
                        .putLong(ModernRuntimeProof.bootEpochKey(packageName),
                                System.currentTimeMillis() - SystemClock.elapsedRealtime())
                        .putString(ModernRuntimeProof.processKey(packageName), currentProcessName)
                        .commit();
                if (persisted) {
                    reportLifecycleStage(packageName, "HEARTBEAT_WRITE_CONFIRMED");
                } else {
                    reportLifecycleStage(packageName, "HEARTBEAT_WRITE_REJECTED");
                    log(Log.ERROR, TAG, "RemotePreferences refused heartbeat write: " + packageName);
                }
            } else {
                log(Log.ERROR, TAG, "RemotePreferences group missing: " + packageName);
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
            log(Log.INFO, TAG, "API102 attached: " + packageName
                    + ", canary=" + canaryEnabled);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Modern preferences unavailable: " + packageName, e);
        }
    }

}
