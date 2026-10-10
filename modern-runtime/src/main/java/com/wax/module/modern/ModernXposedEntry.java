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
            // Contact accessor chain: the modern replacement for the legacy
            // WaContactWpp wrapper. Every consumer feature that needs a name, a
            // JID or a phone number reads this, so its state is reported.
            String contactAccessState = ModernContactAccess.Outcome.ERROR.name();
            ModernContactAccess resolvedContactAccess = null;
            try {
                System.loadLibrary("dexkit");
                ModernContactAccess.Resolution contactAccess =
                        ModernContactAccess.resolve(target);
                resolvedContactAccess = contactAccess.getAccess();
                contactAccessState = contactAccess.getOutcome().name();
                ModernContactAccess.Evidence contactEvidence = contactAccess.getEvidence();
                if (contactEvidence != null) {
                    // Candidate counts and provenance, never a class name: this
                    // line reaches a diagnostics ZIP the user can share.
                    Log.i(TAG, "M06_CONTACT_ACCESS_EVIDENCE package=" + packageName
                            + " contactCandidates=" + contactEvidence.getContactCandidates()
                            + " contactDataCandidates=" + contactEvidence.getContactDataCandidates()
                            + " contactDataAnchor=" + contactEvidence.getContactDataAnchor()
                            + " dataLoaderMatches=" + contactEvidence.getContactDataLoaderMatches()
                            + " jidCandidates=" + contactEvidence.getJidCandidates()
                            + " jidLoaderMatches=" + contactEvidence.getJidLoaderMatches());
                }
                Log.i(TAG, "M06_CONTACT_ACCESS_RESULT package=" + packageName
                        + " state=" + contactAccessState);
            } catch (Throwable accessFailure) {
                if (accessFailure instanceof VirtualMachineError) throw (VirtualMachineError) accessFailure;
                contactAccessState = "ERROR_" + accessFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern contact access failed on " + packageName, accessFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "CONTACT_ACCESS", contactAccessState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Contact access state delivery failed", error);
            }
            // JID accessor: resolves the raw-string reader by signature rather
            // than by a literal member name, then derives phone numbers with
            // the legacy rules. Consumers need it for every privacy rule.
            String jidAccessState = ModernJidAccess.Outcome.ERROR.name();
            ModernJidAccess resolvedJidAccess = null;
            try {
                ModernJidAccess.Resolution jidResolution;
                // The contact chain was resolved once above. Resolving it a
                // second time here re-ran a full DEX scan on every launch and
                // then discarded the result, which is both slow and a source of
                // two different answers for one process.
                if (resolvedContactAccess != null) {
                    jidResolution = ModernJidAccess.resolve(
                            resolvedContactAccess.getJidClass());
                } else {
                    jidResolution = null;
                }
                resolvedJidAccess = jidResolution == null ? null : jidResolution.getAccess();
                jidAccessState = jidResolution == null
                        ? "JID_CLASS_UNRESOLVED"
                        : jidResolution.getOutcome().name();
                Log.i(TAG, "M06_JID_ACCESS_RESULT package=" + packageName
                        + " state=" + jidAccessState);
            } catch (Throwable jidFailure) {
                if (jidFailure instanceof VirtualMachineError) throw (VirtualMachineError) jidFailure;
                jidAccessState = "ERROR_" + jidFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern JID access failed on " + packageName, jidFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "JID_ACCESS", jidAccessState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "JID access state delivery failed", error);
            }
            // TypingPrivacy: the first migrated consumer of the accessor
            // layers, reading its numbers through ModernJidAccess.
            String typingPrivacyState = ModernTypingPrivacyFeature.Outcome.DISABLED.name();
            try {
                if (resolvedJidAccess != null) {
                    typingPrivacyState = ModernTypingPrivacyFeature.INSTANCE
                            .install(target, this, hookRegistry, preferences,
                                    resolvedJidAccess).name();
                } else {
                    typingPrivacyState = "JID_ACCESS_UNAVAILABLE";
                }
                Log.i(TAG, "M06_TYPING_PRIVACY_RESULT package=" + packageName
                        + " state=" + typingPrivacyState);
            } catch (Throwable privacyFailure) {
                if (privacyFailure instanceof VirtualMachineError) throw (VirtualMachineError) privacyFailure;
                typingPrivacyState = "ERROR_" + privacyFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern TypingPrivacy failed on " + packageName, privacyFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "TYPING_PRIVACY", typingPrivacyState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Typing privacy state delivery failed", error);
            }
            // Typing and recording are separate promises to the people the user
            // is talking to, so they are reported separately (#450). A stored
            // preference is not the evidence: only an installed hook withholding
            // the behaviour counts, and anything else says so.
            try {
                ModernTypingPrivacyFeature.Outcome privacyOutcome;
                try {
                    privacyOutcome = ModernTypingPrivacyFeature.Outcome.valueOf(typingPrivacyState);
                } catch (IllegalArgumentException notAnOutcome) {
                    // JID_ACCESS_UNAVAILABLE and the ERROR_ forms are honest
                    // states the enum does not model; both withhold nothing.
                    privacyOutcome = ModernTypingPrivacyFeature.Outcome.ERROR;
                }
                ModernTypingPrivacyFeature.BehaviourState behaviours =
                        ModernTypingPrivacyFeature.INSTANCE.behaviourState(preferences, privacyOutcome);
                Log.i(TAG, "M06_TYPING_RECORDING_STATE package=" + packageName
                        + " typingRequested=" + behaviours.getTypingRequested()
                        + " typingWithheld=" + behaviours.getTypingWithheld()
                        + " recordingRequested=" + behaviours.getRecordingRequested()
                        + " recordingWithheld=" + behaviours.getRecordingWithheld());
                ModernTargetTelemetry.send(target, packageName,
                        "TYPING_PRIVACY_TYPING",
                        behaviours.isBehaviourActive(
                                behaviours.getTypingRequested(),
                                behaviours.getTypingWithheld())
                                ? "WITHHELD" : "NOT_WITHHELD");
                ModernTargetTelemetry.send(target, packageName,
                        "TYPING_PRIVACY_RECORDING",
                        behaviours.isBehaviourActive(
                                behaviours.getRecordingRequested(),
                                behaviours.getRecordingWithheld())
                                ? "WITHHELD" : "NOT_WITHHELD");
            } catch (RuntimeException behaviourFailure) {
                log(Log.WARN, TAG, "Typing/recording state delivery failed", behaviourFailure);
            }
            // Online presence is a different claim again (#450). WhatsApp decides
            // what a remote observer sees about presence server-side, and this
            // module has no evidence that a local hook changes it. It is
            // reported as server-controlled rather than as a working switch.
            try {
                boolean onlineRequested = preferences.getBoolean("hideonline", false);
                ModernTargetTelemetry.send(target, packageName, "ONLINE_PRIVACY",
                        onlineRequested ? "SERVER_CONTROLLED" : "DISABLED");
            } catch (RuntimeException onlineFailure) {
                log(Log.WARN, TAG, "Online presence state delivery failed", onlineFailure);
            }
            // Receipt privacy family (#449). Three separate claims, reported
            // separately: withholding read receipts, releasing them only after a
            // confirmed reply, and the delivery tick, which this module reports
            // as unsupported rather than pretending to suppress.
            try {
                System.loadLibrary("dexkit");
                java.util.Map<String, ModernReceiptPrivacyFeature.Outcome> receiptStates =
                        ModernReceiptPrivacyFeature.INSTANCE.install(
                                target, this, hookRegistry, preferences);
                for (java.util.Map.Entry<String, ModernReceiptPrivacyFeature.Outcome> entry
                        : receiptStates.entrySet()) {
                    String outcome = entry.getValue().name();
                    Log.i(TAG, "M06_RECEIPT_PRIVACY_RESULT package=" + packageName
                            + " feature=" + entry.getKey()
                            + " state=" + outcome);
                    ModernTargetTelemetry.send(
                            target, packageName, receiptEventFor(entry.getKey()), outcome);
                }
            } catch (Throwable receiptFailure) {
                if (receiptFailure instanceof VirtualMachineError) {
                    throw (VirtualMachineError) receiptFailure;
                }
                log(Log.ERROR, TAG, "Receipt privacy failed on " + packageName, receiptFailure);
            }
            // Anti-revoke (#451). Only content that already arrived and was
            // already local is preserved; disappearing-mode, view-once and
            // status content are separate capability classes and are never
            // copied. Disappearing entries are dropped here so the retention
            // window is real rather than nominal.
            String antiRevokeState = ModernAntiRevokeFeature.Outcome.ERROR.name();
            try {
                System.loadLibrary("dexkit");
                ModernAntiRevokeFeature.purgeExpired(System.currentTimeMillis());
                antiRevokeState = ModernAntiRevokeFeature.INSTANCE
                        .install(target, this, hookRegistry, preferences).name();
                Log.i(TAG, "M06_ANTI_REVOKE_RESULT package=" + packageName
                        + " state=" + antiRevokeState
                        + " preserved=" + ModernAntiRevokeFeature.preservedCount()
                        + " retentionDays=" + ModernAntiRevokeFeature.INSTANCE.retentionDays(
                                preferences.getString(
                                        ModernAntiRevokeFeature.PREF_ANTIREVOKE, "0")));
            } catch (Throwable revokeFailure) {
                if (revokeFailure instanceof VirtualMachineError) {
                    throw (VirtualMachineError) revokeFailure;
                }
                antiRevokeState = "ERROR_" + revokeFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Anti-revoke failed on " + packageName, revokeFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "ANTI_REVOKE", antiRevokeState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Anti-revoke state delivery failed", error);
            }
            // Message accessor chain, reused by every message consumer.
            String messageAccessState = ModernMessageAccess.Outcome.ERROR.name();
            ModernMessageAccess resolvedMessageAccess = null;
            try {
                System.loadLibrary("dexkit");
                ModernMessageAccess.Resolution messageAccess =
                        ModernMessageAccess.resolve(target);
                resolvedMessageAccess = messageAccess.getAccess();
                messageAccessState = messageAccess.getOutcome().name();
                Log.i(TAG, "M06_MESSAGE_ACCESS_RESULT package=" + packageName
                        + " state=" + messageAccessState);
            } catch (Throwable messageFailure) {
                if (messageFailure instanceof VirtualMachineError) throw (VirtualMachineError) messageFailure;
                messageAccessState = "ERROR_" + messageFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern message access failed on " + packageName, messageFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "MESSAGE_ACCESS", messageAccessState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Message access state delivery failed", error);
            }
            // ViewOnce: keeps a viewed view-once message open by rewriting the
            // caller's view state when the message is not from this account.
            String viewOnceState = ModernViewOnceFeature.Outcome.DISABLED.name();
            if (resolvedMessageAccess != null) {
                try {
                    System.loadLibrary("dexkit");
                    viewOnceState = ModernViewOnceFeature.INSTANCE
                            .install(target, this, hookRegistry, preferences,
                                    resolvedMessageAccess).name();
                    Log.i(TAG, "M06_VIEW_ONCE_RESULT package=" + packageName
                            + " state=" + viewOnceState);
                } catch (Throwable viewOnceFailure) {
                    if (viewOnceFailure instanceof VirtualMachineError) throw (VirtualMachineError) viewOnceFailure;
                    viewOnceState = "ERROR_" + viewOnceFailure.getClass().getSimpleName();
                    log(Log.ERROR, TAG, "Modern ViewOnce failed on " + packageName, viewOnceFailure);
                }
            } else {
                viewOnceState = "MESSAGE_ACCESS_UNAVAILABLE";
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "VIEW_ONCE", viewOnceState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "View-once state delivery failed", error);
            }
            // HideChat: hides archived chats by swapping the archive view for
            // one that stays GONE. Driven by the user's existing typearchive mode.
            String hideChatState = ModernHideChatFeature.Outcome.DISABLED.name();
            try {
                System.loadLibrary("dexkit");
                hideChatState = ModernHideChatFeature.INSTANCE
                        .install(target, this, hookRegistry, preferences).name();
                Log.i(TAG, "M06_HIDE_CHAT_RESULT package=" + packageName
                        + " state=" + hideChatState);
            } catch (Throwable hideFailure) {
                if (hideFailure instanceof VirtualMachineError) throw (VirtualMachineError) hideFailure;
                hideChatState = "ERROR_" + hideFailure.getClass().getSimpleName();
                log(Log.ERROR, TAG, "Modern HideChat failed on " + packageName, hideFailure);
            }
            try {
                ModernTargetTelemetry.send(target, packageName, "HIDE_CHAT", hideChatState);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Hide chat state delivery failed", error);
            }
            log(Log.INFO, TAG, "API102 attached: " + packageName
                    + ", canary=" + canaryEnabled);
        } catch (RuntimeException e) {
            log(Log.ERROR, TAG, "Modern preferences unavailable: " + packageName, e);
        }
    }

    /**
     * Maps a receipt feature id onto the telemetry event the Manager reads.
     *
     * The three behaviours report separately because they are three separate
     * claims; folding them into one event would make an unsupported delivery
     * tick look like a working read-receipt switch.
     */
    private static String receiptEventFor(String featureId) {
        if (ModernReceiptPrivacyFeature.FEATURE_ID_AFTER_REPLY.equals(featureId)) {
            return "RECEIPT_PRIVACY_AFTER_REPLY";
        }
        if (ModernReceiptPrivacyFeature.FEATURE_ID_DELIVERY.equals(featureId)) {
            return "RECEIPT_PRIVACY_DELIVERY";
        }
        return "RECEIPT_PRIVACY_READ";
    }
}
