package com.wax.module.contract

/**
 * Installs a hook on a method of the target.
 *
 * The reason this is an interface and not a set of static helpers is measurable: fifty-seven of
 * the feature files import `XC_MethodHook` and fifty-four import `XposedBridge` directly, so the
 * hooking mechanism is spread across the whole feature layer with no way to substitute it and no
 * way to count what a feature installed.
 *
 * [hookMethod] therefore returns whether the hook was installed, and [installedCount] reports
 * what this engine has done so far. That is what lets a feature's `FeatureStartResult` carry a
 * real hook count instead of an assumption, and what lets a test assert that a feature hooked the
 * method it claims to hook without a framework present.
 *
 * A callback is [MethodCallback] rather than an Xposed type for the same reason: a test can
 * invoke it directly and assert what the feature did, which is the entire purpose of the
 * contract.
 */
interface HookEngine {
    /**
     * Hooks [methodName] on [className].
     *
     * @param after run the callback after the original method rather than before.
     * @return whether the hook was installed. `false` means the method was not found, which is
     *   an ordinary outcome on an unsupported build and must not be reported as a failure.
     */
    fun hookMethod(
        className: String,
        methodName: String,
        parameterTypes: List<Class<*>> = emptyList(),
        after: Boolean = false,
        callback: MethodCallback,
    ): Boolean

    /** Whether [className] exists in the target. Cheaper and clearer than catching a failure. */
    fun hasClass(className: String): Boolean

    /** How many hooks this engine has installed. */
    fun installedCount(): Int
}

/**
 * What a hook does around the original method.
 *
 * [after] receives the throwable the original method threw, or null, so a feature can observe a
 * failure without swallowing one: the original method has already run and its exception has
 * already been given a chance to propagate.
 */
fun interface MethodCallback {
    /**
     * @param beforeRun true before the original method runs.
     * @param thrown the throwable the original method threw, or null.
     */
    fun invoke(
        beforeRun: Boolean,
        thrown: Throwable?,
    )
}
