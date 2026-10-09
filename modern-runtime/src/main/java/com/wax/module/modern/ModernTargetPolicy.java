package com.wax.module.modern;

/** Strict package/process identity matching for the supported WhatsApp main processes. */
public final class ModernTargetPolicy {
    private ModernTargetPolicy() {}

    /** Available already in onModuleLoaded, before any package or Application callbacks. */
    public static boolean isMainTargetProcess(String processName) {
        return "com.whatsapp".equals(processName) || "com.whatsapp.w4b".equals(processName);
    }

    /** Never hook a push/media service or unrelated package in a multi-package process. */
    public static boolean isTargetPackageForProcess(String processName, String packageName) {
        return isMainTargetProcess(processName) && processName.equals(packageName);
    }

    /**
     * Retained compatibility for callers that deliberately need firstPackage.
     * The modern bootstrap must NOT require firstPackage: it is a hint, not identity.
     */
    public static boolean isMainTarget(
            String processName, String packageName, boolean firstPackage) {
        return firstPackage && isTargetPackageForProcess(processName, packageName);
    }
}
