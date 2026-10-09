package com.wax.module.modern;

/** Pure, fail-closed target selection for the WA X Manager menu link. */
public final class ModernMenuHomePolicy {
    private ModernMenuHomePolicy() {}

    public static String homeClassName(String packageName) {
        if (!ModernTargetPolicy.isMainTargetProcess(packageName)) return null;
        return packageName + ".home.ui.HomeActivity";
    }

    public static boolean isTargetHome(String packageName, String className) {
        String expected = homeClassName(packageName);
        return expected != null && expected.equals(className);
    }

    /** Keep the original callback result; only contribute to existing visible menus. */
    public static boolean shouldContribute(Object result, int itemCount) {
        return Boolean.TRUE.equals(result) || itemCount > 0;
    }
}
