package com.wax.module.modern;

/** Pure process-scope gate shared by every modern canary and feature adapter. */
public final class ModernTargetPolicy {
    private ModernTargetPolicy() {}

    public static boolean isMainTarget(
            String processName, String packageName, boolean firstPackage) {
        return firstPackage
                && processName != null
                && processName.equals(packageName)
                && ("com.whatsapp".equals(packageName)
                    || "com.whatsapp.w4b".equals(packageName));
    }
}
