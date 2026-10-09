package com.wax.module.modern.canary;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.wax.module.modern.ModernRuntimeProof;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CanaryApplication extends Application {
    private static final String TAG = "WA-X Canary";
    private static final String PREFS = "wax.runtime.v1";
    private static final String ENABLE_KEY = "modern_canary_enabled";
    private static final String CUSTOM_TIME_KEY = "modern.feature.custom_time.enabled";

    public interface StateListener {
        void onStateChanged(Status status);
    }

    public static final class Status {
        public final boolean connected;
        public final String message;
        public final boolean monitoringEnabled;
        public final boolean customTimeEnabled;

        Status(boolean connected, String message, boolean monitoringEnabled) {
            this(connected, message, monitoringEnabled, false);
        }

        Status(boolean connected, String message, boolean monitoringEnabled, boolean customTimeEnabled) {
            this.connected = connected;
            this.message = message;
            this.monitoringEnabled = monitoringEnabled;
            this.customTimeEnabled = customTimeEnabled;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArraySet<StateListener> listeners = new CopyOnWriteArraySet<>();
    private volatile XposedService service;
    private volatile Status last = new Status(false, "Waiting for the modern framework service.", false);

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override public void onServiceBind(XposedService bound) {
                    service = bound;
                    refresh();
                }

                @Override public void onServiceDied(XposedService dead) {
                    if (service == dead) {
                        service = null;
                        publish(new Status(false, "Framework disconnected.", false));
                    }
                }
            });
        } catch (LinkageError | RuntimeException error) {
            Log.w(TAG, "API 102 framework service unavailable", error);
            publish(new Status(false, "Service API 102 unavailable on this device.", false));
        }
    }

    public void addListener(StateListener listener) {
        listeners.add(listener);
        main.post(() -> listener.onStateChanged(last));
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    private void publish(Status next) {
        last = next;
        main.post(() -> {
            for (StateListener listener : listeners) {
                listener.onStateChanged(next);
            }
        });
    }

    public void refresh() {
        executor.execute(() -> {
            XposedService current = service;
            if (current == null) {
                publish(new Status(false, "No API 102 framework service connected.", false));
                return;
            }
            try {
                int api = current.getApiVersion();
                if (api < 102) {
                    publish(new Status(false, "Framework API " + api + " is below 102.", false));
                    return;
                }
                SharedPreferences prefs = current.getRemotePreferences(PREFS);
                boolean enabled = prefs != null && prefs.getBoolean(ENABLE_KEY, false);
                boolean customTimeEnabled = prefs != null && prefs.getBoolean(CUSTOM_TIME_KEY, false);
                List<HookedTarget> targets = current.getRunningTargets();
                StringBuilder message = new StringBuilder();
                message.append("Framework: ").append(current.getFrameworkName())
                        .append(' ').append(current.getFrameworkVersion())
                        .append("\nAPI: ").append(api);
                message.append("\nReported running targets (not feature readiness):");
                boolean found = false;
                for (HookedTarget target : targets) {
                    String process = target.getProcessName();
                    if ("com.whatsapp".equals(process) || "com.whatsapp.w4b".equals(process)) {
                        message.append("\n").append(process).append(": ").append(target.getState());
                        found = true;
                    }
                }
                if (!found) message.append("\nNone");
                message.append("\nTarget-origin loader reports (not feature readiness):");
                long now = System.currentTimeMillis();
                for (String target : new String[] {"com.whatsapp", "com.whatsapp.w4b"}) {
                    long last = prefs == null ? 0L : prefs.getLong(ModernRuntimeProof.heartbeatKey(target), 0L);
                    long observedBoot = prefs == null ? 0L : prefs.getLong(ModernRuntimeProof.bootEpochKey(target), 0L);
                    ModernRuntimeProof.State proof = ModernRuntimeProof.classify(
                            last, now, observedBoot, now - SystemClock.elapsedRealtime());
                    message.append("\n").append(target).append(": ").append(proof);
                    String featureState = prefs == null ? "UNREPORTED"
                            : prefs.getString("modern.feature.custom_time.state." + target, "UNREPORTED");
                    message.append("\n CustomTime: ").append(customTimeEnabled ? featureState : "DISABLED");
                }
                publish(new Status(true, message.toString(), enabled, customTimeEnabled));
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to read modern framework state", error);
                publish(new Status(false, "Framework query failed; inspect logcat.", false));
            }
        });
    }

    /** This opt-in only affects the canary, and requires WhatsApp to restart for a new hook. */
    public void setCustomTimeEnabled(boolean enabled) {
        executor.execute(() -> {
            XposedService current = service;
            if (current == null || current.getApiVersion() < 102) {
                refresh();
                return;
            }
            try {
                SharedPreferences prefs = current.getRemotePreferences(PREFS);
                if (prefs != null) {
                    prefs.edit().putBoolean(CUSTOM_TIME_KEY, enabled).apply();
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Cannot update CustomTime pilot preference", error);
            }
            refresh();
        });
    }
    public void setMonitoringEnabled(boolean enabled) {
        executor.execute(() -> {
            XposedService current = service;
            if (current == null || current.getApiVersion() < 102) {
                refresh();
                return;
            }
            try {
                SharedPreferences prefs = current.getRemotePreferences(PREFS);
                if (prefs != null) {
                    prefs.edit().putBoolean(ENABLE_KEY, enabled).apply();
                }
            } catch (RuntimeException error) {
                Log.w(TAG, "Failed updating remote canary preference", error);
            }
            refresh();
        });
    }
}
