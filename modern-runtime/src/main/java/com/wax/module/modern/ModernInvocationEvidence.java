package com.wax.module.modern;

/**
 * Target-origin proof that a modern feature hook actually ran.
 * An installed HookHandle does NOT prove execution or UI-visible behavior.
 */
public final class ModernInvocationEvidence {
    private ModernInvocationEvidence() {}

    public enum Status {
        NOT_OBSERVED, INVOKED_FRESH, INVOKED_STALE, DIFFERENT_BOOT, CLOCK_MISMATCH
    }

    public static String timeKey(String targetPackage) {
        ModernRuntimeProof.heartbeatKey(targetPackage);
        return "modern.feature.custom_time.last_invoked." + targetPackage;
    }

    public static String bootKey(String targetPackage) {
        ModernRuntimeProof.heartbeatKey(targetPackage);
        return "modern.feature.custom_time.invocation_boot." + targetPackage;
    }

    public static String countKey(String targetPackage) {
        ModernRuntimeProof.heartbeatKey(targetPackage);
        return "modern.feature.custom_time.invocation_count." + targetPackage;
    }

    public static Status classify(
            long timestampMillis,
            long bootEpochMillis,
            long count,
            long nowMillis,
            long currentBootEpochMillis) {
        if (count <= 0 || timestampMillis <= 0) return Status.NOT_OBSERVED;
        ModernRuntimeProof.State state =
                ModernRuntimeProof.classify(timestampMillis, nowMillis, bootEpochMillis, currentBootEpochMillis);
        switch (state) {
            case FRESH_TARGET_REPORT: return Status.INVOKED_FRESH;
            case STALE_TARGET_REPORT: return Status.INVOKED_STALE;
            case BOOT_MISMATCH: return Status.DIFFERENT_BOOT;
            case CLOCK_MISMATCH: return Status.CLOCK_MISMATCH;
            default: return Status.NOT_OBSERVED;
        }
    }
}
