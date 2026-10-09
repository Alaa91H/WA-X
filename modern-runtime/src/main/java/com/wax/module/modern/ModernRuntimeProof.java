package com.wax.module.modern;

/** Pure, target-specific evidence model: a service connection is not proof of target injection. */
public final class ModernRuntimeProof {
    private static final long FRESH_WINDOW_MILLIS = 90_000L;
    private ModernRuntimeProof() {}

    public enum State { UNREPORTED, FRESH_TARGET_REPORT, STALE_TARGET_REPORT, CLOCK_MISMATCH, BOOT_MISMATCH }

    public static String heartbeatKey(String targetPackage) {
        if (!"com.whatsapp".equals(targetPackage) && !"com.whatsapp.w4b".equals(targetPackage)) {
            throw new IllegalArgumentException("Unsupported target package");
        }
        return "modern_canary.last_bootstrap." + targetPackage;
    }

    public static String processKey(String targetPackage) {
        heartbeatKey(targetPackage);
        return "modern_canary.last_process." + targetPackage;
    }

    public static String bootEpochKey(String targetPackage) {
        heartbeatKey(targetPackage);
        return "modern_canary.boot_epoch." + targetPackage;
    }

    public static State classify(long timestampMillis, long nowMillis,
            long observedBootEpochMillis, long currentBootEpochMillis) {
        State time = classify(timestampMillis, nowMillis);
        if (time != State.FRESH_TARGET_REPORT) return time;
        if (observedBootEpochMillis <= 0L || currentBootEpochMillis <= 0L) return State.BOOT_MISMATCH;
        long delta = observedBootEpochMillis > currentBootEpochMillis
                ? observedBootEpochMillis - currentBootEpochMillis
                : currentBootEpochMillis - observedBootEpochMillis;
        return delta <= 5000L ? State.FRESH_TARGET_REPORT : State.BOOT_MISMATCH;
    }

    public static State classify(long timestampMillis, long nowMillis) {
        if (timestampMillis <= 0L) return State.UNREPORTED;
        if (nowMillis < timestampMillis) return State.CLOCK_MISMATCH;
        return nowMillis - timestampMillis <= FRESH_WINDOW_MILLIS
                ? State.FRESH_TARGET_REPORT : State.STALE_TARGET_REPORT;
    }
}
