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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile Context targetContext;
    private final Set<String> started = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ModernHookRegistry hookRegistry = new ModernHookRegistry();
    private final AtomicLong formattedCalls = new AtomicLong();
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean();
    // A 45-second non-wakelock worker runs only in the injected target process.
    // Background process suspension simply causes telemetry to become stale.
    private final ScheduledExecutorService heartbeatWorker =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread worker = new Thread(task, "wax-api102-presence-heartbeat");
                worker.setDaemon(true);
                return worker;
            });
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
        // Mirror bootstrap milestones to Android logcat: framework module logs can
        // show a loaded class without exposing callback execution on some builds.
        Log.i(TAG, "M06_LIFECYCLE_MODULE_CALLBACK process=" + currentProcessName);
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
        Log.i(TAG, "M06_LIFECYCLE_PACKAGE_CALLBACK package=" + packageName);
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
                                                targetContext = target;
                                                Log.i(TAG, "M06_LIFECYCLE_ATTACH_OBSERVED package=" + packageName);
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
                Log.i(TAG, "M06_LIFECYCLE_HOOK_INSTALLED package=" + packageName
                        + " origin=" + origin);
                log(Log.INFO, TAG, "Bootstrap installed via " + origin + ": " + packageName);
                reportLifecycleStage(packageName, "ATTACH_HOOK_INSTALLED");
            }
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError) throw (VirtualMachineError) e;
            Log.e(TAG, "M06_LIFECYCLE_HOOK_FAILED package=" + packageName
                    + " origin=" + origin, e);
            log(Log.ERROR, TAG, "Bootstrap install failed via " + origin + ": " + packageName, e);
            reportLifecycleStage(packageName, "ATTACH_HOOK_FAILED");
        }
    }

    /**
     * Early stages are written only to Vector's process logs: no target Context
     * exists before Application.attach and target RemotePreferences is read-only.
     */
    private void reportLifecycleStage(String packageName, String stage) {
        log(Log.INFO, TAG, "Bootstrap lifecycle [" + packageName + "]: " + stage);
    }

    private void recordFormattedInvocation(String packageName) {
        long count = formattedCalls.incrementAndGet();
        if (!invocationThrottle.accept(SystemClock.elapsedRealtime())) return;
        // The hooked timestamp-rendering thread only enqueues background work.
        try {
            evidenceWorker.execute(() -> {
                Context context = targetContext;
                if (context == null) return;
                try {
                    boolean delivered = ModernTargetTelemetry.send(
                            context, packageName, "CUSTOM_TIME", "INVOKED");
                    if (!delivered) {
                        log(Log.WARN, TAG, "Target invocation report rejected, count=" + count);
                    }
                } catch (RuntimeException e) {
                    log(Log.WARN, TAG, "Could not deliver CustomTime invocation evidence", e);
                }
            });
        } catch (RuntimeException e) {
            log(Log.WARN, TAG, "Modern feature invocation evidence queue unavailable", e);
        }
    }

    private void sendMenuHomeEvidence(String packageName, String state) {
        Context context = targetContext;
        if (context == null) return;
        try {
            if (!ModernTargetTelemetry.send(context, packageName, "MENU_HOME", state)) {
                Log.w(TAG, "M06_MENU_HOME_EVIDENCE_REJECTED package=" + packageName);
            }
        } catch (RuntimeException deliveryFailure) {
            Log.w(TAG, "M06_MENU_HOME_EVIDENCE_UNAVAILABLE package=" + packageName,
                    deliveryFailure);
        }
    }

    private void startRuntimeHeartbeat(String packageName, Context target) {
        if (!heartbeatStarted.compareAndSet(false, true)) return;
        try {
            heartbeatWorker.scheduleWithFixedDelay(() -> {
                try {
                    if (!ModernTargetTelemetry.send(
                            target, packageName, "RUNTIME_HEARTBEAT", "ALIVE")) {
                        Log.w(TAG, "M06_RUNTIME_HEARTBEAT_REJECTED package=" + packageName);
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "M06_RUNTIME_HEARTBEAT_UNAVAILABLE package=" + packageName, error);
                }
            }, 45L, 45L, TimeUnit.SECONDS);
            Log.i(TAG, "M06_RUNTIME_HEARTBEAT_SCHEDULED package=" + packageName);
        } catch (RuntimeException scheduleFailure) {
            heartbeatStarted.set(false);
            Log.e(TAG, "M06_RUNTIME_HEARTBEAT_SCHEDULE_FAILED", scheduleFailure);
        }
    }

    private void reportBootstrap(String packageName, Context target) {
        try {
            // XposedModule.getRemotePreferences() is READ-ONLY in hooked apps.
            // Send lifecycle evidence to the Manager through a UID-authenticated provider.
            boolean heartbeat = false;
            try {
                heartbeat = ModernTargetTelemetry.send(target, packageName, "BOOTSTRAP", "ATTACHED");
                Log.i(TAG, "M06_LIFECYCLE_PROVIDER_RESULT package=" + packageName
                        + " accepted=" + heartbeat);
                log(heartbeat ? Log.INFO : Log.ERROR, TAG,
                        "Target-to-Manager bootstrap delivery: " + heartbeat + " for " + packageName);
                if (heartbeat) startRuntimeHeartbeat(packageName, target);
            } catch (RuntimeException telemetryFailure) {
                Log.e(TAG, "M06_LIFECYCLE_PROVIDER_ERROR package=" + packageName,
                        telemetryFailure);
                log(Log.ERROR, TAG, "Target telemetry provider call failed: " + packageName,
                        telemetryFailure);
            }
            // An in-WhatsApp WA X menu link is a core capability, not an opt-in
            // pilot. Install it without touching or activating legacy feature toggles.
            String menuHomeState = "ERROR";
            try {
                ModernMenuHomeFeature.Outcome outcome = new ModernMenuHomeFeature()
                        .install(target, this, hookRegistry, () -> {
                            try {
                                evidenceWorker.execute(() ->
                                        sendMenuHomeEvidence(packageName, "ITEM_ADDED"));
                            } catch (RuntimeException queueFailure) {
                                Log.w(TAG, "M06_MENU_HOME_EVIDENCE_QUEUE_FAILED", queueFailure);
                            }
                        });
                menuHomeState = outcome.name();
                Log.i(TAG, "M06_MENU_HOME_HOOK_RESULT package=" + packageName
                        + " state=" + menuHomeState);
            } catch (Throwable menuFailure) {
                if (menuFailure instanceof VirtualMachineError) throw (VirtualMachineError) menuFailure;
                Log.e(TAG, "M06_MENU_HOME_HOOK_FAILED package=" + packageName, menuFailure);
            }
            sendMenuHomeEvidence(packageName, menuHomeState);

            SharedPreferences preferences = getRemotePreferences(PREFS_GROUP);
            boolean canaryEnabled = preferences != null
                    && preferences.getBoolean("modern_canary_enabled", false);
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
            try {
                ModernTargetTelemetry.send(target, packageName, "CUSTOM_TIME", customTimeState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "CustomTime state delivery failed", error);
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
            try {
                ModernTargetTelemetry.send(target, packageName, "SHARE_LIMIT", shareLimitState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "ShareLimit state delivery failed", error);
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
                try {
                    String event = pilot == ModernPresenceFeatures.Pilot.FREEZE_LAST_SEEN
                            ? "FREEZE_LAST_SEEN" : "DND_MODE";
                    boolean accepted = ModernTargetTelemetry.send(target, packageName, event, state);
                    if (!accepted) {
                        log(Log.WARN, TAG, "Modern presence state rejected: " + pilot.name());
                    }
                } catch (RuntimeException error) {
                    log(Log.WARN, TAG, "Modern presence state delivery failed: " + pilot.name(), error);
                }
            }
            // ContactItemListener is always-on infrastructure (no user toggle):
            // the contact-bind fan-out bus other features subscribe to. Its
            // consumer (ShowOnline) migrates in a later wave; an empty bus is
            // a no-op. Resolution runs on this background reporter thread.
            String contactBusState = ModernContactItemListenerFeature.Outcome.ERROR.name();
            try {
                System.loadLibrary("dexkit");
                contactBusState = ModernContactItemListenerFeature.INSTANCE
                        .install(target, this, hookRegistry).name();
                Log.i(TAG, "M06_CONTACT_BUS_HOOK_RESULT package=" + packageName
                        + " state=" + contactBusState);
            } catch (Throwable featureFailure) {
                if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                contactBusState = "ERROR_" + featureFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern ContactItemListener bus failed on " + packageName, featureFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "CONTACT_ITEM_LISTENER", contactBusState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Contact bus state delivery failed", error);
            }
            // ConversationItemListener is always-on infrastructure (no user
            // toggle): the message-row bus six registered features subscribe
            // to. Field interpretation stays with each consumer migration.
            String conversationBusState = ModernConversationItemListenerFeature.Outcome.ERROR.name();
            try {
                conversationBusState = ModernConversationItemListenerFeature.INSTANCE
                        .install(target, this, hookRegistry).name();
                Log.i(TAG, "M06_CONVERSATION_BUS_HOOK_RESULT package=" + packageName
                        + " state=" + conversationBusState);
            } catch (Throwable featureFailure) {
                if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                conversationBusState = "ERROR_" + featureFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern ConversationItemListener bus failed on " + packageName, featureFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "CONVERSATION_ITEM_LISTENER", conversationBusState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Conversation bus state delivery failed", error);
            }
            // MenuStatusProvider is always-on infrastructure (no user toggle):
            // the status-playback menu bus StatusDownload, SeenTick and
            // DeleteStatus subscribe to. StatusItemWpp interpretation stays
            // with each consumer migration.
            String statusMenuState = ModernMenuStatusProviderFeature.Outcome.ERROR.name();
            try {
                System.loadLibrary("dexkit");
                statusMenuState = ModernMenuStatusProviderFeature.INSTANCE
                        .install(target, this, hookRegistry).name();
                Log.i(TAG, "M06_STATUS_MENU_HOOK_RESULT package=" + packageName
                        + " state=" + statusMenuState);
            } catch (Throwable featureFailure) {
                if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                statusMenuState = "ERROR_" + featureFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern MenuStatusProvider bus failed on " + packageName, featureFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "MENU_STATUS_PROVIDER", statusMenuState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Status menu state delivery failed", error);
            }
            // ActivityController is the Manager-driven contact-picker relay:
            // it opens the target's own About picker and bypasses app-lock auth
            // only for that window. Always-on infra, no user toggle.
            String activityControllerState = ModernActivityControllerFeature.Outcome.ERROR.name();
            try {
                System.loadLibrary("dexkit");
                activityControllerState = ModernActivityControllerFeature.INSTANCE
                        .install(target, this, hookRegistry).name();
                Log.i(TAG, "M06_ACTIVITY_CONTROLLER_RESULT package=" + packageName
                        + " state=" + activityControllerState);
            } catch (Throwable featureFailure) {
                if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                activityControllerState = "ERROR_" + featureFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern ActivityController failed on " + packageName, featureFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "ACTIVITY_CONTROLLER", activityControllerState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Activity controller state delivery failed", error);
            }
            // Tasker automation: user-enabled in the Manager, opt-in at runtime.
            String taskerState = ModernTaskerFeature.Outcome.DISABLED.name();
            if (preferences != null && preferences.getBoolean(ModernTaskerFeature.PREF_ENABLED, false)) {
                try {
                    System.loadLibrary("dexkit");
                    taskerState = ModernTaskerFeature.INSTANCE
                            .install(target, this, hookRegistry, preferences).name();
                    Log.i(TAG, "M06_TASKER_HOOK_RESULT package=" + packageName
                            + " state=" + taskerState);
                } catch (Throwable featureFailure) {
                    if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                    taskerState = "ERROR_" + featureFailure.getClass().getSimpleName();
                    log(Log.ERROR, TAG, "Modern Tasker bridge failed on " + packageName, featureFailure);
                }
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "TASKER", taskerState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Tasker state delivery failed", error);
            }
            // ContextMenuActionProvider is always-on infrastructure (no user
            // toggle): the message-selection popup bus whose providers build
            // contextual actions. It is a no-op while none are registered.
            String contextMenuState = ModernContextMenuActionProviderFeature.Outcome.ERROR.name();
            try {
                System.loadLibrary("dexkit");
                contextMenuState = ModernContextMenuActionProviderFeature.INSTANCE
                        .install(target, this, hookRegistry).name();
                Log.i(TAG, "M06_CONTEXT_MENU_HOOK_RESULT package=" + packageName
                        + " state=" + contextMenuState);
            } catch (Throwable featureFailure) {
                if (featureFailure instanceof VirtualMachineError) throw (VirtualMachineError) featureFailure;
                contextMenuState = "ERROR_" + featureFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern ContextMenuActionProvider failed on " + packageName, featureFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "CONTEXT_MENU_ACTION_PROVIDER", contextMenuState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Context menu state delivery failed", error);
            }
            log(Log.INFO, TAG, "API102 attached: " + packageName
                    + ", canary=" + canaryEnabled);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Modern preferences unavailable: " + packageName, e);
        }
    }

}
