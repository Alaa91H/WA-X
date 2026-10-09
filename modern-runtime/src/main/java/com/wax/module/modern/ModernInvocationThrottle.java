package com.wax.module.modern;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking process-local throttling for hook telemetry. The first real invocation
 * is always reported; further calls are sampled to keep Binder work off WhatsApp UI.
 */
public final class ModernInvocationThrottle {
    private final long minimumIntervalMillis;
    private final AtomicLong lastAccepted = new AtomicLong(-1L);

    public ModernInvocationThrottle(long minimumIntervalMillis) {
        if (minimumIntervalMillis <= 0) throw new IllegalArgumentException("interval must be positive");
        this.minimumIntervalMillis = minimumIntervalMillis;
    }

    public boolean accept(long uptimeMillis) {
        if (uptimeMillis < 0) return false;
        while (true) {
            long previous = lastAccepted.get();
            if (previous >= 0 && uptimeMillis >= previous
                    && uptimeMillis - previous < minimumIntervalMillis) {
                return false;
            }
            if (lastAccepted.compareAndSet(previous, uptimeMillis)) return true;
        }
    }
}
